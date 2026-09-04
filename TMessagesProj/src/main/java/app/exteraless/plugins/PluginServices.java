package app.exteraless.plugins;

import com.chaquo.python.PyObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;

import app.exteraless.plugins.files.FilesControllerJava;
import app.exteraless.plugins.intents.IntentsDispatcher;
import app.exteraless.plugins.utils.ClassProxyFactory;
import app.exteraless.plugins.xposed.XposedHooks;

/**
 * Единая точка входа Python-SDK в подсистемы движка
 * ({@code from app.exteraless.plugins import PluginServices}).
 *
 * Тонкий фасад: вся логика в классах-делегатах. Сигнатуры финальны —
 * от них зависят Python-модули SDK.
 */
public final class PluginServices {

    private PluginServices() {
    }

    /** Отказ в hookAll*: Python-сторона парсит ответ как JSON-массив unhook id. */
    private static final String EMPTY_JSON_ARRAY = "[]";

    // ---------- Xposed-хуки (делегат: XposedHooks) ----------

    /** Хук на один метод/конструктор. @return unhook id или null при ошибке. */
    public static String hookMethod(String pluginId, Object member, PyObject handler,
                                    int priority, String filtersJson) {
        if (!PluginPermissions.check(pluginId, PluginPermissions.HOOKS, "hookMethod")) {
            return null;
        }
        return XposedHooks.hookMethod(pluginId, member, handler, priority, filtersJson);
    }

    /** Хук на все перегрузки метода. @return JSON-массив unhook id. */
    public static String hookAllMethods(String pluginId, Object clazz, String methodName,
                                        PyObject handler, int priority, String filtersJson) {
        if (!PluginPermissions.check(pluginId, PluginPermissions.HOOKS, "hookAllMethods")) {
            return EMPTY_JSON_ARRAY;
        }
        return XposedHooks.hookAllMethods(pluginId, clazz, methodName, handler, priority, filtersJson);
    }

    /** Хук на все конструкторы класса. @return JSON-массив unhook id. */
    public static String hookAllConstructors(String pluginId, Object clazz, PyObject handler,
                                             int priority, String filtersJson) {
        if (!PluginPermissions.check(pluginId, PluginPermissions.HOOKS, "hookAllConstructors")) {
            return EMPTY_JSON_ARRAY;
        }
        return XposedHooks.hookAllConstructors(pluginId, clazz, handler, priority, filtersJson);
    }

    public static void unhook(String unhookId) {
        XposedHooks.unhook(unhookId);
    }

    /** Вызов оригинала захуканного метода (для MethodReplacement). */
    public static Object invokeOriginalMethod(Object member, Object thisObject, Object[] args) throws Exception {
        return XposedHooks.invokeOriginalMethod(member, thisObject, args);
    }

    public static void deoptimizeMethod(String pluginId, Object member) {
        if (!PluginPermissions.check(pluginId, PluginPermissions.HOOKS, "deoptimizeMethod")) {
            return;
        }
        XposedHooks.deoptimizeMethod(member);
    }

    public static Object allocateInstance(String pluginId, Object clazz) {
        if (!PluginPermissions.check(pluginId, PluginPermissions.HOOKS, "allocateInstance")) {
            return null;
        }
        return XposedHooks.allocateInstance(clazz);
    }

    // ---------- Class Proxy (делегат: ClassProxyFactory) ----------

    /** Сгенерировать Java-класс по JSON-спецификации. @return classKey или null. */
    public static String generateProxyClass(String pluginId, String specJson) {
        if (pluginId == null) {
            ClassProxyFactory.setLastError("no current plugin: called outside plugin context");
            return null;
        }
        String permission = ClassProxyFactory.needsHooks(specJson)
                ? PluginPermissions.HOOKS : PluginPermissions.UI;
        if (!PluginPermissions.check(pluginId, permission, "generateProxyClass")) {
            ClassProxyFactory.setLastError(
                    "permission '" + permission + "' not granted to " + pluginId);
            return null;
        }
        return ClassProxyFactory.generateProxyClass(pluginId, specJson);
    }

    /** Причина последнего отказа генератора прокси-классов; null, если отказов не было. */
    public static String getProxyError() {
        return ClassProxyFactory.getLastError();
    }

    /** Создать инстанс сгенерированного класса; python-сторона получает peer. */
    public static Object newProxyInstance(String pluginId, String classKey, String ctorSig,
                                          Object[] args, PyObject peer) {
        if (pluginId == null) {
            return null;
        }
        String permission = ClassProxyFactory.classNeedsHooks(classKey)
                ? PluginPermissions.HOOKS : PluginPermissions.UI;
        if (!PluginPermissions.check(pluginId, permission, "newProxyInstance")) {
            return null;
        }
        return ClassProxyFactory.newProxyInstance(pluginId, classKey, ctorSig, args, peer);
    }

