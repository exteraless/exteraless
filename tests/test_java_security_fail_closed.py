"""Host-JVM regressions for grant integrity and Java gate control flow.

Android/Keystore/hook implementations are stubs. Grant classes are compiled
unchanged; gate control methods are extracted unchanged from production source.
These tests do not replace an Android/Chaquopy integration test.
"""

import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest

import corpus
import javaapi


SHARED_PREFERENCES = r'''
package android.content;
import java.util.Map;
import java.util.Set;
public interface SharedPreferences {
    Map<String, ?> getAll();
    String getString(String k, String d);
    Set<String> getStringSet(String k, Set<String> d);
    int getInt(String k, int d);
    long getLong(String k, long d);
    float getFloat(String k, float d);
    boolean getBoolean(String k, boolean d);
    boolean contains(String k);
    Editor edit();
    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
    interface OnSharedPreferenceChangeListener {}
    interface Editor {
        Editor putString(String k, String v);
        Editor putStringSet(String k, Set<String> v);
        Editor putInt(String k, int v);
        Editor putLong(String k, long v);
        Editor putFloat(String k, float v);
        Editor putBoolean(String k, boolean v);
        Editor remove(String k);
        Editor clear();
        boolean commit();
        void apply();
    }
}
'''

STUBS = {
    'android/content/SharedPreferences.java': SHARED_PREFERENCES,
    'android/content/Context.java': '''package android.content;
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}''',
    'android/security/keystore/KeyGenParameterSpec.java': '''package android.security.keystore;
public class KeyGenParameterSpec implements java.security.spec.AlgorithmParameterSpec {
    public static class Builder {
        public Builder(String alias, int purpose) {}
        public KeyGenParameterSpec build() { return new KeyGenParameterSpec(); }
    }
}''',
    'android/security/keystore/KeyProperties.java': '''package android.security.keystore;
public class KeyProperties {
    public static final String KEY_ALGORITHM_HMAC_SHA256 = "HmacSHA256";
    public static final int PURPOSE_SIGN = 4;
}''',
    'android/util/Base64.java': '''package android.util;
public class Base64 {
    public static final int NO_WRAP = 2;
    public static String encodeToString(byte[] b, int flags) {
        return java.util.Base64.getEncoder().encodeToString(b);
    }
}''',
    'org/telegram/messenger/FileLog.java': '''package org.telegram.messenger;
public class FileLog {
    public static void e(String s) {}
    public static void e(String s, Throwable t) {}
    public static void d(String s) {}
}''',
    'org/telegram/messenger/ApplicationLoader.java': '''package org.telegram.messenger;
public class ApplicationLoader {
    public static android.content.Context applicationContext;
}''',
    'app/exteraless/plugins/PluginsConstants.java': '''package app.exteraless.plugins;
public class PluginsConstants {
    public static final String KEY_PLUGIN_PERMS_PREFIX = "plugin_perms_";
    public static final String KEY_PLUGIN_LEVEL_PREFIX = "plugin_level_";
    public static final String KEY_UNSAFE_MODE = "unsafe_mode";
    public static final String PREFS_NAME = "legacy_plugins";
}''',
    'app/exteraless/plugins/PluginRuntime.java': '''package app.exteraless.plugins;
public class PluginRuntime {
    public static String owner;
    public static boolean python;
    public static String current() { return owner; }
    public static boolean isPythonActive() { return python; }
}''',
    'app/exteraless/plugins/PythonPluginsEngine.java': '''package app.exteraless.plugins;
public class PythonPluginsEngine {
    public static final PythonPluginsEngine INSTANCE = new PythonPluginsEngine();
    public String owner;
    public String context;
    public boolean failure;
    public int calls;
    public static PythonPluginsEngine getInstance() { return INSTANCE; }
    public String pluginFromPythonStack(String context) {
        this.context = context;
        calls++;
        if (failure) throw new IllegalStateException("attribution unavailable");
        return owner;
    }
}''',
    'app/exteraless/plugins/xposed/XposedHooks.java': '''package app.exteraless.plugins.xposed;
public class XposedHooks {
    public static boolean ready = true;
    public static boolean ensureReady() { return ready; }
}''',
}

