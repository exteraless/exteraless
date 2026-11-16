"""Exercise production backup/reset methods with Gson and in-memory preferences.

Set GSON_JAR to an installed Gson jar, or use a populated Gradle user home.
Android/UI dependencies are faked; account policy, import/export, validation,
and reset method bodies are taken verbatim from production sources.
"""
import os
import re
from pathlib import Path

import pytest

from test_account_limits import JAVA, USER_CONFIG, braced_block, jdk, method, read, run


BACKUP = JAVA / "tw/nekomimi/nekogram/helpers/SettingsBackupHelper.java"
GENERAL = JAVA / "app/exteraless/general/GeneralHelper.java"
ETG = JAVA / "app/exteraless/backup/EtgBackup.java"


def extract(source, signature):
    match = re.search(signature, source)
    assert match, f"Production declaration not found: {signature}"
    opening = source.index("{", match.start())
    return source[match.start():opening] + braced_block(source, opening)


@pytest.fixture(scope="module")
def gson_jar():
    configured = os.environ.get("GSON_JAR")
    if configured:
        jar = Path(configured)
        assert jar.is_file(), f"Invalid GSON_JAR: {jar}"
        return jar
    gradle = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    jars = sorted((gradle / "caches/modules-2/files-2.1/com.google.code.gson/gson").glob("*/*/*.jar"))
    if not jars:
        jars = sorted((gradle / "wrapper/dists").glob("*/*/*/lib/gson-*.jar"))
    if not jars:
        pytest.skip("Gson required: set GSON_JAR or populate the Gradle dependency cache")
    return jars[-1]


@pytest.fixture(scope="module")
def backup_classes(jdk, gson_jar, tmp_path_factory):
    output = tmp_path_factory.mktemp("account-settings-backup")
    sources = [JAVA / "app/exteraless/accounts/AccountLimits.java"]

    def write(relative, text):
        path = output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        sources.append(path)

    config_names = [
        "app.exteraless.OpenExteraConfig", "app.exteraless.chats.ChatsConfig",
        "app.exteraless.icons.IconPacksConfig", "app.exteraless.glyph.GlyphConfig",
        "app.exteraless.pillstack.PillStackConfig", "app.exteraless.utils.UtilsConfig",
        "app.exteraless.appearance.AppearanceConfig", "app.exteraless.general.GeneralConfig",
    ]
    for name in config_names:
        package, simple = name.rsplit(".", 1)
        write(name.replace(".", "/") + ".java", f"""
package {package};
public class {simple} {{
    public static java.util.Map<String, Integer> getConfigTypes() {{ return java.util.Map.of(); }}
    public static void reset() {{}}
}}
""")
    write("org/json/JSONException.java", "package org.json; public class JSONException extends Exception {}")
    write("org/json/JSONObject.java", """
package org.json;
public class JSONObject {
    private final com.google.gson.JsonObject value = new com.google.gson.JsonObject();
    public void put(String key, Object item) throws JSONException {
        value.add(key, item instanceof JSONObject ? ((JSONObject) item).value
                : new com.google.gson.Gson().toJsonTree(item));
    }
    public String toString(int indent) { return value.toString(); }
}
""")
    write("app/exteraless/utils/FaveStickersBackup.java", """
package app.exteraless.utils;
public class FaveStickersBackup {
    public static final String KEY = "faveStickers";
    public static org.json.JSONObject export() { return null; }
    public static void restore(com.google.gson.JsonElement value) {}
}
""")

    backup = read(BACKUP)
    backup_methods = "\n".join([
        extract(backup, r"public static String backupSettingsJson\(boolean isCloud, int indentSpaces, boolean includeApiKeys\)"),
        extract(backup, r"private static void spToJSON\(String sp, JSONObject object, Function<String, Boolean> filter\)"),
        extract(backup, r"private static void spToJSON\(String sp, JSONObject object, Function<String, Boolean> filter, boolean includeApiKeys\)"),
        extract(backup, r"public static void importSettings\(JsonObject configJson\)"),
        method(backup, "isCompatibleConfigValue"),
        method(backup, "isDeviceSpecificPushKey"),
    ])
    core = read(USER_CONFIG)
    core_methods = "\n".join(method(core, name) for name in ("getMaxAccountCount", "setMaxAccountCount"))
    etg = read(ETG)
    entry = re.search(r'list\.add\(new Entry\(SECTION_EXTERA, "maxAccountCount",[\s\S]*?;', etg)
    assert entry, "The .extera mapping must include the account admission limit"
    etg_fields = etg[etg.index('    public static final String EXTENSION'):etg.index('    private EtgBackup()')]
    etg_types = etg[etg.index('    private interface Reader'):etg.index('    // ---- Публичное API')]
    etg_methods = "\n".join(method(etg, name) for name in (
        "buildBackup", "countKnownKeys", "applyBackup", "valueOf", "sectionOf", "isExpected", "exactInteger",
    ))
    harness = HARNESS.replace("// CONFIG_IMPORTS", "\n".join(f"import {name};" for name in config_names))
    harness = harness.replace("// BACKUP_METHODS", backup_methods)
    harness = harness.replace("// CORE_METHODS", core_methods)
    harness = harness.replace("// RESET_METHODS", "\n".join(method(read(GENERAL), name) for name in ("resetSettings", "resetAllSettings")))
    harness = harness.replace("// ETG_FIELDS", etg_fields + etg_types)
    harness = harness.replace("// ETG_METHODS", etg_methods)
    harness = harness.replace("// ETG_ENTRY", entry[0])
    write("AccountSettingsBackupTest.java", harness)
    run([jdk[0], "-encoding", "UTF-8", "-cp", gson_jar, "-d", output, *sources])
    return os.pathsep.join((str(output), str(gson_jar)))


