package app.exteraless.plugins;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Реестр и фасад движка плагинов. Аналог PluginsController.java exteraGram (1842 строки),
 * упрощённо: без диалогов установки из чата, без pip, без автообновлений SDK.
 *
 * Обязанности:
 *  - сканирование filesDir/plugins, чтение метаданных (через движок, AST);
 *  - установка/удаление/включение/выключение/перезагрузка;
 *  - per-plugin хранилище настроек (JSON-значения в plugin_settings_<id>);
 *  - реестры хуков (send_message, pre/post request) и меню;
 *  - диспетчер событий приложения и хуков в PythonPluginsEngine;
 *  - watchdog + safe mode.
 */
public class PluginsController extends com.exteragram.messenger.plugins.PluginsController {

    private static volatile PluginsController instance;

    public static PluginsController getInstance() {
        if (instance == null) {
            synchronized (PluginsController.class) {
                if (instance == null) {
                    instance = new PluginsController();
                }
            }
        }
        return instance;
    }

    private Context appContext;
    private SharedPreferences preferences;
    private PluginsWatchdog watchdog;

    /**
     * ConcurrentHashMap, а не LinkedHashMap: карта отдаётся плагинам наружу
     * через шим {@code com.exteragram.messenger.plugins.PluginsController.plugins},
     * и они читают её из своих потоков параллельно с пересканированием.
     * Порядок вставки здесь не нужен — список плагинов сортируется по имени в UI.
     * У exteraGram это поле тоже ConcurrentHashMap.
     */
    public final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    /** pluginId -> приоритет. */
    private final Map<String, Integer> sendMessageHooks = new ConcurrentHashMap<>();
    /** requestName -> список pluginId (точное совпадение). */
    private final Map<String, List<String>> requestHooks = new ConcurrentHashMap<>();
    /** подстрока -> список pluginId. */
    private final Map<String, List<String>> requestHooksSubstring = new ConcurrentHashMap<>();
    /** updateName (TL_update*) -> список pluginId. */
    private final Map<String, List<String>> updateHooks = new ConcurrentHashMap<>();
    /** имя контейнера апдейтов (TL_updates и т.п.) -> список pluginId. */
    private final Map<String, List<String>> updatesContainerHooks = new ConcurrentHashMap<>();

    private final Map<String, Integer> hookPriorities = new ConcurrentHashMap<>();
    private final HookTargetCache sendTargetsCache = new HookTargetCache();
    private final HookTargetCache requestTargetsCache = new HookTargetCache();
    private final HookTargetCache updateTargetsCache = new HookTargetCache();
    private final HookTargetCache updatesTargetsCache = new HookTargetCache();