    /** @return java.lang.Class сгенерированного класса по ключу. */
    public static Object getProxyClass(String classKey) {
        return ClassProxyFactory.getProxyClass(classKey);
    }

    /** Вызов super-метода из Python-override. */
    public static Object invokeSuper(String classKey, Object proxy, String methodSig, Object[] args) {
        // pluginId сюда не передаётся (зовётся из сгенерированного байткода), но он
        // зашит в classKey: ClassProxyFactory.generateInternal собирает ключ как
        // pluginId + ":" + sha256(spec), а двоеточие в __id__ запрещено валидатором.
        if (!PluginPermissions.check(pluginIdOfClassKey(classKey), PluginPermissions.HOOKS,
                "invokeSuper")) {
            return null;
        }
        return ClassProxyFactory.invokeSuper(classKey, proxy, methodSig, args);
    }

    private static String pluginIdOfClassKey(String classKey) {
        if (classKey == null) {
            return null;
        }
        int sep = classKey.indexOf(':');
        return sep > 0 ? classKey.substring(0, sep) : null;
    }

    // ---------- FilesController (делегат: FilesControllerJava) ----------

    /** @return secret хендлера или null. */
    public static String registerFileHandler(String pluginId, String ext, String whitelistJson,
                                             String blacklistJson, boolean hasIcon) {
        return registerFileHandler(pluginId, ext, whitelistJson, blacklistJson, hasIcon, null);
    }

    // exteraless plugins: перегрузка с PyObject-колбэком — FilesController.register
    // принимает on_click внутри FileInfo, а исходная 5-аргументная сигнатура его не несла.
    // Старая сигнатура сохранена и делегирует сюда с onClick == null.
    public static String registerFileHandler(String pluginId, String ext, String whitelistJson,
                                             String blacklistJson, boolean hasIcon, PyObject onClick) {
        if (!PluginPermissions.check(pluginId, PluginPermissions.FILES,
                "registerFileHandler(." + ext + ")")) {
            return null;
        }
        return FilesControllerJava.register(pluginId, ext, whitelistJson, blacklistJson, hasIcon, onClick);
    }

    public static void unregisterFileHandler(String pluginId, String ext, String secret) {
        FilesControllerJava.unregister(pluginId, ext, secret);
    }

    public static boolean fileIconsSupported() {
        return FilesControllerJava.isIconsSupported();
    }

    // ---------- Intents (делегат: IntentsDispatcher) ----------

    /** @return handler id. before=true — before-хендлер (может оборвать обработку). */
    public static String registerIntentHandler(String pluginId, boolean before, String filtersJson,
                                               int priority, PyObject callback) {
        if (!PluginPermissions.check(pluginId, PluginPermissions.INTENTS, "registerIntentHandler")) {
            return null;
        }
        return IntentsDispatcher.registerHandler(pluginId, before, filtersJson, priority, callback);
    }

    public static void unregisterIntentHandler(String pluginId, String handlerId) {
        IntentsDispatcher.unregisterHandler(pluginId, handlerId);
    }

    // ---------- Отложенные вызовы на UI-поток ----------

    /**
     * Выполнить python-колбэк на UI-потоке.
     *
     * Раньше SDK заворачивал колбэк в {@code dynamic_proxy(Runnable)} и отдавал этот
     * прокси прямо в Handler. Проблема в том, что до python-кода дело доходит не
     * сразу: сначала Chaquopy разворачивает сам прокси обратно в python-объект, и
     * если к моменту срабатывания класс прокси уже не тот (плагин перезагрузили,
     * движок перезапустили), разворот падает с NotImplementedError прямо в
     * UI-потоке — приложение умирает целиком, до единого except.
     *
     * Здесь в Handler уходит обычный Java Runnable, а колбэк живёт {@link PyObject}.
     * Разворачивать нечего, а если вызов всё же сломается, ошибка гасится здесь.
     */
    public static int sendRequest(int account, TLObject request, PyObject callback) {
        final RequestDelegate delegate = (response, error) -> {
            if (callback == null) {
                return;
            }
            try {
                callback.call(response, error);
            } catch (Throwable t) {
                FileLog.e("plugin request callback failed", t);
            }
        };
        return ConnectionsManager.getInstance(account).sendRequest(request, delegate);
    }

    public static void runOnUiThread(PyObject callback, long delay) {
        if (callback == null) {
            return;
        }
        final Runnable runnable = () -> {
            try {
                callback.call();
            } catch (Throwable t) {
                FileLog.e("plugin ui callback failed", t);
            }
        };
        if (delay > 0) {
            AndroidUtilities.runOnUIThread(runnable, delay);
        } else {
            AndroidUtilities.runOnUIThread(runnable);
        }
    }
}