@pytest.mark.parametrize("scenario", [
    "json-valid", "json-invalid", "json-roundtrip", "json-mixed",
    "reset-general", "reset-all", "etg-valid", "etg-invalid", "etg-roundtrip",
])
def test_account_settings_backup(jdk, backup_classes, scenario):
    run([jdk[1], "-cp", backup_classes, "AccountSettingsBackupTest", scenario])


HARNESS = r'''
import app.exteraless.accounts.AccountLimits;
import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.*;
import org.json.*;
// CONFIG_IMPORTS

public class AccountSettingsBackupTest {
    static final String KEY = AccountLimits.PREFERENCE_KEY;
    static class SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        Map<String, ?> getAll() { return new HashMap<>(values); }
        int getInt(String key, int fallback) { return values.containsKey(key) ? (Integer) values.get(key) : fallback; }
        Editor edit() { return new Editor(); }
        class Editor {
            final Map<String, Object> pending = new HashMap<>();
            boolean clear;
            Editor putInt(String k, int v) { pending.put(k, v); return this; }
            Editor putLong(String k, long v) { pending.put(k, v); return this; }
            Editor putFloat(String k, float v) { pending.put(k, v); return this; }
            Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
            Editor putString(String k, String v) { pending.put(k, v); return this; }
            Editor clear() { clear = true; return this; }
            boolean commit() { if (clear) values.clear(); values.putAll(pending); return true; }
            void apply() { commit(); }
        }
    }
    static class Context {
        static final int MODE_PRIVATE = 0;
        final Map<String, SharedPreferences> files = new HashMap<>();
        SharedPreferences getSharedPreferences(String name, int mode) {
            return files.computeIfAbsent(name, ignored -> new SharedPreferences());
        }
    }
    static class Activity extends Context {}
    static class ApplicationLoader { static Context applicationContext = new Context(); }
    static class UserConfig {
        static final int MAX_ACCOUNT_DEFAULT_COUNT = AccountLimits.DEFAULT_LIMIT;
        private static final Object accountLimitSync = new Object();
        private static Integer pendingMaxAccountCount;
        // CORE_METHODS
    }
    static class ConfigItem {
        static final int configTypeBool = 0, configTypeInt = 1, configTypeString = 2;
        static final int configTypeLong = 5, configTypeFloat = 6, configTypeBoolLinkInt = 7;
        final String key;
        ConfigItem(String key) { this.key = key; }
        String getKey() { return key; }
    }
    static class NekoConfig {
        static Map<String, Integer> getConfigTypes() { return Map.of("ordinaryBool", ConfigItem.configTypeBool); }
        static SharedPreferences getPreferences() { return prefs(AccountLimits.PREFERENCES_NAME); }
        static final ConfigItem disableNumberRounding = new ConfigItem("round"), showSeconds = new ConfigItem("seconds"),
                disableVibration = new ConfigItem("vibration"), translationProvider = new ConfigItem("translator"),
                translateToLang = new ConfigItem("language"), uploadBoost = new ConfigItem("boost"),
                customSavePath = new ConfigItem("path"), hidePhone = new ConfigItem("phone"),
                openArchiveOnPull = new ConfigItem("archive");
    }
    static class NaConfig {
        static final NaConfig INSTANCE = new NaConfig();
        Map<String, Integer> getConfigTypes() { return Map.of(); }
        ConfigItem getPushServiceType() { return new ConfigItem("pushServiceType"); }
        ConfigItem getPushServiceTypeUnifiedSimple() { return new ConfigItem("pushSimple"); }
        ConfigItem getPushServiceTypeUnifiedWebPushPrivateKey() { return new ConfigItem("pushPrivate"); }
        ConfigItem getPushServiceTypeUnifiedWebPushPublicKey() { return new ConfigItem("pushPublic"); }
        ConfigItem getPushServiceTypeUnifiedWebPushAuthSecret() { return new ConfigItem("pushSecret"); }
        ConfigItem getZalgoFilter() { return new ConfigItem("zalgo"); }
        ConfigItem getIdDcType() { return new ConfigItem("idDc"); }
        ConfigItem getHideArchive() { return new ConfigItem("hideArchive"); }
        ConfigItem getDoNotUnarchiveBySwipe() { return new ConfigItem("swipe"); }
    }
    static class AyuGhostPreferences { static String ghostReadExclusionPrefix = "read_", ghostTypingExclusionPrefix = "typing_"; }
    static class AyuSavePreferences { static String saveExclusionPrefix = "save_"; }
    static class LocalNameHelper { static String chatNameOverridePrefix = "chat_", userNameOverridePrefix = "name_"; }
    static class DialogConfig { static String customForumTabPrefix = "forum_"; }
    static class LocalPeerColorHelper { static String KEY_PREFIX = "color_"; }
    static class LocalPremiumStatusHelper { static String KEY_PREFIX = "premium_"; }
    static class BookmarksHelper { static String KEY_PREFIX = "bookmark_"; }
    static class StringsKt {
        static String substringBeforeLast(String text, String delimiter, String fallback) {
            int index = text.lastIndexOf(delimiter);
            return index < 0 ? fallback : text.substring(0, index);
        }
    }
    static class PushListenerController { static void reconcilePushRegistration() {} }
    static class LegacyDefaults { static void keepPinned(SharedPreferences.Editor editor) {} }
    static class TextUtils { static boolean isEmpty(String value) { return value == null || value.isEmpty(); } }
    static class FileLog { static void e(Exception error) { throw new AssertionError(error); } }
    static class InvisibleEncryptor { static String encode(String value) { return value; } }
    static class Backup {
        // BACKUP_METHODS
    }
    static class General {
        static void resetToDefault(ConfigItem item) {}
        // RESET_METHODS
    }
    static class Etg {
        // ETG_FIELDS
        static void ensureConfigsLoaded() {}
        static List<Entry> entries() {
            ArrayList<Entry> list = new ArrayList<>();
            // ETG_ENTRY
            return list;
        }
        // ETG_METHODS
    }
    static SharedPreferences prefs(String name) {
        return ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }
    static JsonObject payload(String section, String key, JsonElement value) {
        JsonObject object = new JsonObject(), root = new JsonObject();
        object.add(key, value);
        root.add(section, object);
        return root;
    }
    static void importValue(JsonElement value, boolean etg) throws Exception {
        JsonObject root = payload(etg ? "exteraconfig" : AccountLimits.PREFERENCES_NAME,
                etg ? "maxAccountCount" : KEY, value);
        if (etg) Etg.applyBackup(root); else Backup.importSettings(root);
    }
    static void valid(boolean etg) throws Exception {
        String[] values = {"3", "10", "42", "100", "3.0", "3e0", "1e2"};
        int[] expected = {3, 10, 42, 100, 3, 3, 100};
        for (int i = 0; i < values.length; i++) {
            UserConfig.setMaxAccountCount(57);
            importValue(JsonParser.parseString(values[i]), etg);
            equal(expected[i], UserConfig.getMaxAccountCount(), "valid number " + values[i]);
            equal(Integer.class, prefs("nkmrcfg").values.get(KEY).getClass(), "stored integer type");
        }
    }
    static void invalid(boolean etg) throws Exception {
        String[] values = {"null", "true", "false", "[]", "{}", "\"42\"", "\"bad\"",
                "-1", "0", "2", "101", "3.5", "99.9999999999999999999999999999999999",
                "3.0000000000000000000000000000000001", "2147483647", "-2147483648",
                "4294967299", "-4294967196", "9223372036854775807", "1e1000", "1e-1000"};
        for (String value : values) {
            UserConfig.setMaxAccountCount(57);
            importValue(JsonParser.parseString(value), etg);
            equal(57, UserConfig.getMaxAccountCount(), "invalid input must preserve limit: " + value);
        }
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            importValue(new JsonPrimitive(value), etg);
            equal(57, UserConfig.getMaxAccountCount(), "non-finite value");
        }
        if (!etg) {
            for (String suffix : new String[]{"_long", "_float"}) {
                Backup.importSettings(payload("nkmrcfg", KEY + suffix, new JsonPrimitive(42)));
                equal(57, UserConfig.getMaxAccountCount(), "suffix must not bypass integer validation");
            }
        }
    }
    static void roundtrip(boolean etg) throws Exception {
        for (int value : new int[]{3, 10, 100}) {
            UserConfig.setMaxAccountCount(value);
            String exported = etg ? Etg.buildBackup(false) : Backup.backupSettingsJson(false, 2, false);
            JsonObject root = JsonParser.parseString(exported).getAsJsonObject();
            equal(value, root.getAsJsonObject(etg ? "exteraconfig" : "nkmrcfg")
                    .get(etg ? "maxAccountCount" : KEY).getAsInt(), "exported limit");
            UserConfig.setMaxAccountCount(57);
            if (etg) Etg.applyBackup(root); else Backup.importSettings(root);
            equal(value, UserConfig.getMaxAccountCount(), "round-tripped limit");
        }
    }
    public static void main(String[] args) throws Exception {
        prefs("userconfing").values.put("user", "account-zero");
        prefs("userconfing").values.put("selectedAccount", 99);
        prefs("userconfig99").values.put("user", "account-ninety-nine");
        Map<String, ?> zero = prefs("userconfing").getAll(), last = prefs("userconfig99").getAll();
        prefs("nkmrcfg").values.put("unrelated", "preserve");
        UserConfig.setMaxAccountCount(100);
        switch (args[0]) {
            case "json-valid": valid(false); break;
            case "json-invalid": invalid(false); break;
            case "json-roundtrip": roundtrip(false); break;
            case "etg-valid": valid(true); break;
            case "etg-invalid": invalid(true); break;
            case "etg-roundtrip": roundtrip(true); break;
            case "reset-general": General.resetSettings(); equal(10, UserConfig.getMaxAccountCount(), "General reset"); break;
            case "reset-all": General.resetAllSettings(); equal(10, UserConfig.getMaxAccountCount(), "full reset"); break;
            case "json-mixed":
                JsonObject root = payload("nkmrcfg", KEY, JsonParser.parseString("4294967299"));
                root.getAsJsonObject("nkmrcfg").addProperty("ordinaryBool", true);
                Backup.importSettings(root);
                equal(100, UserConfig.getMaxAccountCount(), "invalid limit in mixed backup");
                equal(true, prefs("nkmrcfg").values.get("ordinaryBool"), "other valid settings still import");
                break;
            default: throw new AssertionError(args[0]);
        }
        equal(zero, prefs("userconfing").getAll(), "account zero and selected account must survive");
        equal(last, prefs("userconfig99").getAll(), "high-slot session must survive");
        if (!args[0].equals("reset-all")) {
            equal("preserve", prefs("nkmrcfg").values.get("unrelated"), "unrelated setting must survive");
        }
        System.out.println(args[0] + " passed");
    }
}
'''
