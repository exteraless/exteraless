package app.exteraless.plugins.xposed;

import android.content.SharedPreferences;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.json.JSONArray;
import org.telegram.messenger.FileLog;

import java.lang.reflect.Member;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import app.exteraless.plugins.PluginsConstants;
import app.exteraless.plugins.PluginsController;
import app.exteraless.plugins.PluginsWatchdog;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Фасад Xposed-хуков поверх Aliuhook (LSPlant). Сигнатуры финальны — их зовёт
 * {@link app.exteraless.plugins.PluginServices} и Python-SDK.
 *
 * Реестр unhook-объектов: id -> Unhook. При выгрузке плагина контроллер зовёт
 * {@link #unhookAllForPlugin(String)}.
 *
 * Инициализация Aliuhook ленивая ({@link #ensureInitialized()}): статик-блок
 * XposedBridge грузит libaliuhook.so; если нативная часть недоступна — хуки
 * молча выключены (null/"[]"), приложение продолжает работать.
 */
public final class XposedHooks {

    /** unhook id -> Unhook. */
    private static final ConcurrentHashMap<String, XC_MethodHook.Unhook> UNHOOKS = new ConcurrentHashMap<>();
    /** unhook id -> pluginId (обратная связь для чистки реестров в {@link #unhook}). */
    private static final ConcurrentHashMap<String, String> HOOK_OWNERS = new ConcurrentHashMap<>();
    /** pluginId -> id всех его хуков. */
    private static final ConcurrentHashMap<String, Set<String>> PLUGIN_HOOKS = new ConcurrentHashMap<>();

    private static volatile boolean initAttempted;
    private static volatile boolean initOk;

    private XposedHooks() {
    }

    // ---------- init Aliuhook ----------

    /**
     * Первое обращение к XposedBridge дёргает его статик-блок (System.loadLibrary
     * ("aliuhook")) и заодно снимает hidden API restrictions. Вызывается лениво из
     * hookMethod/hookAll*; ошибки — в FileLog, без падения приложения.
     */
    /** Поднять Aliuhook и сказать, доступны ли хуки. Для гейта стоков. */
    public static boolean ensureReady() {
        return ensureInitialized();
    }

    public static boolean isNativeHooksBroken() {
        SharedPreferences preferences = PluginsController.getInstance().getPreferences();
        return preferences != null
                && preferences.getBoolean(PluginsConstants.KEY_NATIVE_HOOKS_BROKEN, false);
    }

    private static boolean ensureInitialized() {
        if (initAttempted) {
            return initOk;
        }
        synchronized (XposedHooks.class) {
            if (initAttempted) {
                return initOk;
            }
            initAttempted = true;
            SharedPreferences preferences = PluginsController.getInstance().getPreferences();
            if (preferences != null) {
                if (preferences.getBoolean(PluginsConstants.KEY_NATIVE_HOOKS_BROKEN, false)) {
                    FileLog.w("XposedHooks: native hooks disabled after an earlier process death");
                    return false;
                }
                if (preferences.getBoolean(PluginsConstants.KEY_NATIVE_HOOKS_PENDING, false)) {
                    preferences.edit()
                            .remove(PluginsConstants.KEY_NATIVE_HOOKS_PENDING)
                            .putBoolean(PluginsConstants.KEY_NATIVE_HOOKS_BROKEN, true)
                            .commit();
                    FileLog.e("XposedHooks: Aliuhook killed the process last time, hooks are off");
                    return false;
                }
                preferences.edit()
                        .putBoolean(PluginsConstants.KEY_NATIVE_HOOKS_PENDING, true)
                        .commit();
            }
            try {
                if (!XposedBridge.disableHiddenApiRestrictions()) {
                    // Не фатально: для методов самого приложения restrictions не мешают.
                    FileLog.e("XposedHooks: disableHiddenApiRestrictions() returned false");
                }
                // ART Profile Saver со временем перекомпилирует методы и сбивает
                // уже поставленные хуки. Гасим всегда: сюда попадают только те
                // запуски, где плагин действительно загружается, а хук, который
                // отваливается через несколько минут, неотличим от сломанного.
                boolean profileSaverOff = XposedBridge.disableProfileSaver();
                FileLog.d("XposedHooks: disableProfileSaver() -> " + profileSaverOff);
                initOk = true;
                HookGate.guardBridge();
            } catch (Throwable t) {
                initOk = false;
                FileLog.e("XposedHooks: Aliuhook init failed, method hooks disabled", t);
            }
            if (preferences != null) {
                preferences.edit().remove(PluginsConstants.KEY_NATIVE_HOOKS_PENDING).commit();
            }
            return initOk;
        }
    }

    // ---------- регистрация хуков ----------

    public static String hookMethod(String pluginId, Object member, PyObject handler,
                                    int priority, String filtersJson) {
        if (!ensureInitialized()) {
            return null;
        }
        if (pluginId == null || handler == null || !(member instanceof Member)) {
            FileLog.e("XposedHooks.hookMethod: bad arguments (pluginId=" + pluginId
                    + ", member=" + member + ", handler=" + handler + ")");
            return null;
        }
        try {
            XC_MethodHook hook = createHook(pluginId, handler, priority, filtersJson);
            HookGate.prewarm((Member) member);
            XC_MethodHook.Unhook unhook = XposedBridge.hookMethod((Member) member, hook);
            return register(pluginId, unhook);
        } catch (Throwable t) {
            FileLog.e("XposedHooks.hookMethod failed for plugin " + pluginId, t);
            return null;
        }
    }

    public static String hookAllMethods(String pluginId, Object clazz, String methodName,
                                        PyObject handler, int priority, String filtersJson) {
        if (!ensureInitialized()) {
            return "[]";
        }
        if (pluginId == null || handler == null || !(clazz instanceof Class)
                || methodName == null || methodName.isEmpty()) {
            FileLog.e("XposedHooks.hookAllMethods: bad arguments (pluginId=" + pluginId
                    + ", clazz=" + clazz + ", methodName=" + methodName + ")");
            return "[]";
        }
        try {
            XC_MethodHook hook = createHook(pluginId, handler, priority, filtersJson);
            HookGate.prewarmAllMethods((Class<?>) clazz, methodName);
            Set<XC_MethodHook.Unhook> unhooks =
                    XposedBridge.hookAllMethods((Class<?>) clazz, methodName, hook);
            return registerAll(pluginId, unhooks);
        } catch (Throwable t) {
            FileLog.e("XposedHooks.hookAllMethods failed for plugin " + pluginId, t);
            return "[]";
        }
    }

    public static String hookAllConstructors(String pluginId, Object clazz, PyObject handler,
                                             int priority, String filtersJson) {
        if (!ensureInitialized()) {
            return "[]";
        }
        if (pluginId == null || handler == null || !(clazz instanceof Class)) {
            FileLog.e("XposedHooks.hookAllConstructors: bad arguments (pluginId=" + pluginId
                    + ", clazz=" + clazz + ")");
            return "[]";
        }
        try {
            XC_MethodHook hook = createHook(pluginId, handler, priority, filtersJson);
            HookGate.prewarmAllConstructors((Class<?>) clazz);
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllConstructors((Class<?>) clazz, hook);
            return registerAll(pluginId, unhooks);
        } catch (Throwable t) {
            FileLog.e("XposedHooks.hookAllConstructors failed for plugin " + pluginId, t);
            return "[]";
        }
    }

    /**
     * Протокол хендлера: есть атрибут replace_hooked_method -> замена метода,
     * иначе before/after-хук (какой-то из колбэков может отсутствовать).
     * В Chaquopy 17 у PyObject нет hasAttr — containsKey это hasattr.
     */
    private static XC_MethodHook createHook(String pluginId, PyObject handler,
                                            int priority, String filtersJson) {
        HookFilter.Parsed filters = HookFilter.parse(filtersJson);
        if (handler.containsKey("replace_hooked_method")) {
            return new PyMethodReplacement(pluginId, handler, priority, filters.before);
        }
        return new PyMethodHook(pluginId, handler, priority, filters.before, filters.after);
    }

    private static String register(String pluginId, XC_MethodHook.Unhook unhook) {
        String id = UUID.randomUUID().toString();
        UNHOOKS.put(id, unhook);
        HOOK_OWNERS.put(id, pluginId);
        PLUGIN_HOOKS.computeIfAbsent(pluginId, k -> ConcurrentHashMap.newKeySet()).add(id);
        return id;
    }

    private static String registerAll(String pluginId, Set<XC_MethodHook.Unhook> unhooks) {
        JSONArray ids = new JSONArray();
        if (unhooks != null) {
            for (XC_MethodHook.Unhook unhook : unhooks) {
                ids.put(register(pluginId, unhook));
            }
        }
        return ids.toString();
    }

    /**
     * Взять под учёт хук, поставленный плагином напрямую через XposedBridge.
     *
     * Плагины каталога (zwylib) цепляют метод сами и лишь отдают нам Unhook,
     * чтобы он снялся при выгрузке плагина. Повторная регистрация того же
     * объекта ничего не меняет.
     */
    public static String addPluginUnhook(String pluginId, XC_MethodHook.Unhook unhook) {
        if (pluginId == null || unhook == null) {
            return null;
        }
        for (Map.Entry<String, XC_MethodHook.Unhook> entry : UNHOOKS.entrySet()) {
            if (entry.getValue() == unhook) {
                return entry.getKey();
            }
        }
        return register(pluginId, unhook);
    }

    /** Снимает ранее учтённый хук по самому объекту Unhook. */
    public static void removePluginUnhook(String pluginId, XC_MethodHook.Unhook unhook) {
        if (unhook == null) {
            return;
        }
        for (Map.Entry<String, XC_MethodHook.Unhook> entry : UNHOOKS.entrySet()) {
            if (entry.getValue() == unhook) {
                unhook(entry.getKey());
                return;
            }
        }
        try {
            unhook.unhook();
        } catch (Throwable t) {
            FileLog.e("XposedHooks.removePluginUnhook failed for " + pluginId, t);
        }
    }

    // ---------- снятие хуков ----------

    /** Идемпотентно: повторный вызов/неизвестный id — no-op. Потокобезопасно. */
    public static void unhook(String unhookId) {
        if (unhookId == null) {
            return;
        }
        // remove атомарен: unhook() зовёт ровно один поток-победитель.
        XC_MethodHook.Unhook unhook = UNHOOKS.remove(unhookId);
        if (unhook != null) {
            try {
                unhook.unhook();
            } catch (Throwable t) {
                FileLog.e("XposedHooks.unhook failed for id " + unhookId, t);
            }
        }
        String owner = HOOK_OWNERS.remove(unhookId);
        if (owner != null) {
            Set<String> ids = PLUGIN_HOOKS.get(owner);
            if (ids != null) {
                ids.remove(unhookId);
            }
        }
    }

    public static void unhookAllForPlugin(String pluginId) {
        if (pluginId == null) {
            return;
        }
        Set<String> ids = PLUGIN_HOOKS.remove(pluginId);
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            unhook(id);
        }
    }

    // ---------- утилиты для Python ----------

    public static Object invokeOriginalMethod(Object member, Object thisObject, Object[] args) throws Exception {
        return XposedBridge.invokeOriginalMethod((Member) member, thisObject, args);
    }

    public static void deoptimizeMethod(Object member) {
        if (!ensureInitialized()) {
            return;
        }
        try {
            XposedBridge.deoptimizeMethod((Member) member);
        } catch (Throwable t) {
            FileLog.e("XposedHooks.deoptimizeMethod failed for " + member, t);
        }
    }

    public static Object allocateInstance(Object clazz) {
        if (!ensureInitialized()) {
            return null;
        }
        try {
            return XposedBridge.allocateInstance((Class<?>) clazz);
        } catch (Throwable t) {
            FileLog.e("XposedHooks.allocateInstance failed for " + clazz, t);
            return null;
        }
    }

    // ---------- общее для PyMethodHook / PyMethodReplacement ----------

    /** Watchdog может отсутствовать, если PluginsController.init() ещё не вызывали. */
    static PluginsWatchdog watchdog() {
        try {
            return PluginsController.getInstance().getWatchdog();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ошибка плагина -> watchdog (плагин отключится); watchdog нет — просто лог. */
    static void reportError(String pluginId, Throwable t) {
        PluginsWatchdog watchdog = watchdog();
        if (watchdog != null) {
            try {
                watchdog.handlePluginError(pluginId, t);
                return;
            } catch (Throwable t2) {
                FileLog.e("XposedHooks: watchdog.handlePluginError failed", t2);
            }
        }
        FileLog.e("XposedHooks: plugin " + pluginId + " error", t);
    }

    /** Итог вызова Python-колбэка. ok=false — ошибка уже в watchdog, param не тронут. */
    private static volatile PyObject hookBridge;
    private static volatile boolean hookBridgeResolved;

    /**
     * base_plugin.dispatch_hook оборачивает MethodHookParam так, чтобы из Python
     * работали обе формы: param.getResult()/setResult() и param.result. Если
     * модуль почему-то недоступен, зовём обработчик напрямую — как раньше.
     */
    private static PyObject hookBridge() {
        if (!hookBridgeResolved) {
            synchronized (XposedHooks.class) {
                if (!hookBridgeResolved) {
                    try {
                        hookBridge = Python.getInstance().getModule("base_plugin");
                    } catch (Throwable t) {
                        FileLog.e("XposedHooks: base_plugin bridge unavailable", t);
                        hookBridge = null;
                    }
                    hookBridgeResolved = true;
                }
            }
        }
        return hookBridge;
    }

    static final class PyResult {
        static final PyResult ERROR = new PyResult(false, null);

        final boolean ok;
        final PyObject value;

        private PyResult(boolean ok, PyObject value) {
            this.ok = ok;
            this.value = value;
        }

        static PyResult of(PyObject value) {
            return new PyResult(true, value);
        }
    }

    /**
     * Вызвать Python-метод хука так, чтобы исключение НИКОГДА не улетело в
     * захуканный метод приложения. Обёрнуто в notePluginEnter/notePluginExit
     * (атрибуция падений в watchdog).
     */
    static PyResult callPython(String pluginId, PyObject handler, String attr,
                               XC_MethodHook.MethodHookParam param) {
        PluginsWatchdog watchdog = watchdog();
        boolean entered = false;
        String previousRuntime = app.exteraless.plugins.PluginRuntime.enter(pluginId);
        try {
            if (watchdog != null) {
                watchdog.notePluginEnter(pluginId);
                entered = true;
            }
            PyObject bridge = hookBridge();
            return PyResult.of(bridge != null
                    ? bridge.callAttr("dispatch_hook", handler, attr, param)
                    : handler.callAttr(attr, param));
        } catch (Throwable t) {
            reportError(pluginId, t);
            return PyResult.ERROR;
        } finally {
            app.exteraless.plugins.PluginRuntime.exit(previousRuntime);
            if (entered) {
                try {
                    watchdog.notePluginExit(pluginId);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