HARNESS = r'''
package app.exteraless.plugins;
import android.content.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.telegram.messenger.ApplicationLoader;
public class SecurityHarness {
    static void expect(boolean condition) {
        if (!condition) throw new AssertionError("security assertion failed");
    }
    static class Prefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        boolean commitWorks = true;
        public Map<String, ?> getAll() { return new HashMap<>(values); }
        public String getString(String k, String d) { return (String) values.getOrDefault(k, d); }
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String k, Set<String> d) { return (Set<String>) values.getOrDefault(k, d); }
        public int getInt(String k, int d) { return (Integer) values.getOrDefault(k, d); }
        public long getLong(String k, long d) { return (Long) values.getOrDefault(k, d); }
        public float getFloat(String k, float d) { return (Float) values.getOrDefault(k, d); }
        public boolean getBoolean(String k, boolean d) { return (Boolean) values.getOrDefault(k, d); }
        public boolean contains(String k) { return values.containsKey(k); }
        public Editor edit() { return new E(); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        class E implements Editor {
            final Map<String, Object> pending = new HashMap<>();
            boolean clear;
            Editor put(String k, Object v) { pending.put(k, v); return this; }
            public Editor putString(String k, String v) { return put(k, v); }
            public Editor putStringSet(String k, Set<String> v) { return put(k, v); }
            public Editor putInt(String k, int v) { return put(k, v); }
            public Editor putLong(String k, long v) { return put(k, v); }
            public Editor putFloat(String k, float v) { return put(k, v); }
            public Editor putBoolean(String k, boolean v) { return put(k, v); }
            public Editor remove(String k) { return put(k, null); }
            public Editor clear() { clear = true; pending.clear(); return this; }
            public boolean commit() { if (!commitWorks) return false; apply(); return true; }
            public void apply() {
                if (clear) values.clear();
                for (Map.Entry<String, Object> e : pending.entrySet()) {
                    if (e.getValue() == null) values.remove(e.getKey());
                    else values.put(e.getKey(), e.getValue());
                }
            }
        }
    }
    static SharedPreferences wrap(Prefs prefs) {
        return SignedGrantPreferences.wrap(prefs, PluginGrantStore::isGrantKey);
    }
    static void injectSigningKey(SharedPreferences signed) throws Exception {
        java.lang.reflect.Field field = SignedGrantPreferences.class.getDeclaredField("key");
        field.setAccessible(true);
        field.set(signed, new SecretKeySpec(new byte[32], "HmacSHA256"));
    }
    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        Prefs prefs = new Prefs();
        SharedPreferences signed = wrap(prefs);
        String grant = "plugin_perms_p";
        if (scenario.equals("unavailable")) {
            prefs.values.put(grant, "files");
            prefs.values.put("unsafe_mode", true);
            prefs.values.put("plugin_level_p", 2);
            expect(signed.getString(grant, null) == null);
            expect(!signed.getBoolean("unsafe_mode", false));
            expect(signed.getInt("plugin_level_p", 0) == 0);
            expect(!signed.contains(grant));
            expect(!signed.getAll().containsKey(grant));
        } else if (scenario.equals("unrelated")) {
            prefs.values.put("ordinary_setting", "value");
            expect(signed.getString("ordinary_setting", null).equals("value"));
        } else if (scenario.equals("failed-signing")) {
            signed.edit().putString(grant, "files").apply();
            expect(!prefs.contains(grant));
            expect(!prefs.contains("sig:" + grant));
        } else if (scenario.equals("valid-signature")) {
            injectSigningKey(signed);
            signed.edit().putString(grant, "files").commit();
            expect(signed.getString(grant, null).equals("files"));
            expect(prefs.contains("sig:" + grant));
        } else if (scenario.equals("tampered")) {
            injectSigningKey(signed);
            signed.edit().putString(grant, "files").commit();
            expect(signed.contains(grant));
            prefs.values.put(grant, "native");
            expect(signed.getString(grant, null) == null);
            expect(!signed.contains(grant));
        } else if (scenario.equals("migration-failure")) {
            Prefs legacy = new Prefs();
            legacy.values.put(grant, "files");
            prefs.commitWorks = false;
            ApplicationLoader.applicationContext = new Context() {
                public SharedPreferences getSharedPreferences(String name, int mode) {
                    return name.equals(PluginsConstants.PREFS_NAME) ? legacy : prefs;
                }
            };
            expect(PluginGrantStore.get() == null);
            expect(legacy.getString(grant, null).equals("files"));
        } else {
            PluginSinkGate.runScenario(scenario);
        }
    }
}
'''

