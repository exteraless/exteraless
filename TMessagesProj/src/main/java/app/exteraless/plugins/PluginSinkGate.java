package app.exteraless.plugins;

import org.telegram.messenger.FileLog;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Гейт на Java-стоках: то, чего Python-аудит увидеть не может.
 *
 * Python-гейт (extera_utils/audit_gate.py) ловит действия внутри CPython.
 * Но Chaquopy отдаёт плагину живые Java-объекты, и дальше всё происходит в
 * JVM: {@code java.net.URL.openConnection()}, {@code Runtime.exec()},
 * {@code Class.forName(...)} — ни одного события PEP 578. Проверено на
 * устройстве: рефлексией до {@code Runtime.exec("id")} плагин дошёл за шесть
 * событий, из которых пять — импорт {@code java.lang}.
 *
 * Здесь стоят хуки на сами стоки. Проверка идёт только если на этом потоке
 * стоит метка {@link PluginRuntime}, то есть исполняется код плагина; для
 * приложения хук — один ThreadLocal.get() и выход.
 *
 * Набор стоков сознательно узкий: запуск процессов, загрузка нативных
 * библиотек, сеть и резолв классов. Хуки на {@code FileInputStream} и прочую
 * горячую механику java.io не ставятся — цена высокая, а файловую сторону
 * закрывает Python-гейт (плагины работают с файлами через open()).
 *
 * Чего он не держит:
 * <ul>
 *   <li>плагин с разрешением {@code hooks} снимает эти хуки — против него
 *       защиты нет и быть не может.</li>
 * </ul>
 *
 * Отложенная работа закрыта: колбэк, пришедший из Java в python мимо SDK
 * (свой {@code dynamic_proxy}, поставленный в чужую очередь), метки на потоке
 * не имеет, поэтому на входе в {@code PyInvocationHandler.invoke} отмечается
 * сам факт исполнения python-кода, а владелец достаётся по кадрам питоновского
 * стека — и только если сток действительно сработал.
 */
public final class PluginSinkGate {

    private static volatile boolean installed;

    /**
     * Защита от рекурсии. Проверка внутри хука сама трогает классы и настройки,
     * а значит снова попадает в {@code loadClass} — без флага это бесконечный
     * спуск на первом же отказе.
     */
    private static final ThreadLocal<Boolean> INSIDE = new ThreadLocal<>();

    /**
     * Классы, недоступные плагину ни при каких разрешениях.
     *
     * {@code java.lang.Runtime} здесь не место: класс нужен и ради
     * {@code exit}, {@code gc}, {@code availableProcessors}, а запрет на
     * резолв ронял плагин на строке импорта, до всякого разрешения. Опасное в
     * нём закрыто по методам: {@code exec} — hookDeny, {@code load0} и
     * {@code loadLibrary0} — hookNativeLoad.
     */
    private static final String[] DENIED_CLASSES = {
            "java.lang.ProcessBuilder",
            "java.lang.Process",
            "app.exteraless.plugins.PluginPermissions",
            "app.exteraless.plugins.PluginTrustLevel",
            "app.exteraless.plugins.PluginSinkGate",
            "app.exteraless.plugins.PluginsWatchdog",
            "app.exteraless.plugins.PluginRuntime",
            "app.exteraless.plugins.PluginServices",
            "app.exteraless.plugins.PluginAuditJournal",
            "app.exteraless.plugins.PluginDenialNotice",
            "app.exteraless.plugins.files.FilesControllerJava",
            "app.exteraless.plugins.intents.IntentsDispatcher",
            "app.exteraless.plugins.menus.MenusController",
    };

    /**
     * Загрузка скомпилированного кода на ходу.
     *
     * Сначала эти классы стояли в списке «никогда», и это было ошибкой сразу с
     * двух сторон. По совместимости: 33 плагина каталога грузят dex через
     * InMemoryDexClassLoader — запрет молча сломал бы каждый одиннадцатый. По
     * смыслу: загруженный dex — это произвольный Java-код в нашем процессе,
     * ровно та же власть, что у Xposed-хуков. Значит и разрешение то же:
     * hooks, уровень, про который на экране прямо сказано, что защиты дальше нет.
     */
    private static final String[] CODE_LOADING_CLASSES = {
            "de.robv.android.xposed.",
            "dalvik.system.DexClassLoader",
            "dalvik.system.PathClassLoader",
            "dalvik.system.InMemoryDexClassLoader",
            "dalvik.system.BaseDexClassLoader",
    };

