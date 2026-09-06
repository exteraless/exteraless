package app.exteraless.plugins;

import android.content.Context;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.telegram.messenger.FileLog;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Python-рантайм движка плагинов (Chaquopy, CPython 3.12).
 * Упрощённый аналог PythonPluginsEngine.java exteraGram (3097 строк): без pip,
 * без dev-сервера, без Xposed — загрузка/выгрузка модулей, события, хуки.
 *
 * Вся Python-работа идёт через модуль-посредник {@code extera_utils.plugin_loader}
 * (лежит в src/main/python), который возвращает JSON-строки — так мост
 * не зависит от деталей конвертации Chaquopy.
 *
 * Потоки: старт интерпретатора и загрузка плагинов — на своём executor;
 * хуки зовутся синхронно из потока вызывающего (как у exteraGram), GIL их
 * сериализует. Если движок ещё не поднялся, хук возвращает DEFAULT.
 */
public class PythonPluginsEngine extends com.exteragram.messenger.plugins.PythonPluginsEngine {

    private static volatile PythonPluginsEngine instance;

    /**
     * Загруженные экземпляры BasePlugin по id — в форме exteraGram.
     * У нас они живут словарём на Python-стороне, но плагины каталога читают
     * именно это поле: {@code engine.pluginInstances.get(plugin_id)}.
     */
    public final ConcurrentHashMap<String, PyObject> pluginInstances = new ConcurrentHashMap<>();

    public ConcurrentHashMap<String, PyObject> getPluginInstances() {
        return pluginInstances;
    }