GATE_STUB = r'''
package app.exteraless.plugins;
import java.net.Socket;
import java.net.URL;
import org.telegram.messenger.FileLog;
public class PluginSinkGate {
    private static volatile boolean installed;
    private static SecurityException installationFailure;
    private static boolean hookInstallationFailed;
    private static final ThreadLocal<Boolean> INSIDE = new ThreadLocal<>();
    static String missing;
    static int hooks;
    public static boolean refuseFromPlugin(String what, boolean allowTrusted) { return false; }
    static int hit(String name) {
        hooks++;
        if ("partial-error".equals(missing)) hookInstallationFailed = true;
        return name.equals(missing) ? 0 : 1;
    }
    static int hookDeny(Class<?> owner, String method, String what, String category) {
        return method.equals("startPipeline") ? 0 : hit(method);
    }
    static int hookNativeLoad() { return hit("native"); }
    static int hookNetwork(Class<?> owner, String method, String what) { return hit(method); }
    static int hookOverlayWindows() { return hit("overlay"); }
    static int hookClassResolution() { return hit("classes"); }
    static int hookMessengerSinks() { return hit("messenger"); }
    static int hookGrantStore() { return hit("grants"); }
    static int hookPythonCallbacks() { return hit("callbacks"); }
    /* PRODUCTION_METHODS */
    static void mustDeny(Runnable action) {
        try { action.run(); } catch (SecurityException expected) { return; }
        throw new AssertionError("operation was not denied");
    }
    static void runScenario(String scenario) {
        if (scenario.equals("gate-ready")) {
            install();
            SecurityHarness.expect(installed);
            int previous = hooks;
            install();
            SecurityHarness.expect(hooks == previous);
        } else if (scenario.equals("gate-unavailable")) {
            app.exteraless.plugins.xposed.XposedHooks.ready = false;
            mustDeny(PluginSinkGate::install);
            SecurityHarness.expect(!installed && hooks == 0);
        } else if (scenario.startsWith("missing-") || scenario.equals("partial-error")) {
            missing = scenario.equals("partial-error") ? scenario : scenario.substring(8);
            mustDeny(PluginSinkGate::install);
            SecurityHarness.expect(!installed);
            int previous = hooks;
            missing = null;
            mustDeny(PluginSinkGate::install);
            SecurityHarness.expect(hooks == previous);
        } else if (scenario.equals("host-attribution")) {
            PythonPluginsEngine.INSTANCE.failure = true;
            SecurityHarness.expect(enterCheck() == null);
            SecurityHarness.expect(PythonPluginsEngine.INSTANCE.calls == 0);
        } else if (scenario.equals("failed-attribution") || scenario.equals("unknown-attribution")) {
            PluginRuntime.python = true;
            PythonPluginsEngine.INSTANCE.failure = scenario.equals("failed-attribution");
            mustDeny(() -> enterCheck());
            SecurityHarness.expect(INSIDE.get() == null);
            PythonPluginsEngine.INSTANCE.failure = false;
            PythonPluginsEngine.INSTANCE.owner = "p";
            SecurityHarness.expect(enterCheck().equals("p"));
            leaveCheck();
        } else if (scenario.equals("context-attribution")) {
            PluginRuntime.owner = "outer";
            PythonPluginsEngine.INSTANCE.owner = "common";
            SecurityHarness.expect(enterCheck().equals("common"));
            SecurityHarness.expect(PythonPluginsEngine.INSTANCE.context.equals("outer"));
            leaveCheck();
            SecurityHarness.expect(INSIDE.get() == null);
        } else {
            throw new AssertionError("unknown scenario: " + scenario);
        }
    }
}
'''


def production_method(source, name):
    clean = javaapi.strip_noise(source)
    match = re.search(r'(?m)^[ \t]*(?:public|private)[^\n]*\b' + re.escape(name)
                      + r'\([^)]*\)\s*\{', clean)
    assert match, 'missing production method: ' + name
    opening = clean.index('{', match.start())
    depth = 1
    end = opening + 1
    while depth and end < len(clean):
        depth += (clean[end] == '{') - (clean[end] == '}')
        end += 1
    assert depth == 0
    return source[match.start():end]


@pytest.fixture(scope='module')
def java_security(tmp_path_factory):
    java = shutil.which('java')
    javac = shutil.which('javac')
    if not javac and os.environ.get('JAVA_HOME'):
        candidate = Path(os.environ['JAVA_HOME'], 'bin', 'javac' + ('.exe' if os.name == 'nt' else ''))
        if candidate.is_file():
            javac = str(candidate)
    ecj = os.environ.get('EXTERALESS_ECJ_JAR')
    if java is None or (javac is None and not ecj):
        pytest.skip('host Java security tests need a JDK or EXTERALESS_ECJ_JAR')
    work = tmp_path_factory.mktemp('java_security')
    sources = dict(STUBS)
    production = Path(corpus.JAVA_ROOT, 'app/exteraless/plugins')
    for name in ('SignedGrantPreferences', 'PluginGrantStore'):
        sources['app/exteraless/plugins/' + name + '.java'] = (production / (name + '.java')).read_text(encoding="utf-8")
    gate = (production / 'PluginSinkGate.java').read_text(encoding="utf-8")
    methods = '\n'.join(production_method(gate, name)
                        for name in ('install', 'requireHooks', 'enterCheck', 'leaveCheck'))
    sources['app/exteraless/plugins/PluginSinkGate.java'] = GATE_STUB.replace('/* PRODUCTION_METHODS */', methods)
    sources['app/exteraless/plugins/SecurityHarness.java'] = HARNESS
    paths = []
    for name, content in sources.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        paths.append(str(path))
    compiler = [javac, '--release', '17'] if javac else [java, '-jar', ecj, '-17', '-warn:none']
    result = subprocess.run(compiler + ['-encoding', 'UTF-8', '-d', str(work)] + paths,
                            capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, result.stdout + result.stderr
    return java, work


@pytest.mark.parametrize('scenario', [
    'unavailable', 'unrelated', 'failed-signing', 'valid-signature', 'tampered',
    'migration-failure', 'gate-ready', 'gate-unavailable', 'missing-native',
    'missing-callbacks', 'missing-grants', 'partial-error', 'host-attribution',
    'failed-attribution', 'unknown-attribution', 'context-attribution',
])
def test_java_security_control_flow(java_security, scenario):
    java, work = java_security
    result = subprocess.run([java, '-cp', str(work), 'app.exteraless.plugins.SecurityHarness', scenario],
                            capture_output=True, text=True, timeout=15)
    assert result.returncode == 0, result.stdout + result.stderr