    private final List<MenuItemRecord> menuItems = Collections.synchronizedList(new ArrayList<>());
    /** Слушатель открытого экрана настроек плагина. */
    private final Map<String, List<Runnable>> settingsReloadListeners = new ConcurrentHashMap<>();

    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "plugins-io"));

    private volatile boolean initialized;

    private PluginsController() {
    }

    // ---------- init ----------

    /** Вызывается из ApplicationLoader.onCreate. Быстрый: тяжёлое уходит в фон. */
    public void init(Context context) {
        if (initialized) {
            return;
        }
        initialized = true;
        appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(PluginsConstants.PREFS_NAME, Context.MODE_PRIVATE);
        watchdog = new PluginsWatchdog(preferences);
        getPluginsDir().mkdirs();

        if (!isEngineEnabled()) {
            return;
        }
        String crashed = watchdog.recoverAfterCrash();
        if (crashed != null) {
            FileLog.e("PluginsController: disabled crashed plugin " + crashed);
        }
        // Планировщик детекта зависаний живёт столько же, сколько движок.
        watchdog.start();
        PythonPluginsEngine.getInstance().ensureStarted(appContext, ok -> {
            if (ok) {
                rescanAndLoadEnabled();
                executeOnAppEvent(PluginsConstants.EVENT_APP_START);
            }
        });
    }

    public PluginsWatchdog getWatchdog() {
        return watchdog;
    }

    public File getPluginsDir() {
        return new File(appContext.getFilesDir(), "plugins");
    }

    // ---------- флаги движка ----------

    /**
     * Хранилище настроек движка. Имя поля и геттера совпадают с exteraGram:
     * опубликованные плагины достают его рефлексией по имени
     * ({@code get_private_field(PluginsController.getInstance(), "preferences")}).
     */
    public SharedPreferences getPreferences() {
        return preferences;
    }

    public boolean isEngineEnabled() {
        return preferences != null && preferences.getBoolean(PluginsConstants.KEY_ENGINE_ENABLED, false);
    }

    public void setEngineEnabled(boolean enabled) {
        preferences.edit()
                .putBoolean(PluginsConstants.KEY_ENGINE_ENABLED, enabled)
                .remove(PluginsConstants.KEY_NATIVE_HOOKS_BROKEN)
                .apply();
        if (enabled && initialized) {
            PythonPluginsEngine.getInstance().ensureStarted(appContext, ok -> {
                if (ok) {
                    rescanAndLoadEnabled();
                }
            });
        } else if (!enabled) {
            unloadAll();
        }
    }

    public boolean isSafeMode() {
        return preferences != null && preferences.getBoolean(PluginsConstants.KEY_SAFE_MODE, false);
    }

    public void setSafeMode(boolean safeMode) {
        preferences.edit().putBoolean(PluginsConstants.KEY_SAFE_MODE, safeMode).apply();
    }

    public boolean isUnsafeMode() {
        if (unsafeMode == null) {
            unsafeMode = preferences != null
                    && preferences.getBoolean(PluginsConstants.KEY_UNSAFE_MODE, false);
        }
        return unsafeMode;
    }

    public void setUnsafeMode(boolean value) {
        unsafeMode = value;
        if (preferences != null) {
            preferences.edit().putBoolean(PluginsConstants.KEY_UNSAFE_MODE, value).apply();
        }
        FileLog.w("PluginsController: unsafe mode " + (value ? "ON" : "off"));
        PythonPluginsEngine.getInstance().setUnsafeMode(value);
    }

    private Boolean unsafeMode;

    public boolean isDeveloperMode() {
        return preferences != null && preferences.getBoolean(PluginsConstants.KEY_DEVELOPER_MODE, false);
    }

    public void setDeveloperMode(boolean developerMode) {
        preferences.edit().putBoolean(PluginsConstants.KEY_DEVELOPER_MODE, developerMode).apply();
    }

    // ---------- реестр плагинов ----------

    public boolean isCompactView() {
        return preferences != null && preferences.getBoolean(PluginsConstants.KEY_COMPACT_VIEW, false);
    }

    public void setCompactView(boolean value) {
        preferences.edit().putBoolean(PluginsConstants.KEY_COMPACT_VIEW, value).apply();
    }

    /**
     * Режим совместимости. ART Profile Saver со временем перекомпилирует методы
     * и сбивает установленные хуки; exteraGram лечит это вызовом
     * {@code XposedBridge.disableProfileSaver()} при инициализации движка.
     * Требует перезапуска — на живых хуках вызывать поздно.
     */
    public boolean isCompatibilityMode() {
        return preferences != null
                && preferences.getBoolean(PluginsConstants.KEY_COMPATIBILITY_MODE, false);
    }

    public void setCompatibilityMode(boolean value) {
        preferences.edit().putBoolean(PluginsConstants.KEY_COMPATIBILITY_MODE, value).apply();
    }

    /**
     * Закреплённые плагины идут в списке первыми.
     *
     * Ключ на плагин, а не общий список: плагины удаляются и ставятся заново, и
     * общий список пришлось бы чистить от исчезнувших id вручную.
     */
    public boolean isPluginPinned(String id) {
        return id != null && preferences != null
                && preferences.getBoolean("plugin_pinned_" + id, false);
    }

    public void setPluginPinned(String id, boolean pinned) {
        if (id == null || preferences == null) {
            return;
        }
        if (pinned) {
            preferences.edit().putBoolean("plugin_pinned_" + id, true).apply();
        } else {
            preferences.edit().remove("plugin_pinned_" + id).apply();
        }
    }

    private static boolean hasPluginExtension(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(PluginsConstants.PLUGIN_EXT)
                || lower.endsWith(PluginsConstants.PLUGIN_EXT_PY)
                || lower.endsWith(PluginsConstants.PLUGIN_EXT_ELYX)
                || lower.endsWith(PluginsConstants.PLUGIN_EXT_EAF);
    }

    public static boolean isPlugin(File file, MessageObject message) {
        return file != null && file.exists() && file.length() > 0
                && hasPluginExtension(file.getName());
    }

    public static boolean isPlugin(MessageObject message) {
        if (message == null || !hasPluginExtension(message.getDocumentName())) {
            return false;
        }
        try {
            return isPlugin(FileLoader.getInstance(UserConfig.selectedAccount)
                    .getPathToMessage(message.messageOwner), message);
        } catch (Throwable t) {
            return false;
        }
    }

    public synchronized List<Plugin> getPluginsSnapshot() {
        return new ArrayList<>(plugins.values());
    }

    /**
     * Живая карта id → плагин, как {@code getPlugins()} эталона: dex-модули берут
     * её рефлексией и зовут у результата {@code values()}.
     */
    @Override
    public Map<String, Plugin> getPlugins() {
        return plugins;
    }

    public Map<String, Plugin> getPluginsMap() {
        return plugins;
    }

    public synchronized Plugin getPlugin(String id) {
        return plugins.get(id);
    }

    /** Сканировать каталог и перечитать метаданные всех файлов. Нужен запущенный движок. */
    /**
     * Перечитать каталог плагинов с диска.
     *
     * Рантайм-состояние ({@code loaded}, {@code hasSettings}) живёт в Python и
     * этим сканом не восстанавливается, поэтому объекты уже загруженных
     * плагинов переиспользуются, а не создаются заново. Прежняя версия делала
     * {@code plugins.clear()} и клала свежие объекты с {@code loaded = false} —
     * из-за чего каждый заход на экран «Плагины» (updateRows зовёт rescan)
     * гасил кнопку настроек у работающего плагина, будто он упал.
     */
    /**
     * Установленный плагин обязан лежать как {@code <id>.py}: под этим именем
     * его ищут плагины каталога (zwylib читает исходник соседа по такому пути).
     * {@code .plugin} — расширение для передачи файла, а не для хранения.
     */
    private File normalizeInstalledName(File file) {
        String name = file.getName();
        if (!name.endsWith(PluginsConstants.PLUGIN_EXT)) {
            return file;
        }
        File renamed = new File(file.getParentFile(),
                name.substring(0, name.length() - PluginsConstants.PLUGIN_EXT.length())
                        + PluginsConstants.PLUGIN_EXT_PY);
        if (renamed.exists() || !file.renameTo(renamed)) {
            return file;
        }
        return renamed;
    }

    public synchronized void rescanPlugins() {
        if (!PythonPluginsEngine.getInstance().isStarted()) {
            return;
        }
        File[] files = getPluginsDir().listFiles();
        if (files == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            f = normalizeInstalledName(f);
            String name = f.getName();
            if (!name.endsWith(PluginsConstants.PLUGIN_EXT_PY) && !name.endsWith(PluginsConstants.PLUGIN_EXT)
                    && !name.endsWith(PluginsConstants.PLUGIN_EXT_ELYX) && !name.endsWith(PluginsConstants.PLUGIN_EXT_EAF)) {
                continue;
            }
            Plugin fresh = readPluginMetadata(f);
            if (fresh == null || fresh.id == null) {
                continue;
            }
            seen.add(fresh.id);
            boolean enabled = preferences.getBoolean(
                    PluginsConstants.KEY_PLUGIN_ENABLED_PREFIX + fresh.id, true);
            Plugin existing = plugins.get(fresh.id);
            if (existing != null && existing.loaded && f.getAbsolutePath().equals(existing.path)) {
                // Тот же файл, плагин исполняется — обновляем метаданные,
                // рантайм-состояние сохраняем.
                existing.name = fresh.name;
                existing.description = fresh.description;
                existing.author = fresh.author;
                existing.version = fresh.version;
                existing.icon = fresh.icon;
                existing.appVersion = fresh.appVersion;
                existing.sdkVersion = fresh.sdkVersion;
                existing.beta = fresh.beta;
                existing.requirements = fresh.requirements;
                existing.permissions = fresh.permissions;
                existing.permissionsDeclared = fresh.permissionsDeclared;
                existing.loadError = fresh.loadError;
                existing.enabled = enabled;
                continue;
            }
            fresh.enabled = enabled;
            plugins.put(fresh.id, fresh);
        }
        // Файлы, которых больше нет: выгрузить и забыть.
        for (Iterator<Map.Entry<String, Plugin>> it = plugins.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Plugin> entry = it.next();
            if (seen.contains(entry.getKey())) {
                continue;
            }
            Plugin gone = entry.getValue();
            if (gone.loaded) {
                unregisterPluginHooks(gone.id);
                PythonPluginsEngine.getInstance().unloadPlugin(gone);
            }
            it.remove();
        }
    }

    private Plugin readPluginMetadata(File f) {
        String json = PythonPluginsEngine.getInstance().readMetadataJson(f.getAbsolutePath());
        if (json == null) {
            Plugin p = new Plugin();
            p.id = f.getName();
            p.name = f.getName();
            p.path = f.getAbsolutePath();
            p.loadError = "engine not ready";
            return p;
        }
        try {
            JSONObject root = new JSONObject(json);
            Plugin p = new Plugin();
            p.path = f.getAbsolutePath();
            if (!root.optBoolean("ok")) {
                p.id = f.getName();
                p.name = f.getName();
                p.loadError = root.optString("error", "unknown error");
                p.loadDebug = root.optString("debug", null);
                return p;
            }
            JSONObject meta = root.getJSONObject("meta");
            p.id = meta.optString("id");
            p.name = meta.optString("name");
            p.description = JsonUtils.optStringOrNull(meta, "description");
            p.author = JsonUtils.optStringOrNull(meta, "author");
            p.version = meta.optString("version", "1.0");
            p.icon = JsonUtils.optStringOrNull(meta, "icon");
            p.appVersion = JsonUtils.optStringOrNull(meta, "app_version");
            p.sdkVersion = JsonUtils.optStringOrNull(meta, "sdk_version");
            p.beta = meta.optBoolean("beta", false);
            p.requirements = new ArrayList<>();
            JSONArray reqs = meta.optJSONArray("requirements");
            if (reqs != null) {
                for (int i = 0; i < reqs.length(); i++) {
                    p.requirements.add(reqs.optString(i));
                }
            }
            // __permissions__: ключи уже проверены AST-парсером, неизвестный ключ
            // приезжает как ok=false выше. permissions_declared отличает
            // «объявил пусто» от «не объявлял» — от этого зависит режим
            // совместимости в PluginPermissions.getEffective.
            p.permissions = new ArrayList<>();
            p.permissionsDeclared = meta.optBoolean("permissions_declared", false);
            JSONArray perms = meta.optJSONArray("permissions");
            if (perms != null) {
                for (int i = 0; i < perms.length(); i++) {
                    String key = perms.optString(i);
                    if (PluginPermissions.isKnown(key) && !p.permissions.contains(key)) {
                        p.permissions.add(key);
                    }
                }
            }
            // Elyx requires: {"dep_id": {"min_version": ..., "url": ...}}
            p.requires = new java.util.LinkedHashMap<>();
            JSONObject requires = meta.optJSONObject("requires");
            if (requires != null) {
                Iterator<String> keys = requires.keys();
                while (keys.hasNext()) {
                    String depId = keys.next();
                    JSONObject dep = requires.optJSONObject(depId);
                    p.requires.put(depId, new String[]{
                            dep == null ? null : JsonUtils.optStringOrNull(dep, "min_version"),
                            dep == null ? null : JsonUtils.optStringOrNull(dep, "url"),
                    });
                }
            }
            String constraintError = checkVersionConstraints(p);
            if (constraintError != null) {
                p.loadError = constraintError;
            }
            return p;
        } catch (Exception e) {
            FileLog.e("PluginsController: bad metadata json for " + f.getName(), e);
            Plugin p = new Plugin();
            p.id = f.getName();
            p.name = f.getName();
            p.path = f.getAbsolutePath();
            p.loadError = "metadata parse error";
            return p;
        }
    }

    /** Проверка __app_version__/__sdk_version__ (операторы >=, <=, ==, >, <; без оператора — >=). */
    private String checkVersionConstraints(Plugin p) {
        if (p.appVersion != null && !checkVersionConstraint(p.appVersion, BuildVars.BUILD_VERSION_STRING)) {
            return "requires app " + p.appVersion;
        }
        if (p.sdkVersion != null && !checkVersionConstraint(p.sdkVersion, PluginsConstants.SDK_VERSION)) {
            return "requires sdk " + p.sdkVersion;
        }
        return null;
    }

    public static boolean checkVersionConstraint(String constraint, String current) {
        if (constraint == null || constraint.isEmpty()) {
            return true;
        }
        String op = ">=";
        String version = constraint.trim();
        // Ограничение без единой цифры — это не версия: пустая строка, "null",
        // мусор из метаданных. Считать такое невыполненным нельзя, иначе плагин
        // отвергается на ровном месте.
        boolean hasDigit = false;
        for (int i = 0; i < version.length(); i++) {
            if (Character.isDigit(version.charAt(i))) {
                hasDigit = true;
                break;
            }
        }
        if (!hasDigit) {
            return true;
        }
        for (String candidate : new String[]{">=", "<=", "==", ">", "<"}) {
            if (version.startsWith(candidate)) {
                op = candidate;
                version = version.substring(candidate.length()).trim();
                break;
            }
        }
        int cmp = compareVersions(current, version);
        switch (op) {
            case ">=": return cmp >= 0;
            case "<=": return cmp <= 0;
            case ">": return cmp > 0;
            case "<": return cmp < 0;
            default: return cmp == 0;
        }
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.-]");
        String[] pb = b.split("[.-]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int va = i < pa.length ? parseVersionPart(pa[i]) : 0;
            int vb = i < pb.length ? parseVersionPart(pb[i]) : 0;
            if (va != vb) {
                return va < vb ? -1 : 1;
            }
        }
        return 0;
    }

    private static int parseVersionPart(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------- загрузка/выгрузка ----------

    /** Пересканировать каталог и загрузить все включённые плагины (safe mode — ничего). */
    public void rescanAndLoadEnabled() {
        rescanPlugins();
        if (isSafeMode()) {
            FileLog.d("PluginsController: safe mode, no plugins loaded");
            return;
        }
        List<Plugin> snapshot = getPluginsSnapshot();
        for (Plugin p : snapshot) {
            if (p.enabled && p.loadError == null) {
                loadPluginInternal(p);
            }
        }
    }

    /**
     * Elyx {@code requires}: требуемый плагин должен быть установлен, включён и
     * не старше объявленной версии. Проверяем перед загрузкой — иначе плагин
     * падает уже внутри своего кода, и причина теряется.
     */
    private String checkRequiredPlugins(Plugin p) {
        if (p.requires == null || p.requires.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String[]> entry : p.requires.entrySet()) {
            String depId = entry.getKey();
            String minVersion = entry.getValue() == null ? null : entry.getValue()[0];
            Plugin dep = getPlugin(depId);
            if (dep == null) {
                return "requires plugin " + depId + " (not installed)";
            }
            if (!dep.enabled) {
                return "requires plugin " + depId + " (disabled)";
            }
            if (minVersion != null && !checkVersionConstraint(">=" + minVersion, dep.version)) {
                return "requires plugin " + depId + " >= " + minVersion
                        + " (installed " + dep.version + ")";
            }
        }
        return null;
    }

    private boolean loadPluginInternal(Plugin p) {
        String missingDependency = checkRequiredPlugins(p);
        if (missingDependency != null) {
            p.loaded = false;
            p.loadError = missingDependency;
            return false;
        }
        String result = PythonPluginsEngine.getInstance().loadPlugin(p);
        try {
            JSONObject root = new JSONObject(result);
            if (root.optBoolean("ok")) {
                p.loaded = true;
                p.loadError = null;
                p.loadDebug = null;
                p.hasSettings = root.optBoolean("has_settings", false);
                notifyPluginSettings(p.id, p.hasSettings);
                return true;
            }
            p.loaded = false;
            p.loadError = root.optString("error", "load failed");
            p.loadDebug = root.optString("debug", null);
            return false;
        } catch (Exception e) {
            p.loaded = false;
            p.loadError = "load failed";
            return false;
        }
    }

    private void unloadAll() {
        List<Plugin> snapshot = getPluginsSnapshot();
        for (Plugin p : snapshot) {
            if (p.loaded) {
                unregisterPluginHooks(p.id);
                PythonPluginsEngine.getInstance().unloadPlugin(p);
            }
        }
        sendMessageHooks.clear();
        requestHooks.clear();
        requestHooksSubstring.clear();
        updateHooks.clear();
        updatesContainerHooks.clear();
        hookPriorities.clear();
        invalidateHookTargets();
        synchronized (menuItems) {
            menuItems.clear();
        }
    }

    public boolean setPluginEnabled(String id, boolean enabled) {
        Plugin p = getPlugin(id);
        if (p == null) {
            return false;
        }
        preferences.edit().putBoolean(PluginsConstants.KEY_PLUGIN_ENABLED_PREFIX + id, enabled).apply();
        p.enabled = enabled;
        if (!PythonPluginsEngine.getInstance().isStarted()) {
            return true;
        }
        if (enabled && !p.loaded && !isSafeMode()) {
            return loadPluginInternal(p);
        } else if (!enabled && p.loaded) {
            unregisterPluginHooks(id);
            PythonPluginsEngine.getInstance().unloadPlugin(p);
        }
        return true;
    }

    public void setPluginEnabled(String id, boolean enabled, Utilities.Callback<String> callback) {
        boolean ok = setPluginEnabled(id, enabled);
        if (callback != null) {
            AndroidUtilities.runOnUIThread(() -> callback.run(ok ? null : id));
        }
    }

    public void reloadPlugin(String id) {
        Plugin p = getPlugin(id);
        if (p == null || !PythonPluginsEngine.getInstance().isStarted()) {
            return;
        }
        if (p.loaded) {
            unregisterPluginHooks(id);
            PythonPluginsEngine.getInstance().unloadPlugin(p);
        }
        Plugin fresh = readPluginMetadata(new File(p.path));
        if (fresh != null && fresh.loadError == null) {
            p.name = fresh.name;
            p.description = fresh.description;
            p.author = fresh.author;
            p.version = fresh.version;
            p.icon = fresh.icon;
            p.requirements = fresh.requirements;
            p.permissions = fresh.permissions;
            p.permissionsDeclared = fresh.permissionsDeclared;
            p.loadError = null;
            loadPluginInternal(p);
        } else if (fresh != null) {
            p.loadError = fresh.loadError;
        }
    }

    // ---------- установка/удаление ----------

    public interface InstallCallback {
        void onResult(boolean ok, String error, Plugin plugin);
    }

    private boolean awaitEngineStarted() {
        PythonPluginsEngine engine = PythonPluginsEngine.getInstance();
        if (engine.isStarted()) {
            return true;
        }
        final Object lock = new Object();
        final boolean[] started = {false};
        final boolean[] done = {false};
        engine.ensureStarted(appContext, ok -> {
            synchronized (lock) {
                started[0] = ok;
                done[0] = true;
                lock.notify();
            }
        });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 30_000;
            while (!done[0]) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    break;
                }
                try {
                    lock.wait(wait);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return started[0];
    }

    /**
     * Прочитать метаданные файла, не устанавливая его. Разбор идёт AST-парсером,
     * без выполнения кода плагина — можно показывать пользователю до согласия.
     * Колбэк зовётся в потоке файловых операций.
     */
    public void readMetadataAsync(File source, org.telegram.messenger.Utilities.Callback<Plugin> callback) {
        fileExecutor.execute(() -> {
            Plugin plugin = null;
            try {
                if (awaitEngineStarted()) {
                    plugin = readPluginMetadata(source);
                }
            } catch (Throwable t) {
                FileLog.e("PluginsController: metadata preview failed", t);
            }
            if (callback != null) {
                callback.run(plugin);
            }
        });
    }

    /**
     * Установить плагин из .py/.plugin-файла. Асинхронно, колбэк на UI-потоке.
     * Валидация метаданных — до копирования; при совпадении id — перезапись.
     */
    public void installPlugin(File source, InstallCallback callback) {
        installPlugin(source, true, callback);
    }

    public void installPlugin(File source, boolean enable, InstallCallback callback) {
        fileExecutor.execute(() -> {
            PythonPluginsEngine engine = PythonPluginsEngine.getInstance();
            if (!awaitEngineStarted()) {
                deliver(callback, false, "Python engine failed to start", null);
                return;
            }
            String json = engine.readMetadataJson(source.getAbsolutePath());
            if (json == null) {
                deliver(callback, false, "failed to read metadata", null);
                return;
            }
            try {
                JSONObject root = new JSONObject(json);
                if (!root.optBoolean("ok")) {
                    deliver(callback, false, root.optString("error", "invalid plugin"), null);
                    return;
                }
                String id = root.getJSONObject("meta").optString("id");
                // Сохраняем исходное расширение: .elyx/.eaf — ZIP-архивы, их нельзя
                // переименовывать в .py.
                String srcName = source.getName().toLowerCase();
                String ext = PluginsConstants.PLUGIN_EXT_PY;
                if (srcName.endsWith(PluginsConstants.PLUGIN_EXT_ELYX)) {
                    ext = PluginsConstants.PLUGIN_EXT_ELYX;
                } else if (srcName.endsWith(PluginsConstants.PLUGIN_EXT_EAF)) {
                    ext = PluginsConstants.PLUGIN_EXT_EAF;
                }
                File dest = new File(getPluginsDir(), id + ext);
                copyFile(source, dest);

                Plugin existing = getPlugin(id);
                if (existing != null && existing.loaded) {
                    unregisterPluginHooks(id);
                    engine.unloadPlugin(existing);
                }
                Plugin p = readPluginMetadata(dest);
                if (p == null) {
                    deliver(callback, false, "metadata parse error", null);
                    return;
                }
                p.enabled = enable;
                preferences.edit().putBoolean(PluginsConstants.KEY_PLUGIN_ENABLED_PREFIX + id, enable).apply();
                // Согласие пользователя записывает диалог установки (PluginPermissions.setGranted).
                // Если он этого не сделал, запись всё равно должна появиться: без неё
                // свежепоставленный плагин уедет в режим совместимости, где ему дают всё.
                // Объявленное считаем выданным, необъявленное — пустым набором.
                if (!PluginPermissions.hasRecord(id)) {
                    PluginPermissions.setGranted(id,
                            p.permissionsDeclared ? p.permissions : new ArrayList<>());
                }
                synchronized (this) {
                    plugins.put(id, p);
                }
                if (enable && !isSafeMode()) {
                    loadPluginInternal(p);
                }
                if (p.loadError != null) {
                    // Иначе упавший плагин остаётся в списке установленных: запись о нём
                    // появляется до загрузки, а загрузка только проставляет loadError.
                    final String error = p.loadError;
                    uninstallPlugin(id);
                    deliver(callback, false, error, null);
                    return;
                }
                deliver(callback, true, null, p);
            } catch (Exception e) {
                FileLog.e("PluginsController: install failed", e);
                deliver(callback, false, e.getMessage(), null);
            }
        });
    }

    public boolean uninstallPlugin(String id) {
        Plugin p = getPlugin(id);
        if (p == null) {
            return false;
        }
        if (p.loaded) {
            unregisterPluginHooks(id);
            PythonPluginsEngine.getInstance().unloadPlugin(p);
        }
        // pip-зависимости (refcount) и elyx-экстракции чистятся на Python-стороне.
        PythonPluginsEngine.getInstance().uninstallPlugin(id);
        synchronized (this) {
            plugins.remove(id);
        }
        preferences.edit().remove(PluginsConstants.KEY_PLUGIN_ENABLED_PREFIX + id).apply();
        // Иначе переустановка того же id молча унаследует старое согласие.
        PluginPermissions.clear(id);
        PluginTrustLevel.clear(id);
        PluginCapabilityScan.clear(id);
        setPluginPinned(id, false);
        PythonPluginsEngine.getInstance().forgetAudit(id);
        File f = new File(p.path);
        // SharedPreferences plugin_settings_<id> — отдельный файл, удаляем напрямую.
        File prefsFile = new File(appContext.getFilesDir().getParentFile(),
                "shared_prefs/" + PluginsConstants.SETTINGS_PREFS_PREFIX + id + ".xml");
        if (prefsFile.exists()) {
            prefsFile.delete();
        }
        return f.delete();
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void deliver(InstallCallback callback, boolean ok, String error, Plugin plugin) {
        if (callback == null) {
            return;
        }
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                callback.onResult(ok, error, plugin));
    }

    // ---------- настройки плагинов (хранилище, зовётся из PythonBridge) ----------

    private SharedPreferences pluginPrefs(String pluginId) {
        return appContext.getSharedPreferences(
                PluginsConstants.SETTINGS_PREFS_PREFIX + pluginId, Context.MODE_PRIVATE);
    }

    public String getPluginSettingJson(String pluginId, String key) {
        if (appContext == null) {
            return null;
        }
        return pluginPrefs(pluginId).getString(key, null);
    }

    public void setPluginSettingJson(String pluginId, String key, String jsonValue, boolean reloadSettings) {
        if (appContext == null) {
            return;
        }
        pluginPrefs(pluginId).edit().putString(key, jsonValue).apply();
        if (reloadSettings) {
            reloadSettingsScreen(pluginId);
        }
    }

    /** Есть ли у плагина сохранённые настройки (кнопка сброса показывается только тогда). */
    private static void notifyPluginSettings(String pluginId, boolean hasSettings) {
        if (pluginId == null || pluginId.isEmpty()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(
                hasSettings ? NotificationCenter.pluginSettingsRegistered
                        : NotificationCenter.pluginSettingsUnregistered, pluginId));
    }

    public boolean hasPluginSettingsPreferences(String pluginId) {
        return appContext != null && pluginId != null && !pluginPrefs(pluginId).getAll().isEmpty();
    }

    /** Сброс настроек плагина: значения стираются, открытый экран пересобирается. */
    public void clearPluginSettingsPreferences(String pluginId) {
        if (appContext == null || pluginId == null) {
            return;
        }
        pluginPrefs(pluginId).edit().clear().apply();
        reloadSettingsScreen(pluginId);
    }

    public String exportPluginSettings(String pluginId) {
        if (appContext == null) {
            return "{}";
        }
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, ?> e : pluginPrefs(pluginId).getAll().entrySet()) {
            try {
                obj.put(e.getKey(), e.getValue());
            } catch (Exception ignored) {
            }
        }
        return obj.toString();
    }

    /** Переписывает настройки плагина целиком: ключи, которых нет в json, исчезают. */
    public void replacePluginSettings(String pluginId, String json) {
        if (appContext == null) {
            return;
        }
        try {
            JSONObject obj = new JSONObject(json);
            SharedPreferences.Editor editor = pluginPrefs(pluginId).edit();
            editor.clear();
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                editor.putString(key, obj.optString(key));
            }
            editor.apply();
        } catch (Exception e) {
            FileLog.e("PluginsController: replaceSettings failed for " + pluginId, e);
        }
    }

    public void addXposedHook(String pluginId, de.robv.android.xposed.XC_MethodHook.Unhook unhook) {
        app.exteraless.plugins.xposed.XposedHooks.addPluginUnhook(pluginId, unhook);
    }

    public void removeXposedHook(String pluginId, de.robv.android.xposed.XC_MethodHook.Unhook unhook) {
        app.exteraless.plugins.xposed.XposedHooks.removePluginUnhook(pluginId, unhook);
    }

    /** Имя из SDK exteraGram; у нас перерисовка экрана и есть перезагрузка настроек. */
    public void loadPluginSettings(String pluginId) {
        reloadSettingsScreen(pluginId);
    }

    /**
     * Движки по имени языка. У нас он один, но плагины каталога берут его
     * через карту: PluginUtils.get_python_engine() в zwylib.
     */
    public static Map<String, PythonPluginsEngine> getEngines() {
        Map<String, PythonPluginsEngine> engines = new ConcurrentHashMap<>();
        engines.put(PluginsConstants.PYTHON, PythonPluginsEngine.getInstance());
        return engines;
    }

    public static void openPluginSettings(String pluginId) {
        openPluginSettings(pluginId, null);
    }

    public static void openPluginSettings(String pluginId, String targetSetting) {
        if (pluginId == null || pluginId.isEmpty()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            Plugin plugin = getInstance().getPlugin(pluginId);
            if (plugin == null) {
                return;
            }
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment == null) {
                return;
            }
            PythonPluginsEngine.getInstance().openPluginSettings(plugin, fragment, targetSetting);
        });
    }

    public void importPluginSettings(String pluginId, String json, boolean reloadSettings) {
        if (appContext == null) {
            return;
        }
        try {
            JSONObject obj = new JSONObject(json);
            SharedPreferences.Editor editor = pluginPrefs(pluginId).edit();
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                editor.putString(key, obj.optString(key));
            }
            editor.apply();
            if (reloadSettings) {
                reloadSettingsScreen(pluginId);
            }
        } catch (Exception e) {
            FileLog.e("PluginsController: importSettings failed for " + pluginId, e);
        }
    }

    // ---------- экран настроек плагина (UI <- JSON) ----------

    /** @return JSON-список элементов ui.settings с текущими значениями или null. */
    public String getPluginSettingsJson(String pluginId) {
        return PythonPluginsEngine.getInstance().getSettingsJson(pluginId);
    }

    // ---------- имена API exteraGram ----------

    public void init() {
        init(isSafeMode(), null);
    }

    public void init(Runnable onDone) {
        init(isSafeMode(), onDone);
    }

    public void init(boolean startWithSafeMode) {
        init(startWithSafeMode, null);
    }

    public void init(boolean startWithSafeMode, Runnable onDone) {
        setSafeMode(startWithSafeMode);
        if (appContext == null && ApplicationLoader.applicationContext != null) {
            init(ApplicationLoader.applicationContext);
        }
        if (onDone != null) {
            AndroidUtilities.runOnUIThread(onDone);
        }
    }

    public boolean getInitialized() {
        return initialized;
    }

    public void restart() {
        restart(isSafeMode());
    }

    public void restart(boolean startWithSafeMode) {
        setSafeMode(startWithSafeMode);
        unloadAll();
        if (!isEngineEnabled() || startWithSafeMode) {
            return;
        }
        PythonPluginsEngine.getInstance().ensureStarted(appContext, ok -> {
            if (ok) {
                rescanAndLoadEnabled();
            }
        });
    }

    public void shutdown() {
        shutdown(null);
    }

    public void shutdown(Runnable onDone) {
        unloadAll();
        if (onDone != null) {
            AndroidUtilities.runOnUIThread(onDone);
        }
    }

    public void runOnPluginsQueue(Runnable runnable) {
        if (runnable != null) {
            fileExecutor.execute(runnable);
        }
    }

    public String getPluginPath(String id) {
        Plugin plugin = getPlugin(id);
        return plugin == null ? null : plugin.path;
    }

    public PythonPluginsEngine getPluginEngine(String pluginId) {
        return PythonPluginsEngine.getInstance();
    }

    public PythonPluginsEngine getPluginEngine(File file) {
        return PythonPluginsEngine.getInstance();
    }

    public boolean isPluginEngineAvailable() {
        return PythonPluginsEngine.getInstance().isStarted();
    }

    public boolean isPluginEngineSupported() {
        return true;
    }

    public void notifyPluginsChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.pluginsUpdated));
    }

    public void deletePlugin(String pluginId, Utilities.Callback<String> callback) {
        boolean removed = uninstallPlugin(pluginId);
        if (callback != null) {
            AndroidUtilities.runOnUIThread(() -> callback.run(removed ? null : pluginId));
        }
    }

    public void loadPluginSettings() {
        for (Plugin plugin : getPluginsSnapshot()) {
            loadPluginSettings(plugin.id);
        }
    }

    public void invalidatePluginSettings(String pluginId) {
        reloadSettingsScreen(pluginId);
    }

    public boolean hasPluginSettings(String pluginId) {
        String json = getPluginSettingsJson(pluginId);
        return json != null && !json.isEmpty() && !"[]".equals(json.trim());
    }

    public Map<String, ?> getPluginSettingsPreferences(String pluginId) {
        if (appContext == null || pluginId == null) {
            return Collections.emptyMap();
        }
        return pluginPrefs(pluginId).getAll();
    }

    public void clearPluginSettingsPreferences(String pluginId, boolean reloadSettings) {
        if (appContext == null || pluginId == null) {
            return;
        }
        pluginPrefs(pluginId).edit().clear().apply();
        if (reloadSettings) {
            reloadSettingsScreen(pluginId);
        }
    }

    public List<Object> getPluginSettingsList(String pluginId) {
        List<Object> items = new ArrayList<>();
        String json = getPluginSettingsJson(pluginId);
        if (json == null || json.isEmpty()) {
            return items;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                items.add(fromJson(array.opt(i)));
            }
        } catch (Exception e) {
            FileLog.e("PluginsController: settings list failed for " + pluginId, e);
        }
        return items;
    }

    public Map<String, List<Object>> getSettings() {
        Map<String, List<Object>> all = new ConcurrentHashMap<>();
        for (Plugin plugin : getPluginsSnapshot()) {
            all.put(plugin.id, getPluginSettingsList(plugin.id));
        }
        return all;
    }

    public boolean getPluginSettingBoolean(String pluginId, String key, boolean defaultValue) {
        Object value = readSetting(pluginId, key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return defaultValue;
    }

    public int getPluginSettingInt(String pluginId, String key, int defaultValue) {
        Object value = readSetting(pluginId, key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1 : 0;
        }
        return defaultValue;
    }

    public String getPluginSettingString(String pluginId, String key, String defaultValue) {
        Object value = readSetting(pluginId, key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public void setPluginSetting(String pluginId, String key, Object value) {
        setPluginSettingJson(pluginId, key, toJson(value), true);
    }

    public void setPluginSettingAndTriggerOnChange(String pluginId, String key, Object value,
                                                   com.chaquo.python.PyObject onChangeCallback) {
        String json = toJson(value);
        setPluginSettingJson(pluginId, key, json, true);
        notifySettingChanged(pluginId, key, json);
        if (onChangeCallback != null) {
            try {
                onChangeCallback.call(value);
            } catch (Throwable t) {
                FileLog.e("PluginsController: on_change failed for " + pluginId + "/" + key, t);
            }
        }
    }

    private Object readSetting(String pluginId, String key) {
        String raw = getPluginSettingJson(pluginId, key);
        if (raw == null) {
            return null;
        }
        try {
            return fromJson(new JSONArray("[" + raw + "]").opt(0));
        } catch (Exception e) {
            return raw;
        }
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        return JSONObject.quote(String.valueOf(value));
    }

    private static Object fromJson(Object value) {
        if (value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Map<String, Object> map = new HashMap<>();
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, fromJson(object.opt(key)));
            }
            return map;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                list.add(fromJson(array.opt(i)));
            }
            return list;
        }
        return value;
    }

    public Object getPluginSettingsCustomContent(String pluginId, String viewId,
                                                  android.content.Context context) {
        return PythonPluginsEngine.getInstance().getSettingsCustomContent(pluginId, viewId, context);
    }

    public android.view.View getPluginSettingsCustomView(String pluginId, String viewId,
                                                         android.content.Context context) {
        return PythonPluginsEngine.getInstance().getSettingsCustomView(pluginId, viewId, context);
    }

    /** Из UI: пользователь изменил значение. */
    public void notifySettingChanged(String pluginId, String key, String jsonValue) {
        PythonPluginsEngine.getInstance().notifySettingChanged(pluginId, key, jsonValue);
    }

    /** Из UI: пользователь нажал элемент с on_click. Вьюха строки уезжает в плагин:
     * exteraGram зовёт колбэк как {@code callback.call(view)}, и плагины на это
     * рассчитывают — привязывают к ней меню и всплывашки. */
    public void dispatchSettingClick(String pluginId, String callbackId, android.view.View view) {
        PythonPluginsEngine.getInstance().dispatchSettingClick(pluginId, callbackId, view);
    }

    /**
     * Слушателей на плагин может быть несколько: подстраницы настроек живут
     * отдельными фрагментами поверх корневого, и перестроиться должен каждый
     * открытый — иначе экран под пальцем остаётся со старым списком.
     */
    public void addSettingsReloadListener(String pluginId, Runnable listener) {
        if (pluginId == null || listener == null) {
            return;
        }
        settingsReloadListeners
                .computeIfAbsent(pluginId, id -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    public void removeSettingsReloadListener(String pluginId, Runnable listener) {
        if (pluginId == null || listener == null) {
            return;
        }
        List<Runnable> list = settingsReloadListeners.get(pluginId);
        if (list == null) {
            return;
        }
        list.remove(listener);
        if (list.isEmpty()) {
            settingsReloadListeners.remove(pluginId);
        }
    }

    public void reloadSettingsScreen(String pluginId) {
        List<Runnable> list = settingsReloadListeners.get(pluginId);
        if (list == null) {
            return;
        }
        for (Runnable r : list) {
            org.telegram.messenger.AndroidUtilities.runOnUIThread(r);
        }
    }

    // ---------- реестры хуков (зовётся из PythonBridge) ----------

    public void registerSendMessageHook(String pluginId, int priority) {
        // PLUGINS-SECURITY.md, «Точки проверки»: хук исходящих даёт и чтение текста,
        // и отмену отправки. Отказ — молча не регистрируем, плагин продолжает жить.
        if (!PluginPermissions.check(pluginId, PluginPermissions.MESSAGES_SEND,
                "send-message hook")) {
            return;
        }
        sendMessageHooks.put(pluginId, priority);
        sendTargetsCache.invalidate();
    }

    public void registerRequestHook(String pluginId, String requestName, boolean matchSubstring, int priority) {
        if (pluginId == null || requestName == null || requestName.isEmpty()) {
            return;
        }
        // PLUGINS-SECURITY.md: update/updates/post-request хуки требуют messages.read.
        // Pre- и post-request живут в одном реестре (findRequestHookTargets), разделить
        // их на регистрации нечем — поэтому гейт стоит на всей регистрации.
        if (!PluginPermissions.check(pluginId, PluginPermissions.MESSAGES_READ,
                "request hook " + requestName)) {
            return;
        }
        // Маршрутизация по имени: TL_updates* — контейнеры апдейтов, TL_update* —
        // одиночные апдейты, остальное — TL-запросы (pre/post request hook).
        Map<String, List<String>> target;
        if (matchSubstring) {
            target = requestHooksSubstring;
        } else if (requestName.startsWith("TL_updates") || requestName.startsWith("TL_updateShort")) {
            target = updatesContainerHooks;
        } else if (requestName.startsWith("TL_update")) {
            target = updateHooks;
        } else {
            target = requestHooks;
        }
        synchronized (target) {
            List<String> list = target.computeIfAbsent(requestName, k -> new ArrayList<>());
            if (!list.contains(pluginId)) {
                list.add(pluginId);
            }
        }
        hookPriorities.merge(priorityKey(requestName, pluginId), priority, Math::max);
        invalidateHookTargets();
    }

    private static String priorityKey(String hookName, String pluginId) {
        return hookName + '\u0001' + pluginId;
    }

    private int hookPriority(String hookName, String pluginId) {
        Integer stored = hookPriorities.get(priorityKey(hookName, pluginId));
        return stored != null ? stored : 0;
    }

    private static List<String> byPriority(Map<String, Integer> priorities) {
        List<String> ordered = new ArrayList<>(priorities.keySet());
        ordered.sort(Comparator
                .comparingInt((String id) -> priorities.get(id)).reversed()
                .thenComparing(Comparator.<String>naturalOrder()));
        return ordered;
    }



    /** Снять один request-хук плагина (SDK: {@code remove_hook(name)}). */
    public void unregisterRequestHook(String pluginId, String requestName) {
        if (pluginId == null || requestName == null) {
            return;
        }
        synchronized (requestHooks) {
            dropPluginFromKey(requestHooks, requestName, pluginId);
        }
        synchronized (requestHooksSubstring) {
            dropPluginFromKey(requestHooksSubstring, requestName, pluginId);
        }
        synchronized (updateHooks) {
            dropPluginFromKey(updateHooks, requestName, pluginId);
        }
        synchronized (updatesContainerHooks) {
            dropPluginFromKey(updatesContainerHooks, requestName, pluginId);
        }
        hookPriorities.remove(priorityKey(requestName, pluginId));
        invalidateHookTargets();
    }

    /** Снять хук исходящих сообщений (SDK: {@code remove_hook("on_send_message_hook")}). */
    public void unregisterSendMessageHook(String pluginId) {
        if (pluginId != null) {
            sendMessageHooks.remove(pluginId);
            sendTargetsCache.invalidate();
        }
    }

    private static void dropPluginFromKey(Map<String, List<String>> hooks, String key,
                                          String pluginId) {
        List<String> list = hooks.get(key);
        if (list == null) {
            return;
        }
        list.remove(pluginId);
        if (list.isEmpty()) {
            hooks.remove(key);
        }
    }

    /**
     * Опустевший ключ удаляется вместе со списком: иначе hasAnyRequestHooks() и
     * hasAnyUpdateHooks() остаются true навсегда, и каждый запрос платит за поиск
     * по карте, в которой уже никого нет.
     */
    private static void dropPluginFrom(Map<String, List<String>> hooks, String pluginId) {
        hooks.values().removeIf(list -> {
            list.remove(pluginId);
            return list.isEmpty();
        });
    }

    public void unregisterPluginHooks(String pluginId) {
        sendMessageHooks.remove(pluginId);
        hookPriorities.keySet().removeIf(k -> k.endsWith('\u0001' + pluginId));
        synchronized (requestHooks) {
            dropPluginFrom(requestHooks, pluginId);
        }
        synchronized (requestHooksSubstring) {
            dropPluginFrom(requestHooksSubstring, pluginId);
        }
        synchronized (updateHooks) {
            dropPluginFrom(updateHooks, pluginId);
        }
        synchronized (updatesContainerHooks) {
            dropPluginFrom(updatesContainerHooks, pluginId);
        }
        invalidateHookTargets();
        synchronized (menuItems) {
            menuItems.removeIf(item -> item.pluginId.equals(pluginId));
        }
        // Подсистемы с собственными реестрами.
        app.exteraless.plugins.xposed.XposedHooks.unhookAllForPlugin(pluginId);
        app.exteraless.plugins.files.FilesControllerJava.unregisterAllForPlugin(pluginId);
        app.exteraless.plugins.intents.IntentsDispatcher.unregisterAllForPlugin(pluginId);
        app.exteraless.plugins.utils.ClassProxyFactory.releaseAllForPlugin(pluginId);
    }

    // ---------- диспетчеры (зовутся из ядра Telegram) ----------

    /** События приложения из LaunchActivity: app_start/app_stop/app_pause/app_resume. */
    public void executeOnAppEvent(String event) {
        if (!PythonPluginsEngine.getInstance().isStarted()) {
            return;
        }
        List<Plugin> snapshot = getPluginsSnapshot();
        for (Plugin p : snapshot) {
            if (p.loaded) {
                PythonPluginsEngine.getInstance().callAppEvent(p.id, event);
            }
        }
        // Уход в фон: приложение дожило до сюда без падения. Снимаем маркер
        // (дальше процесс может убить сам Android, и это не вина плагина) и
        // обнуляем накопленные страйки загруженных плагинов.
        if (PluginsConstants.EVENT_APP_PAUSE.equals(event)
                || PluginsConstants.EVENT_APP_STOP.equals(event)) {
            PluginsWatchdog wd = getWatchdog();
            if (wd != null) {
                wd.onAppBackgrounded();
                for (Plugin p : snapshot) {
                    if (p.loaded) {
                        wd.noteHealthy(p.id);
                    }
                }
            }
        }
    }

    public boolean hasSendMessageHooks() {
        return !sendMessageHooks.isEmpty();
    }

    /** Дешёвый гейт для ConnectionsManager: есть ли вообще request-хуки. */
    public boolean hasAnyRequestHooks() {
        return !requestHooks.isEmpty() || !requestHooksSubstring.isEmpty();
    }

    /** Исходящее сообщение из SendMessagesHelper. CANCEL = не отправлять. */
    public HookResult executeOnSendMessageHook(int account, Object params) {
        if (sendMessageHooks.isEmpty() || !PythonPluginsEngine.getInstance().isStarted()) {
            return HookResult.DEFAULT;
        }
        List<String> sorted = sendTargetsCache.get("send", () -> byPriority(new HashMap<>(sendMessageHooks)));
        HookResult last = HookResult.DEFAULT;
        for (String pluginId : sorted) {
            Plugin p = getPlugin(pluginId);
            if (p == null || !p.loaded) {
                continue;
            }
            HookResult r = PythonPluginsEngine.getInstance().callSendMessageHook(pluginId, account, params);
            if (r.isCancel()) {
                return r;
            }
            if (r.strategy != HookResult.Strategy.DEFAULT) {
                Object replacement = r.replacement(org.telegram.messenger.SendMessagesHelper.SendMessageParams.class);
                if (replacement != null) {
                    params = replacement;
                }
                last = new HookResult(r.strategy, params);
            }
            if (r.isFinal()) {
                break;
            }
        }
        return last;
    }

    /** Перед отправкой TL-запроса. CANCEL = запрос не уходит. */
    public HookResult executePreRequestHook(int account, String requestName, Object request) {
        List<String> targets = findRequestHookTargets(requestName);
        if (targets.isEmpty() || !PythonPluginsEngine.getInstance().isStarted()) {
            return HookResult.DEFAULT;
        }
        HookResult last = HookResult.DEFAULT;
        for (String pluginId : targets) {
            Plugin p = getPlugin(pluginId);
            if (p == null || !p.loaded) {
                continue;
            }
            HookResult r = PythonPluginsEngine.getInstance()
                    .callPreRequestHook(pluginId, account, requestName, request);
            if (r.isCancel()) {
                return r;
            }
            if (r.strategy != HookResult.Strategy.DEFAULT) {
                Object replacement = r.replacement(org.telegram.tgnet.TLObject.class);
                if (replacement != null) {
                    request = replacement;
                }
                last = new HookResult(r.strategy, request);
            }
            if (r.isFinal()) {
                break;
            }
        }
        return last;
    }

    /** После ответа на TL-запрос. */
    public HookResult executePostRequestHook(int account, String requestName, Object response, Object error) {
        List<String> targets = findRequestHookTargets(requestName);
        if (targets.isEmpty() || !PythonPluginsEngine.getInstance().isStarted()) {
            return HookResult.DEFAULT;
        }
        HookResult last = HookResult.DEFAULT;
        for (String pluginId : targets) {
            Plugin p = getPlugin(pluginId);
            if (p == null || !p.loaded) {
                continue;
            }
            HookResult r = PythonPluginsEngine.getInstance()
                    .callPostRequestHook(pluginId, account, requestName, response, error);
            if (r.isCancel()) {
                return r;
            }
            if (r.strategy != HookResult.Strategy.DEFAULT) {
                Object replacement = r.replacement(org.telegram.tgnet.TLObject.class);
                if (replacement != null) {
                    response = replacement;
                }
                last = new HookResult(r.strategy, response);
            }
            if (r.isFinal()) {
                break;
            }
        }
        return last;
    }

    private List<String> findRequestHookTargets(String requestName) {
        return requestTargetsCache.get(requestName, () -> resolveRequestHookTargets(requestName));
    }

    // ---------- хуки апдейтов ----------

    public boolean hasAnyUpdateHooks() {
        return !updateHooks.isEmpty() || !requestHooksSubstring.isEmpty();
    }

    public boolean hasAnyUpdatesContainerHooks() {
        return !updatesContainerHooks.isEmpty() || !requestHooksSubstring.isEmpty();
    }

    /** Одиночный апдейт из MessagesController.processUpdateArray. CANCEL = не обрабатывать. */
    public HookResult executeOnUpdateHook(int account, String updateName, Object update) {
        List<String> targets = updateTargetsCache.get(updateName, () -> resolveHookTargets(updateName, updateHooks));
        if (targets.isEmpty() || !PythonPluginsEngine.getInstance().isStarted()) {
            return HookResult.DEFAULT;
        }
        HookResult last = HookResult.DEFAULT;
        for (String pluginId : targets) {
            Plugin p = getPlugin(pluginId);
            if (p == null || !p.loaded) {
                continue;
            }
            HookResult r = PythonPluginsEngine.getInstance()
                    .callUpdateHook(pluginId, account, updateName, update);
            if (r.isCancel()) {
                return r;
            }
            if (r.strategy != HookResult.Strategy.DEFAULT) {
                Object replacement = r.replacement(org.telegram.tgnet.TLRPC.Update.class);
                if (replacement != null) {
                    update = replacement;
                }
                last = new HookResult(r.strategy, update);
            }
            if (r.isFinal()) {
                break;
            }
        }
        return last;
    }

    /** Контейнер апдейтов из MessagesController.processUpdates. CANCEL = не обрабатывать. */
    public HookResult executeOnUpdatesHook(int account, String containerName, Object updates) {
        List<String> targets = updatesTargetsCache.get(containerName, () -> resolveHookTargets(containerName, updatesContainerHooks));
        if (targets.isEmpty() || !PythonPluginsEngine.getInstance().isStarted()) {
            return HookResult.DEFAULT;
        }
        HookResult last = HookResult.DEFAULT;
        for (String pluginId : targets) {
            Plugin p = getPlugin(pluginId);
            if (p == null || !p.loaded) {
                continue;
            }
            HookResult r = PythonPluginsEngine.getInstance()
                    .callUpdatesHook(pluginId, account, containerName, updates);
            if (r.isCancel()) {
                return r;
            }
            if (r.strategy != HookResult.Strategy.DEFAULT) {
                Object replacement = r.replacement(org.telegram.tgnet.TLRPC.Updates.class);
                if (replacement != null) {
                    updates = replacement;
                }
                last = new HookResult(r.strategy, updates);
            }
            if (r.isFinal()) {
                break;
            }
        }
        return last;
    }

    /** Пункты меню для типа, отсортированные по priority (desc). Зовут патчи ядра при построении меню. */
    public List<MenuItemRecord> getMenuItemsFor(MenuItemRecord.MenuType type) {
        List<MenuItemRecord> result = new ArrayList<>();
        synchronized (menuItems) {
            for (MenuItemRecord item : menuItems) {
                if (item.menuType == type) {
                    result.add(item);
                }
            }
        }
        result.sort((a, b) -> Integer.compare(b.priority, a.priority));
        return result;
    }

    // ---------- клики по пунктам меню (зовутся из патчей ядра) ----------

    /** Найти пункт меню и вызвать его Python on_click с Map-контекстом. */
    public void dispatchMenuClick(String pluginId, String itemId, java.util.Map<String, Object> context) {
        MenuItemRecord target = null;
        synchronized (menuItems) {
            for (MenuItemRecord item : menuItems) {
                if (item.pluginId.equals(pluginId) && item.itemId.equals(itemId)) {
                    target = item;
                    break;
                }
            }
        }
        if (target == null || target.onClick == null) {
            return;
        }
        PluginsWatchdog wd = getWatchdog();
        wd.notePluginEnter(pluginId);
        try {
            target.onClick.callAttr("__call__", context);
        } catch (Throwable t) {
            wd.handlePluginError(pluginId, t);
        } finally {
            wd.notePluginExit(pluginId);
        }
    }

    // ---------- меню ----------

    private static final AtomicLong generatedMenuItemId = new AtomicLong();

    public String registerMenuItem(String pluginId, String jsonMenuItem,
                                   com.chaquo.python.PyObject onClick) {
        try {
            JSONObject obj = new JSONObject(jsonMenuItem);
            String itemId = obj.optString("item_id");
            if (itemId == null || itemId.isEmpty()) {
                itemId = pluginId + "_" + generatedMenuItemId.incrementAndGet();
            }
            final String finalItemId = itemId;
            MenuItemRecord record = new MenuItemRecord(
                    pluginId,
                    itemId,
                    MenuItemRecord.MenuType.fromString(obj.optString("menu_type")),
                    obj.optString("text"),
                    JsonUtils.optStringOrNull(obj, "subtext"),
                    JsonUtils.optStringOrNull(obj, "icon"),
                    JsonUtils.optStringOrNull(obj, "condition"),
                    obj.optInt("priority", 0),
                    onClick);
            synchronized (menuItems) {
                menuItems.removeIf(i -> i.pluginId.equals(pluginId) && i.itemId.equals(finalItemId));
                menuItems.add(record);
                menuItems.sort(Comparator.comparingInt((MenuItemRecord i) -> i.priority).reversed());
            }
            notifyMenuItemsUpdated();
            return finalItemId;
        } catch (Exception e) {
            FileLog.e("PluginsController: bad menu item json", e);
            return null;
        }
    }

    public void removeMenuItem(String pluginId, String itemId) {
        synchronized (menuItems) {
            menuItems.removeIf(i -> i.pluginId.equals(pluginId) && i.itemId.equals(itemId));
        }
        notifyMenuItemsUpdated();
    }

    /**
     * Реестр пунктов изменился — экраны с подменю плагинов пересобирают его.
     * Зовётся при регистрации/снятии пункта и при выгрузке плагина.
     */
    public void notifyMenuItemsUpdated() {
        org.telegram.messenger.NotificationCenter.getGlobalInstance()
                .postNotificationNameOnUIThread(
                        org.telegram.messenger.NotificationCenter.pluginMenuItemsUpdated);
    }

    private void invalidateHookTargets() {
        sendTargetsCache.invalidate();
        requestTargetsCache.invalidate();
        updateTargetsCache.invalidate();
        updatesTargetsCache.invalidate();
    }

    private List<String> resolveRequestHookTargets(String requestName) {
        return resolveHookTargets(requestName, requestHooks);
    }

    private List<String> resolveHookTargets(String requestName, Map<String, List<String>> exactHooks) {
        Map<String, Integer> priorities = new HashMap<>();
        synchronized (exactHooks) {
            List<String> exact = exactHooks.get(requestName);
            if (exact != null) {
                for (String pluginId : exact) {
                    priorities.merge(pluginId, hookPriority(requestName, pluginId), Math::max);
                }
            }
        }
        synchronized (requestHooksSubstring) {
            for (Map.Entry<String, List<String>> e : requestHooksSubstring.entrySet()) {
                if (requestName != null && requestName.contains(e.getKey())) {
                    for (String pluginId : e.getValue()) {
                        priorities.merge(pluginId, hookPriority(e.getKey(), pluginId), Math::max);
                    }
                }
            }
        }
        return byPriority(priorities);
    }
}