    /** Классы, требующие разрешения network. */
    private static final String[] NETWORK_CLASSES = {
            "java.net.URL",
            "java.net.Socket",
            "java.net.HttpURLConnection",
            "java.net.URLConnection",
            "java.net.DatagramSocket",
            "java.net.ServerSocket",
            "javax.net.ssl.",
            "okhttp3.",
            "org.apache.http.",
            "java.nio.channels.SocketChannel",
            "java.nio.channels.DatagramChannel",
    };

    private PluginSinkGate() {
    }

    /**
     * Поставить хуки. Идемпотентно, ошибки не фатальны: без Aliuhook просто
     * остаётся Python-гейт.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        if (!app.exteraless.plugins.xposed.XposedHooks.ensureReady()) {
            FileLog.w("PluginSinkGate: Aliuhook unavailable, Java-side gate is off");
            return;
        }
        installed = true;
        int ok = 0;
        ok += hookDeny(Runtime.class, "exec", "run a shell command", "process");
        ok += hookDeny(ProcessBuilder.class, "start", "start a process", "process");
        ok += hookDeny(ProcessBuilder.class, "startPipeline", "start a process", "process");
        ok += hookNativeLoad();
        ok += hookNetwork(URL.class, "openConnection", "open a network connection");
        ok += hookNetwork(Socket.class, "connect", "connect to the network");
        ok += hookClassResolution();
        ok += hookMessengerSinks();
        ok += hookPythonCallbacks();
        FileLog.d("PluginSinkGate: " + ok + " hooks installed");
    }

    // ---------- стоки ----------

    private static int hookDeny(Class<?> owner, String methodName, String what, String category) {
        return hookAll(owner, methodName, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String pluginId = enterCheck();
                if (pluginId == null) {
                    return;
                }
                try {
                    deny(pluginId, owner.getName() + "." + methodName, category,
                            describe(param), what + " is never available to plugins", param);
                } finally {
                    leaveCheck();
                }
            }
        });
    }

    private static int hookNetwork(Class<?> owner, String methodName, String what) {
        return hookAll(owner, methodName, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String pluginId = enterCheck();
                if (pluginId == null) {
                    return;
                }
                try {
                    String detail = param.thisObject instanceof URL
                            ? String.valueOf(param.thisObject) : describe(param);
                    if (PluginPermissions.check(pluginId, PluginPermissions.NETWORK)) {
                        PluginAuditJournal.record(pluginId, owner.getName() + "." + methodName,
                                "network", detail, true);
                        return;
                    }
                    deny(pluginId, owner.getName() + "." + methodName, "network", detail,
                            "missing the 'network' permission", param);
                } finally {
                    leaveCheck();
                }
            }
        });
    }

    /**
     * Резолв классов. Без него сеть закрывается на один шаг: плагин, которому
     * запрещён {@code java.net.URL}, достаёт тот же класс через
     * {@code Class.forName}.
     *
     * Только {@code Class.forName}: хук на {@code ClassLoader.loadClass} ронял
     * приложение изнутри ART. Резолвя класс, ClassLinker сам зовёт loadClass
     * загрузчика приложения, и с хуком это заход в сгенерированный LSPlant
     * класс прямо из середины DefineClass — рекурсия по загрузке классов,
     * которая изредка кончалась SIGSEGV на чужом потоке.
     */
    private static int hookClassResolution() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0
                        || !(param.args[0] instanceof String)) {
                    return;
                }
                String pluginId = enterCheck();
                if (pluginId == null) {
                    return;
                }
                try {
                    checkClass(pluginId, (String) param.args[0], param);
                } finally {
                    leaveCheck();
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.getThrowable() instanceof ClassNotFoundException)
                        || param.args == null || param.args.length == 0
                        || !(param.args[0] instanceof String)) {
                    return;
                }
                String requested = (String) param.args[0];
                String alias = ClassAliases.resolve(requested);
                if (alias.equals(requested)) {
                    return;
                }
                boolean initialize = param.args.length > 1 && Boolean.TRUE.equals(param.args[1]);
                ClassLoader loader = param.args.length > 2 && param.args[2] instanceof ClassLoader
                        ? (ClassLoader) param.args[2]
                        : PluginSinkGate.class.getClassLoader();
                try {
                    param.setResult(Class.forName(alias, initialize, loader));
                } catch (Throwable ignored) {
                }
            }

            private void checkClass(String pluginId, String name, MethodHookParam param) {
                String event = param.method == null ? "loadClass" : param.method.getName();
                for (String denied : DENIED_CLASSES) {
                    if (name.equals(denied)) {
                        StringBuilder trace = new StringBuilder();
                        trace.append("DENY ").append(pluginId).append(' ').append(name).append('\n');
                        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                            trace.append("    ").append(element).append('\n');
                        }
                        android.util.Log.w("exteraless-gate", trace.toString());
                        deny(pluginId, event, "reflection", name,
                                "class " + name + " is never available to plugins", param);
                        return;
                    }
                }
                for (String loader : CODE_LOADING_CLASSES) {
                    if (matchesClassName(name, loader)) {
                        if (!PluginPermissions.check(pluginId, PluginPermissions.HOOKS)) {
                            deny(pluginId, event, "native", name,
                                    "missing the 'hooks' permission", param);
                        } else {
                            PluginAuditJournal.record(pluginId, event, "native", name, true);
                        }
                        return;
                    }
                }
                for (String prefix : NETWORK_CLASSES) {
                    if (!matchesClassName(name, prefix)) {
                        continue;
                    }
                    if (!PluginPermissions.check(pluginId, PluginPermissions.NETWORK)) {
                        deny(pluginId, event, "network", name,
                                "missing the 'network' permission", param);
                    } else {
                        PluginAuditJournal.record(pluginId, event, "network", name, true);
                    }
                    return;
                }
            }
        };
        return hookExact(Class.class, "forName", hook,
                String.class, boolean.class, ClassLoader.class);
    }

    /**
     * Загрузка нативных библиотек.
     *
     * Хук стоит на внутренних {@code Runtime.load0/loadLibrary0}, а не на
     * {@code System.loadLibrary}: последний определяет загрузчик по кадру
     * вызывающего ({@code VMStack.getCallingClassLoader}), а после хука этим
     * кадром становится сгенерированный LSPlant класс с boot-загрузчиком —
     * и приложение перестаёт находить собственные .so. Внутренние методы
     * получают загрузчик аргументом, поэтому хук им не мешает.
     */
    private static int hookNativeLoad() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String pluginId = enterCheck();
                if (pluginId == null) {
                    return;
                }
                try {
                    String event = "Runtime." + param.method.getName();
                    String reason = allowNativeLoadReason(param);
                    if (reason != null) {
                        PluginAuditJournal.record(pluginId, event, "native",
                                describe(param) + " (" + reason + ")", true);
                        return;
                    }
                    if (PluginPermissions.has(pluginId, PluginPermissions.NATIVE)) {
                        PluginAuditJournal.record(pluginId, event, "native",
                                describe(param) + " (granted)", true);
                        return;
                    }
                    deny(pluginId, event, "native", describe(param),
                            "loading a native library needs the 'native' permission,"
                                    + " available only on the trusted level", param);
                } finally {
                    leaveCheck();
                }
            }
        };
        int count = hookAll(Runtime.class, "load0", hook);
        count += hookAll(Runtime.class, "loadLibrary0", hook);
        return count;
    }

    /**
     * Почему этот вызов {@code loadLibrary} не считается загрузкой плагина.
     *
     * Метка на потоке говорит лишь, что где-то ниже по стеку исполняется
     * плагин, — а .so грузит не он. Плагин зовёт публичный API, тот уходит в
     * платформу, и библиотеку тянет уже она. На vivo так падало приложение:
     * плагин вызывал {@code CameraManager.getCameraIdList()},
     * {@code VivoJavaJsonOperate.<clinit>} звал {@code System.loadLibrary},
     * наш отказ прилетал исключением из статического инициализатора —
     * и класс платформы оставался испорченным до конца жизни процесса.
     * Следующий колбэк камеры получал {@code NoClassDefFoundError} на
     * binder-потоке, где обработчика нет.
     *
     * Отсюда два послабления. Загрузчик boot — код платформы, плагину такой
     * класс не принадлежит. Статический инициализатор — отказ там бьёт не по
     * вызову, а по классу целиком, включая тех, кто плагина в глаза не видел.
     *
     * Собственная загрузка плагина под них не подходит: его классы приходят
     * из своего загрузчика, а вызов идёт из обычного метода.
     */
    private static String allowNativeLoadReason(XC_MethodHook.MethodHookParam param) {
        final Object[] args = param.args;
        final Class<?>[] types = param.method instanceof Method
                ? ((Method) param.method).getParameterTypes() : null;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Class) {
                    if (((Class<?>) args[i]).getClassLoader() == null) {
                        return "platform class " + ((Class<?>) args[i]).getName();
                    }
                    break;
                }
                if (args[i] instanceof ClassLoader) {
                    break;
                }
                if (args[i] == null && types != null && i < types.length
                        && ClassLoader.class.isAssignableFrom(types[i])) {
                    return "boot class loader";
                }
            }
        }
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if ("<clinit>".equals(element.getMethodName())) {
                return "static initializer of " + element.getClassName();
            }
        }
        return null;
    }

    /**
     * Стоки самого мессенджера.
     *
     * Разбор каталога плагинов показал, чего не хватало: 30 плагинов шлют
     * сообщения через SendMessagesHelper, 19 — произвольные запросы через
     * ConnectionsManager, 17 читают базу напрямую. Всё это шло мимо проверок:
     * они стояли на регистрации хуков нашего SDK, а не на самих действиях.
     *
     * Классы берутся по имени и могут отсутствовать (сборка без части
     * подсистем) — поэтому через Class.forName с тихим пропуском, а не
     * ссылкой на класс.
     */
    private static int hookMessengerSinks() {
        int count = 0;
        count += hookByName("org.telegram.messenger.SendMessagesHelper", "sendMessage",
                PluginPermissions.MESSAGES_SEND, "send a message");
        count += hookByName("org.telegram.messenger.SendMessagesHelper", "prepareSendingMedia",
                PluginPermissions.MESSAGES_SEND, "send media");
        count += hookByName("org.telegram.messenger.SendMessagesHelper", "prepareSendingDocument",
                PluginPermissions.MESSAGES_SEND, "send a file");
        count += hookByName("org.telegram.messenger.SendMessagesHelper", "prepareSendingPhoto",
                PluginPermissions.MESSAGES_SEND, "send a photo");
        // Ключ к базе сообщений: получив её, плагин читает переписку запросами
        // мимо всякого API.
        count += hookConnectionsManager();
        count += hookCodeLoaders();
        count += hookWebView();
        count += hookIndirectDownloads();
        return count;
    }

    /**
     * Конструкторы загрузчиков dex.
     *
     * Имя класса плагин может и не называть: 18 плагинов каталога берут
     * InMemoryDexClassLoader обычным импортом `from dalvik.system import ...`,
     * а его проверка по имени не видит. Конструктор видит любой путь.
     */
    private static int hookCodeLoaders() {
        int count = 0;
        for (String className : CODE_LOADING_CLASSES) {
            final Class<?> owner = classForName(className);
            if (owner == null) {
                continue;
            }
            try {
                for (java.lang.reflect.Constructor<?> constructor : owner.getDeclaredConstructors()) {
                    app.exteraless.plugins.xposed.HookGate.prewarm(constructor);
                    XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String pluginId = enterCheck();
                            if (pluginId == null) {
                                return;
                            }
                            try {
                                if (PluginPermissions.check(pluginId, PluginPermissions.HOOKS)) {
                                    PluginAuditJournal.record(pluginId, "loadDex", "native",
                                            className, true, className.equals("dalvik.system.PathClassLoader")
                                                    ? callerStack() : null);
                                    return;
                                }
                                // Здесь именно исключение: конструктор нельзя
                                // «не выполнить», вернув пустое значение, —
                                // объект всё равно оказался бы на руках.
                                deny(pluginId, "loadDex", "native", className,
                                        "missing the 'hooks' permission", param);
                            } finally {
                                leaveCheck();
                            }
                        }
                    });
                    count++;
                }
            } catch (Throwable t) {
                FileLog.e("PluginSinkGate: cannot hook constructors of " + className, t);
            }
        }
        return count;
    }

    /**
     * Произвольный запрос к серверу. Разрешение зависит от запроса: чтение
     * истории — это messages.read, отправка и правка — messages.send.
     * Различаем по имени класса запроса, других данных на этом уровне нет.
     */
    private static int hookConnectionsManager() {
        final Class<?> owner = classForName("org.telegram.tgnet.ConnectionsManager");
        if (owner == null) {
            return 0;
        }
        List<Member> targets = new ArrayList<>();
        Method widest = null;
        try {
            for (Method method : owner.getDeclaredMethods()) {
                String name = method.getName();
                if ("sendRequestSync".equals(name)) {
                    targets.add(method);
                } else if ("sendRequest".equals(name)
                        && (widest == null || method.getParameterTypes().length > widest.getParameterTypes().length)) {
                    widest = method;
                }
            }
        } catch (Throwable t) {
            FileLog.e("PluginSinkGate: cannot enumerate ConnectionsManager.sendRequest", t);
            return 0;
        }
        if (widest != null) {
            targets.add(widest);
        }
        return hookMembers(targets, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String pluginId = enterCheck();
                if (pluginId == null) {
                    return;
                }
                try {
                    final Object request = param.args == null || param.args.length == 0
                            ? null : param.args[0];
                    final String name = request == null ? "" : request.getClass().getSimpleName();
                    final boolean writes = isWritingRequest(name);
                    final String permission = writes
                            ? PluginPermissions.MESSAGES_SEND : PluginPermissions.MESSAGES_READ;
                    if (PluginPermissions.check(pluginId, permission)) {
                        PluginAuditJournal.record(pluginId, "sendRequest", "messages", name, true);
                        return;
                    }
                    denySilently(pluginId, "sendRequest", "messages", name,
                            "missing the '" + permission + "' permission", param);
                } finally {
                    leaveCheck();
                }
            }
        });
    }

    /**
     * WebView — дыра в сетевом гейте, найденная разбором ещё семи каналов: там
     * его создают 32 плагина из 153. Свои запросы WebView делает сам, нативно,
     * мимо socket.connect и мимо URL.openConnection, поэтому плагин без
     * разрешения на сеть мог загрузить любой адрес — с данными в параметрах.
     *
     * Гейтим загрузку, а не сам класс: WebView с локальной разметкой сети не
     * требует, и запрещать его целиком означало бы ломать разметку ни за что.
     */
    private static int hookWebView() {
        final Class<?> owner = classForName("android.webkit.WebView");
        if (owner == null) {
            return 0;
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String pluginId = enterCheck();
                if (pluginId == null) {
                    return;
                }
                try {
                    final String url = param.args == null || param.args.length == 0
                            ? "" : String.valueOf(param.args[0]);
                    if (!isRemoteUrl(url)) {
                        return;  // about:blank, data:, file:// — сети тут нет
                    }
                    if (PluginPermissions.check(pluginId, PluginPermissions.NETWORK)) {
                        PluginAuditJournal.record(pluginId, "WebView.load", "network", url, true);
                        return;
                    }
                    denySilently(pluginId, "WebView.load", "network", url,
                            "missing the 'network' permission", param);
                } finally {
                    leaveCheck();
                }
            }
        };
        int count = hookAll(owner, "loadUrl", hook);
        count += hookAll(owner, "postUrl", hook);
        count += hookAll(owner, "loadDataWithBaseURL", hook);
        return count;
    }

    /**
     * Загрузка чужими руками.
     *
     * Плагину не обязательно открывать сокет самому: скачать по адресу может
     * загрузчик самого приложения, системный DownloadManager или проигрыватель.
     * По подсчёту на 512 плагинах: ImageReceiver/FileLoader зовут 86,
     * MediaPlayer.setDataSource — 23, DownloadManager — 17. Данные при этом
     * уходят так же, как из своего сокета.
     *
     * Гейтим точки, где адрес виден и однозначен. ImageReceiver целиком не
     * трогаем — им 86 плагинов показывают обычные телеграмные картинки, а
     * загрузка по URL идёт отдельным методом ImageLoader.loadHttpFile.
     */
    private static int hookIndirectDownloads() {
        int count = 0;
        count += hookRemoteUrlArgument("org.telegram.messenger.ImageLoader", "loadHttpFile",
                "download a file");
        count += hookRemoteUrlArgument("android.media.MediaPlayer", "setDataSource",
                "stream from the network");
        final Class<?> downloads = classForName("android.app.DownloadManager");
        if (downloads != null) {
            count += hookAll(downloads, "enqueue", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String pluginId = enterCheck();
                    if (pluginId == null) {
                        return;
                    }
                    try {
                        // Адрес спрятан внутри Request и наружу не отдаётся,
                        // поэтому спрашиваем разрешение на саму постановку в
                        // очередь: локальных загрузок у DownloadManager нет.
                        if (PluginPermissions.check(pluginId, PluginPermissions.NETWORK)) {
                            PluginAuditJournal.record(pluginId, "DownloadManager.enqueue",
                                    "network", "", true);
                            return;
                        }
                        denySilently(pluginId, "DownloadManager.enqueue", "network", "",
                                "missing the 'network' permission", param);
                    } finally {
                        leaveCheck();
                    }
                }
            });
        }
        return count;
    }

    /** Хук на метод, у которого первый строковый аргумент — адрес. */
    private static int hookRemoteUrlArgument(String className, String methodName, String what) {
        final Class<?> owner = classForName(className);
        if (owner == null) {
            return 0;
        }
        return hookAll(owner, methodName, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String pluginId = enterCheck();
                if (pluginId == null) {
                    return;
                }
                try {
                    String url = null;
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            if (arg instanceof String) {
                                url = (String) arg;
                                break;
                            }
                        }
                    }
                    if (!isRemoteUrl(url)) {
                        return;  // локальный файл — сети тут нет
                    }
                    if (PluginPermissions.check(pluginId, PluginPermissions.NETWORK)) {
                        PluginAuditJournal.record(pluginId, methodName, "network", url, true);
                        return;
                    }
                    denySilently(pluginId, methodName, "network", url,
                            "missing the 'network' permission (" + what + ")", param);
                } finally {
                    leaveCheck();
                }
            }
        });
    }

    /** Адрес ведёт наружу, а не в локальную разметку. */
    private static boolean isRemoteUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("ws://") || lower.startsWith("wss://")
                || lower.startsWith("ftp://");
    }

    /** Запрос меняет что-то на сервере, а не только читает. */
    private static boolean isWritingRequest(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("send") || lower.contains("edit") || lower.contains("delete")
                || lower.contains("forward") || lower.contains("set") || lower.contains("save")
                || lower.contains("upload") || lower.contains("create") || lower.contains("join")
                || lower.contains("leave") || lower.contains("invite") || lower.contains("report");
    }

    private static Class<?> classForName(String name) {
        try {
            return Class.forName(name, false, PluginSinkGate.class.getClassLoader());
        } catch (Throwable ignored) {
            // Класса нет в этой сборке — гейтить нечего.
            return null;
        }
    }

    /** Хук на метод класса, взятого по имени: отказ = нет разрешения. */
    private static int hookByName(String className, String methodName,
                                  String permission, String what) {
        final Class<?> owner = classForName(className);
        if (owner == null) {
            return 0;
        }
        return hookAll(owner, methodName, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String pluginId = enterCheck();
                if (pluginId == null) {
                    return;
                }
                try {
                    if (PluginPermissions.check(pluginId, permission)) {
                        PluginAuditJournal.record(pluginId, methodName, categoryFor(permission),
                                describe(param), true);
                        return;
                    }
                    denySilently(pluginId, methodName, categoryFor(permission), describe(param),
                            "missing the '" + permission + "' permission", param);
                } finally {
                    leaveCheck();
                }
            }
        });
    }

    private static String categoryFor(String permission) {
        if (PluginPermissions.FILES.equals(permission)) {
            return "files";
        }
        if (PluginPermissions.INTENTS.equals(permission)) {
            return "intents";
        }
        return "messages";
    }

    // ---------- инфраструктура ----------

    /**
     * Колбэки, пришедшие из Java в python. Метку с id здесь поставить нечем:
     * Chaquopy зовёт объект, а владельца знает только питоновский стек. Поэтому
     * отмечается сам факт исполнения python-кода, а id достаётся в
     * {@link #enterCheck()} и только если сток действительно сработал.
     */
    private static int hookPythonCallbacks() {
        return hookAll(com.chaquo.python.PyInvocationHandler.class, "invoke", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                PluginRuntime.enterPython();
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                PluginRuntime.exitPython();
                Throwable error = param.getThrowable();
                if (error == null) {
                    return;
                }
                if (isStalePythonProxy(error)) {
                    FileLog.w("PluginSinkGate: dropped a callback into an unloaded plugin ("
                            + describeProxiedMethod(param) + ")");
                } else {
                    reportCallbackError(param, error);
                }
                param.setResult(zeroValueFor(proxiedMethod(param)));
            }
        });
    }

    private static void reportCallbackError(XC_MethodHook.MethodHookParam param, Throwable error) {
        String where = describeProxiedMethod(param);
        if (isPermissionDenial(error)) {
            FileLog.w("PluginSinkGate: permission denied in " + where + ": " + error.getMessage());
            return;
        }
        String pluginId = PluginRuntime.current();
        if (pluginId != null) {
            PluginsWatchdog watchdog = null;
            try {
                watchdog = PluginsController.getInstance().getWatchdog();
            } catch (Throwable ignored) {
            }
            if (watchdog != null) {
                try {
                    watchdog.handlePluginError(pluginId, error);
                    return;
                } catch (Throwable t) {
                    FileLog.e("PluginSinkGate: watchdog.handlePluginError failed", t);
                }
            }
        }
        FileLog.e("PluginSinkGate: callback " + where + " failed", error);
    }

    private static boolean isPermissionDenial(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof SecurityException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.startsWith("PermissionError:")) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    private static boolean isStalePythonProxy(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            String message = current.getMessage();
            if (message != null && (message.contains("_chaquopyGetDict")
                    || message.contains("_chaquopySetDict"))) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    private static Method proxiedMethod(XC_MethodHook.MethodHookParam param) {
        Object[] args = param.args;
        if (args != null && args.length > 1 && args[1] instanceof Method) {
            return (Method) args[1];
        }
        return null;
    }

    private static String describeProxiedMethod(XC_MethodHook.MethodHookParam param) {
        Method method = proxiedMethod(param);
        return method == null ? "unknown method"
                : method.getDeclaringClass().getName() + "." + method.getName();
    }

    private static Object zeroValueFor(Method method) {
        Class<?> type = method == null ? null : method.getReturnType();
        if (type == null || !type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }

    public static boolean allowDatabaseAccess() {
        if (!installed || (PluginRuntime.current() == null && !PluginRuntime.isPythonActive())) {
            return true;
        }
        String pluginId = enterCheck();
        if (pluginId == null) {
            return true;
        }
        try {
            if (PluginPermissions.check(pluginId, PluginPermissions.MESSAGES_READ)) {
                PluginAuditJournal.record(pluginId, "getDatabase", "messages", "", true);
                return true;
            }
            if (PluginPermissions.isUnsafeMode()) {
                PluginAuditJournal.record(pluginId, "getDatabase", "messages", "", true);
                return true;
            }
            PluginAuditJournal.record(pluginId, "getDatabase", "messages", "", false);
            FileLog.w("PluginSinkGate: skipped getDatabase for plugin " + pluginId
                    + " — missing the '" + PluginPermissions.MESSAGES_READ + "' permission");
            return false;
        } finally {
            leaveCheck();
        }
    }

    /** id плагина, если проверять надо; null — приложение или мы уже внутри проверки. */
    private static String enterCheck() {
        if (Boolean.TRUE.equals(INSIDE.get())) {
            return null;
        }
        String pluginId = PluginRuntime.current();
        if (pluginId == null) {
            if (!PluginRuntime.isPythonActive()) {
                return null;
            }
            INSIDE.set(Boolean.TRUE);
            try {
                pluginId = PythonPluginsEngine.getInstance().pluginFromPythonStack();
            } catch (Throwable t) {
                pluginId = null;
            }
            if (pluginId == null) {
                INSIDE.remove();
                return null;
            }
            return pluginId;
        }
        INSIDE.set(Boolean.TRUE);
        return pluginId;
    }

    private static void leaveCheck() {
        INSIDE.remove();
    }

    private static int hookExact(Class<?> owner, String methodName, XC_MethodHook hook,
                                Class<?>... parameterTypes) {
        try {
            Member target = owner.getDeclaredMethod(methodName, parameterTypes);
            app.exteraless.plugins.xposed.HookGate.prewarm(target);
            XposedBridge.hookMethod(target, hook);
            return 1;
        } catch (Throwable t) {
            FileLog.e("PluginSinkGate: hook failed for " + owner.getName() + "." + methodName, t);
            return 0;
        }
    }

    private static int hookAll(Class<?> owner, String methodName, XC_MethodHook hook) {
        List<Member> targets = new ArrayList<>();
        try {
            for (Method method : owner.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    targets.add(method);
                }
            }
        } catch (Throwable t) {
            FileLog.e("PluginSinkGate: cannot enumerate " + owner.getName() + "." + methodName, t);
            return 0;
        }
        return hookMembers(targets, hook);
    }

    private static int hookMembers(List<Member> targets, XC_MethodHook hook) {
        int count = 0;
        for (Member target : targets) {
            try {
                app.exteraless.plugins.xposed.HookGate.prewarm(target);
                XposedBridge.hookMethod(target, hook);
                count++;
            } catch (Throwable t) {
                FileLog.e("PluginSinkGate: hook failed for " + target, t);
            }
        }
        return count;
    }

    /**
     * Отказ без исключения: метод просто не выполняется и возвращает пустое
     * значение своего типа.
     *
     * Для отправки сообщения и чтения базы это честнее исключения: плагин
     * зовёт их как обычные методы и обработчика для SecurityException не имеет,
     * так что упал бы весь его сценарий. Пустой результат он переживает — так
     * же, как неудачную отправку.
     */
    private static void denySilently(String pluginId, String event, String category, String detail,
                                     String reason, XC_MethodHook.MethodHookParam param) {
        if (PluginPermissions.isUnsafeMode()) {
            PluginAuditJournal.record(pluginId, event, category, detail, true);
            return;
        }
        PluginAuditJournal.record(pluginId, event, category, detail, false);
        FileLog.w("PluginSinkGate: skipped " + event + " for plugin " + pluginId + " — " + reason);
        param.setResult(emptyResultFor(param));
    }

    /** Пустое значение под тип возврата метода: у примитивов null уронил бы распаковку. */
    private static Object emptyResultFor(XC_MethodHook.MethodHookParam param) {
        return zeroValueFor(param.method instanceof Method ? (Method) param.method : null);
    }

    private static void deny(String pluginId, String event, String category, String detail,
                             String reason, XC_MethodHook.MethodHookParam param) {
        if (PluginPermissions.isUnsafeMode()) {
            PluginAuditJournal.record(pluginId, event, category, detail, true);
            return;
        }
        PluginAuditJournal.record(pluginId, event, category, detail, false, callerStack());
        FileLog.w("PluginSinkGate: denied " + event + " to plugin " + pluginId + " — " + reason);
        param.setThrowable(denialFor(event, category, pluginId, reason));
    }

    /**
     * Чем отвечать на отказ.
     *
     * Плагин умеет обрабатывать отказы сети и отсутствие класса — этих ошибок
     * он ждёт. SecurityException из середины openConnection он не ждёт, и
     * команда просто молча ничего не делала: со стороны неотличимо от поломки.
     * Поэтому сетевой отказ выглядит сетевым, отказ в резолве класса —
     * ClassNotFoundException (он и объявлен у forName/loadClass), и только
     * запуск процессов остаётся SecurityException: там нечего изображать.
     */
    private static String callerStack() {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int printed = 0;
        for (StackTraceElement element : stack) {
            String cls = element.getClassName();
            if (cls.startsWith("java.lang.Thread") || cls.startsWith("app.exteraless.plugins.PluginSinkGate")
                    || cls.startsWith("app.exteraless.plugins.PluginAuditJournal")) {
                continue;
            }
            sb.append(element).append('\n');
            if (++printed >= 24) {
                break;
            }
        }
        return sb.toString();
    }

    private static Throwable denialFor(String event, String category, String pluginId, String reason) {
        String message = "plugin '" + pluginId + "' cannot " + event + ": " + reason;
        if ("reflection".equals(category) || "loadClass".equals(event) || "forName".equals(event)) {
            return new ClassNotFoundException(message);
        }
        if ("network".equals(category)) {
            return new java.net.ConnectException(message);
        }
        return new SecurityException(message);
    }

    /** Загрузчики кода: требуют hooks, а не «никогда». */
    static boolean isCodeLoader(String className) {
        for (String loader : CODE_LOADING_CLASSES) {
            if (matchesClassName(className, loader)) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesClassName(String name, String pattern) {
        if (name == null || pattern == null) {
            return false;
        }
        return pattern.endsWith(".") ? name.startsWith(pattern) : name.equals(pattern);
    }

    private static String describe(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0 || param.args[0] == null) {
            return "";
        }
        Object first = param.args[0];
        if (first instanceof Object[]) {
            Object[] array = (Object[]) first;
            return array.length == 0 ? "" : String.valueOf(array[0]);
        }
        return String.valueOf(first);
    }
}