    public static PythonPluginsEngine getInstance() {
        if (instance == null) {
            synchronized (PythonPluginsEngine.class) {
                if (instance == null) {
                    instance = new PythonPluginsEngine();
                }
            }
        }
        return instance;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plugins-engine");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private volatile boolean started;
    private volatile boolean startFailed;
    private PyObject loader;

    private PythonPluginsEngine() {
    }

    public boolean isStarted() {
        return started;
    }

    /**
     * Чей код лежит на питоновском стеке этого потока прямо сейчас.
     *
     * Нужно {@link PluginSinkGate}: колбэк, пришедший из Java, метки на потоке
     * не имеет, а владелец определяется только по кадрам. Зовётся редко —
     * лишь когда сработал сток и метки нет.
     */
    public String pluginFromPythonStack() {
        if (!started || loader == null) {
            return null;
        }
        try {
            PyObject owner = loader.callAttr("plugin_frame_owner");
            return owner == null ? null : owner.toJava(String.class);
        } catch (Throwable t) {
            return null;
        }
    }

    public interface StartCallback {
        void onStarted(boolean ok);
    }

    /** Идемпотентный запуск интерпретатора. Колбэк придёт на потоке executor'а. */
    public void ensureStarted(Context appContext, StartCallback callback) {
        if (started || startFailed) {
            if (callback != null) {
                callback.onStarted(started);
            }
            return;
        }
        executor.execute(() -> {
            try {
                if (!Python.isStarted()) {
                    long t0 = System.currentTimeMillis();
                    Python.start(new AndroidPlatform(appContext));
                    FileLog.d("PluginsEngine: Python started in " + (System.currentTimeMillis() - t0) + " ms");
                }
                loader = Python.getInstance().getModule("extera_utils.plugin_loader");
                started = true;
                // Dev-сервер (порт 42690) — только в developer mode; реализован в plugin_loader.
                if (PluginsController.getInstance().isDeveloperMode()) {
                    try {
                        loader.callAttr("start_dev_server");
                    } catch (Throwable t) {
                        FileLog.e("PluginsEngine: dev server start failed", t);
                    }
                }
            } catch (Throwable t) {
                startFailed = true;
                FileLog.e("PluginsEngine: failed to start Python", t);
            }
            if (callback != null) {
                callback.onStarted(started);
            }
        });
    }

    /**
     * Что плагин может делать — статический разбор исходника перед установкой.
     * @return JSON {"network":["requests"],...} или null, если движок не поднят.
     */
    public String scanCapabilitiesJson(String path) {
        if (!started) {
            return null;
        }
        try {
            return loader.callAttr("scan_capabilities_json", path).toJava(String.class);
        } catch (Throwable t) {
            FileLog.e("PluginsEngine: capability scan failed", t);
            return null;
        }
    }

    public void setUnsafeMode(boolean value) {
        if (!started) {
            return;
        }
        try {
            loader.callAttr("set_unsafe_mode", value);
        } catch (Throwable t) {
            FileLog.e("PluginsEngine: cannot push unsafe mode", t);
        }
    }

    // ---------- журнал наблюдений ----------

    /** Журнал Python-гейта по плагину (или по всем, если id == null). */
    public String getAuditJournalJson(String pluginId) {
        if (!started) {
            return null;
        }
        try {
            return loader.callAttr("get_audit_journal_json", pluginId, 100).toJava(String.class);
        } catch (Throwable t) {
            FileLog.e("PluginsEngine: audit journal read failed", t);
            return null;
        }
    }

    /** Счётчики по категориям: что плагин делал по факту. */
    public String getAuditProfileJson(String pluginId) {
        if (!started) {
            return null;
        }
        try {
            return loader.callAttr("get_audit_profile_json", pluginId).toJava(String.class);
        } catch (Throwable t) {
            FileLog.e("PluginsEngine: audit profile read failed", t);
            return null;
        }
    }

    /** Забыть наблюдения плагина (удаление плагина, сброс профиля). */
    public void forgetAudit(String pluginId) {
        PluginAuditJournal.forget(pluginId);
        if (!started) {
            return;
        }
        try {
            loader.callAttr("forget_audit", pluginId);
        } catch (Throwable t) {
            FileLog.e("PluginsEngine: audit forget failed", t);
        }
    }

    // ---------- метаданные ----------

    /**
     * Прочитать метаданные .py-файла без его выполнения (AST на Python-стороне).
     * @return JSON {"ok":true,"meta":{...}} или {"ok":false,"error":"..."}; null при сбое моста.
     */
    public String readMetadataJson(String path) {
        if (!started) {
            return null;
        }
        try {
            return loader.callAttr("read_metadata_json", path).toJava(String.class);
        } catch (Throwable t) {
            FileLog.e("PluginsEngine: readMetadata failed for " + path, t);
            return null;
        }
    }

    // ---------- жизненный цикл ----------

    /** Загрузить плагин (импорт модуля, инстанс BasePlugin, on_plugin_load). Синхронно. */
    public String loadPlugin(Plugin plugin) {
        if (!started) {
            return "{\"ok\":false,\"error\":\"engine not started\"}";
        }
        try {
            PluginSinkGate.install();
        } catch (Throwable t) {
            FileLog.e("PluginsEngine: sink gate install failed", t);
        }
        PluginsWatchdog watchdog = PluginsController.getInstance().getWatchdog();
        // Загрузка — единственный заход, который пишется в маркер сразу.
        watchdog.notePluginEnter(plugin.id, true);
        try {
            String result = loader.callAttr("load_plugin", plugin.path, plugin.id).toJava(String.class);
            watchdog.notePluginExit(plugin.id);
            rememberInstance(plugin.id);
            return result;
        } catch (Throwable t) {
            watchdog.handlePluginError(plugin.id, t);
            return "{\"ok\":false,\"error\":" + quote(t.getMessage()) + "}";
        }
    }

    private void rememberInstance(String pluginId) {
        try {
            PyObject value = loader.callAttr("get_plugin_instance", pluginId);
            if (value == null || "None".equals(value.toString())) {
                pluginInstances.remove(pluginId);
            } else {
                pluginInstances.put(pluginId, value);
            }
        } catch (Throwable t) {
            FileLog.e("PluginsEngine: cannot cache instance of " + pluginId, t);
        }
    }

    /**
     * Пересканировать каталог плагинов и выполнить колбэк. У exteraGram менеджеры
     * плагинов зовут это после установки или удаления файла:
     * {@code controller.engines.get("python").loadPlugins(callback)}.
     */
    public void loadPlugins(Object callback) {
        try {
            PluginsController.getInstance().rescanPlugins();
        } catch (Throwable t) {
            FileLog.e("PluginsEngine.loadPlugins: rescan failed", t);
        }
        if (callback instanceof Runnable) {
            try {
                ((Runnable) callback).run();
            } catch (Throwable t) {
                FileLog.e("PluginsEngine.loadPlugins: callback failed", t);
            }
        }
    }

    /** Выгрузить плагин (on_plugin_unload + очистка). Синхронно. */
    public void unloadPlugin(Plugin plugin) {
        if (!started) {
            return;
        }
        PluginsWatchdog watchdog = PluginsController.getInstance().getWatchdog();
        watchdog.notePluginEnter(plugin.id);
        try {
            loader.callAttr("unload_plugin", plugin.id);
        } catch (Throwable t) {
            FileLog.e("PluginsEngine: unload failed for " + plugin.id, t);
        } finally {
            watchdog.notePluginExit(plugin.id);
        }
        plugin.loaded = false;
        pluginInstances.remove(plugin.id);
        // Пункты меню плагина сняты вместе с ним — подменю надо пересобрать.
        PluginsController.getInstance().notifyMenuItemsUpdated();
    }

    /** Полная деинсталляция на Python-стороне: pip-зависимости (refcount),
     *  вычистка elyx-экстракций. Звать ПОСЛЕ unloadPlugin. */
    public void uninstallPlugin(String pluginId) {
        if (!started) {
            return;
        }
        try {
            loader.callAttr("uninstall_plugin", pluginId);
        } catch (Throwable t) {
            FileLog.e("PluginsEngine: uninstall cleanup failed for " + pluginId, t);
        }
    }

    // ---------- события и хуки (синхронно, из потока вызывающего) ----------

    public void callAppEvent(String pluginId, String event) {
        callSimple(pluginId, "call_app_event", event);
    }

    public HookResult callSendMessageHook(String pluginId, int account, Object params) {
        PyObject result = callHook(pluginId, "call_send_message_hook", account, params);
        return resultOf(result);
    }

    public HookResult callPreRequestHook(String pluginId, int account, String requestName, Object request) {
        PyObject result = callHook(pluginId, "call_pre_request_hook", account, requestName, request);
        return resultOf(result);
    }

    public HookResult callPostRequestHook(String pluginId, int account, String requestName, Object response, Object error) {
        PyObject result = callHook(pluginId, "call_post_request_hook", account, requestName, response, error);
        return resultOf(result);
    }

    public HookResult callUpdateHook(String pluginId, int account, String updateName, Object update) {
        PyObject result = callHook(pluginId, "call_update_hook", account, updateName, update);
        return resultOf(result);
    }

    public HookResult callUpdatesHook(String pluginId, int account, String containerName, Object updates) {
        PyObject result = callHook(pluginId, "call_updates_hook", account, containerName, updates);
        return resultOf(result);
    }

    // ---------- экран настроек плагина ----------

    /** @return JSON-список элементов настроек (ui.settings) с текущими значениями. */
    public String getSettingsJson(String pluginId) {
        if (!started) {
            return null;
        }
        PluginsWatchdog watchdog = PluginsController.getInstance().getWatchdog();
        watchdog.notePluginEnter(pluginId);
        try {
            return loader.callAttr("get_settings_json", pluginId).toJava(String.class);
        } catch (Throwable t) {
            FileLog.e("PluginsEngine: getSettingsJson failed for " + pluginId, t);
            return null;
        } finally {
            watchdog.notePluginExit(pluginId);
        }
    }

    /**
     * Вьюха строки {@code {"type": "custom"}}: её собирает сам плагин на Python,
     * Java получает готовый {@link android.view.View} через Chaquopy.
     *
     * @return null, если плагин ничего не вернул или вернул не вьюху — строка
     *         тогда просто не рисуется, а экран остаётся живым.
     */
    public android.view.View getSettingsCustomView(String pluginId, String viewId,
                                                   android.content.Context context) {
        if (!started) {
            return null;
        }
        PluginsWatchdog watchdog = PluginsController.getInstance().getWatchdog();
        watchdog.notePluginEnter(pluginId);
        try {
            PyObject result = loader.callAttr("get_custom_setting_view", pluginId, viewId, context);
            return result == null ? null : result.toJava(android.view.View.class);
        } catch (Throwable t) {
            FileLog.e("PluginsEngine: getSettingsCustomView failed for " + pluginId, t);
            return null;
        } finally {
            watchdog.notePluginExit(pluginId);
        }
    }

    public void notifySettingChanged(String pluginId, String key, String jsonValue) {
        callSimple(pluginId, "notify_setting_changed", key, jsonValue);
    }

    public void dispatchSettingClick(String pluginId, String callbackId, android.view.View view) {
        callSimple(pluginId, "dispatch_setting_click", callbackId, view);
    }

    // ---------- внутреннее ----------

    private void callSimple(String pluginId, String method, Object... args) {
        callHook(pluginId, method, args);
    }

    private PyObject callHook(String pluginId, String method, Object... args) {
        if (!started) {
            return null;
        }
        PluginsWatchdog watchdog = PluginsController.getInstance().getWatchdog();
        watchdog.notePluginEnter(pluginId);
        try {
            Object[] callArgs = new Object[args.length + 1];
            callArgs[0] = pluginId;
            System.arraycopy(args, 0, callArgs, 1, args.length);
            return loader.callAttr(method, callArgs);
        } catch (Throwable t) {
            watchdog.handlePluginError(pluginId, t);
            return null;
        } finally {
            watchdog.notePluginExit(pluginId);
        }
    }

    private static HookResult resultOf(PyObject result) {
        if (result == null) {
            return HookResult.DEFAULT;
        }
        try {
            PyObject strategyObject = result.get("strategy");
            if (strategyObject != null) {
                HookResult.Strategy strategy = HookResult.Strategy.fromString(strategyObject.toJava(String.class));
                PyObject value = result.get("value");
                return new HookResult(strategy, value == null ? null : value.toJava(Object.class));
            }
            HookResult.Strategy strategy = HookResult.Strategy.fromString(result.toJava(String.class));
            return strategy == HookResult.Strategy.DEFAULT ? HookResult.DEFAULT : new HookResult(strategy);
        } catch (ClassCastException | PyException e) {
            FileLog.e("PluginsEngine: invalid hook strategy", e);
        }
        return HookResult.DEFAULT;
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"unknown error\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
