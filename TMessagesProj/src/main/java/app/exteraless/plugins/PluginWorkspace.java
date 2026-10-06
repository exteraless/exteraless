package app.exteraless.plugins;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import app.exteraless.plugins.models.Plugin;

/**
 * Рантайм плагина в отдельном процессе.
 *
 * Хост (этот процесс) держит JVM, таблицу классов, разрешения и согласие; воркер —
 * форкнутый потомок, у которого мост Chaquopy выключен, а Java доступна только через
 * RPC. Переключатель на плагин; пока он выключен, всё идёт прежним путём.
 */
public final class PluginWorkspace {

    private static final String KEY_PREFIX = "plugin_workspace_";
    private static final long TIMEOUT_MS = 30000L;

    private PluginWorkspace() {
    }

    private static SharedPreferences prefs() {
        SharedPreferences p = PluginsController.getInstance().getPreferences();
        if (p != null) {
            return p;
        }
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return null;
        }
        return ctx.getSharedPreferences(PluginsConstants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isOn(String pluginId) {
        if (pluginId == null) {
            return false;
        }
        SharedPreferences p = prefs();
        return p != null && p.getBoolean(KEY_PREFIX + pluginId, false);
    }

    public static void setOn(String pluginId, boolean value) {
        if (PluginSinkGate.calledFromPlugin()) {
            FileLog.w("PluginWorkspace: refused setOn for " + pluginId + " from plugin code");
            return;
        }
        SharedPreferences p = prefs();
        if (p == null || pluginId == null) {
            return;
        }
        p.edit().putBoolean(KEY_PREFIX + pluginId, value).apply();
        if (!value) {
            stop(pluginId);
        }
    }

    public static void clear(String pluginId) {
        if (PluginSinkGate.calledFromPlugin()) {
            return;
        }
        SharedPreferences p = prefs();
        if (p == null || pluginId == null) {
            return;
        }
        p.edit().remove(KEY_PREFIX + pluginId).apply();
        stop(pluginId);
    }

    public static String statusJson() {
        try {
            return PythonPluginsEngine.getInstance().workspaceStatus();
        } catch (Throwable t) {
            return "[]";
        }
    }

    static void start(String pluginId, String path) {
        String result = PythonPluginsEngine.getInstance().workspaceStart(pluginId, path);
        JSONObject envelope = envelope(result);
        if (envelope == null || !envelope.optBoolean("ok", false)) {
            FileLog.e("PluginWorkspace: cannot start the worker for " + pluginId + ": " + result);
        }
    }

    static void stop(String pluginId) {
        try {
            PythonPluginsEngine.getInstance().workspaceStop(pluginId);
        } catch (Throwable t) {
            FileLog.e("PluginWorkspace: cannot stop the worker of " + pluginId, t);
        }
    }

    static String load(String pluginId, String path) {
        start(pluginId, path);
        String value = text(pluginId, "load_plugin", path, pluginId);
        if (value == null) {
            return "{\"ok\":false,\"error\":\"workspace: worker did not load the plugin\"}";
        }
        return value;
    }

    static String text(String pluginId, String method, Object... args) {
        String raw = call(pluginId, method, args);
        JSONObject envelope = envelope(raw);
        if (envelope == null) {
            return null;
        }
        if (!envelope.optBoolean("ok", false)) {
            FileLog.e("PluginWorkspace: " + method + " failed for " + pluginId + ": "
                    + envelope.optString("error"));
            return null;
        }
        Object value = envelope.opt("value");
        return value == null ? null : String.valueOf(value);
    }

    static Object object(String pluginId, String method, Object... args) {
        try {
            return PythonPluginsEngine.getInstance().workspaceObject(pluginId, method, args);
        } catch (Throwable t) {
            FileLog.e("PluginWorkspace: " + method + " failed for " + pluginId, t);
            return null;
        }
    }

    static HookResult result(String pluginId, String method, Object... args) {
        String raw = call(pluginId, method, args);
        JSONObject envelope = envelope(raw);
        if (envelope == null || !envelope.optBoolean("ok", false)) {
            return HookResult.DEFAULT;
        }
        Object value = envelope.opt("value");
        if (value == null || value == JSONObject.NULL) {
            return HookResult.DEFAULT;
        }
        if (value instanceof String) {
            HookResult.Strategy strategy = HookResult.Strategy.fromString((String) value);
            return strategy == HookResult.Strategy.DEFAULT ? HookResult.DEFAULT : new HookResult(strategy);
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            HookResult.Strategy strategy = HookResult.Strategy.fromString(object.optString("strategy"));
            Object carried = object.opt("value");
            return new HookResult(strategy, resolve(pluginId, carried));
        }
        return HookResult.DEFAULT;
    }

    private static Object resolve(String pluginId, Object wire) {
        if (wire == null || wire == JSONObject.NULL) {
            return null;
        }
        if (wire instanceof JSONObject && ((JSONObject) wire).has("h")) {
            try {
                return PythonPluginsEngine.getInstance().workspaceResolve(pluginId, wire.toString());
            } catch (Throwable t) {
                FileLog.e("PluginWorkspace: cannot resolve a handle for " + pluginId, t);
                return null;
            }
        }
        return wire instanceof String ? wire : null;
    }

    static void invoke(String pluginId, String method, Object... args) {
        call(pluginId, method, args);
    }

    private static String call(String pluginId, String method, Object... args) {
        try {
            return PythonPluginsEngine.getInstance().workspaceText(pluginId, method, args);
        } catch (Throwable t) {
            FileLog.e("PluginWorkspace: " + method + " failed for " + pluginId, t);
            return null;
        }
    }

    private static JSONObject envelope(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            FileLog.e("PluginWorkspace: bad envelope " + raw, e);
            return null;
        }
    }

    static String pathOf(String pluginId) {
        Plugin plugin = PluginsController.getInstance().getPlugin(pluginId);
        return plugin == null ? null : plugin.path;
    }

    static long timeout() {
        return TIMEOUT_MS;
    }
}
