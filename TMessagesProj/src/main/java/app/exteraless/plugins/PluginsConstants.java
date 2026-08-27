package app.exteraless.plugins;

/**
 * Константы движка плагинов exteraless.
 * Аналог com.exteragram.messenger.plugins.PluginsConstants из exteraGram 12.9.0,
 * но со своими ключами (совместимость по протоколу не требуется — свой SDK).
 */
public final class PluginsConstants {

    private PluginsConstants() {
    }

    /** Свой SharedPreferences-файл движка (как у exteraGram — ApplicationLoader читает свой prefs). */
    public static final String PREFS_NAME = "exteraless_plugins";

    /** Мастер-тумблер движка. */
    public static final String KEY_ENGINE_ENABLED = "pluginsEnabled";
    /** Safe mode: движок стартует, но плагины не грузятся. */
    public static final String KEY_SAFE_MODE = "pluginsSafeMode";
    /** Developer mode: перезагрузка плагинов из UI, подробные ошибки. */
    public static final String KEY_DEVELOPER_MODE = "pluginsDeveloperMode";
    /** Компактный вид списка плагинов. */
    public static final String KEY_COMPACT_VIEW = "plugins_compact_view";
    /** Режим совместимости: отключить ART Profile Saver ради надёжности хуков. */
    public static final String KEY_COMPATIBILITY_MODE = "plugins_compatibility_mode";
    public static final String KEY_UNSAFE_MODE = "pluginsUnsafeMode";

    /** Префикс ключа включённости конкретного плагина: plugin_enabled_<id>. */
    public static final String KEY_PLUGIN_ENABLED_PREFIX = "plugin_enabled_";

    /**
     * Префикс ключа выданных разрешений: plugin_perms_&lt;id&gt;, значение — ключи через
     * запятую (пустая строка = только ui). Спецификация называла файлом хранения nkmrcfg,
     * но все остальные ключи движка (plugin_enabled_) лежат здесь, в PREFS_NAME, и
     * удаляются вместе с плагином — разносить по двум файлам нечем оправдать.
     */
    public static final String KEY_PLUGIN_PERMS_PREFIX = "plugin_perms_";
    /** Уровень доступа плагина: {@code plugin_level_<id>}, значение — int. */
    public static final String KEY_PLUGIN_LEVEL_PREFIX = "plugin_level_";

    /** Watchdog: id плагина, которого грузили/выполняли в момент падения. */
    /** Устарел: маркер исполняемого плагина уехал в файл plugins/.watchdog. */
    public static final String KEY_WATCHDOG_LOADING_LEGACY = "watchdog_loading_plugin";
    /** Watchdog: id плагина, отключённого после падения (для бюллетеня). */
    public static final String KEY_WATCHDOG_CRASHED = "watchdog_crashed_plugin";

    public static final String KEY_NATIVE_HOOKS_PENDING = "native_hooks_init_pending";

    public static final String KEY_NATIVE_HOOKS_BROKEN = "native_hooks_unsupported";
    /** Сколько раз процесс уже умирал на этом плагине. */
    public static final String KEY_WATCHDOG_STRIKES_PREFIX = "watchdog_strikes_";

    public static final String KEY_WATCHDOG_MUTED_PREFIX = "watchdog_muted_";

    /** Per-plugin настройки: отдельный prefs-файл plugin_settings_<id>, значения — JSON. */
    public static final String SETTINGS_PREFS_PREFIX = "plugin_settings_";

    /** Расширения файлов плагинов. .plugin — как у exteraGram, .py — для удобства dev-сценария. */
    public static final String PLUGIN_EXT = ".plugin";
    public static final String PLUGIN_EXT_PY = ".py";
    public static final String PLUGIN_EXT_ELYX = ".elyx";
    public static final String PLUGIN_EXT_EAF = ".eaf";

    /** Версия нашего Python SDK. Своя линейка, к 1.4.5.0 из exteraGram отношения не имеет. */
    /**
     * Версия SDK, о которой мы заявляем плагинам.
     *
     * Плагины объявляют {@code __sdk_version__ = ">=1.4.3.3"} и подобное, и при
     * несовпадении установка отклоняется. В каталоге из 361 плагина такое
     * ограничение стоит у 72 — при прежнем значении "1.0.0" ни один из них не
     * ставился вообще.
     *
     * 1.4.5.3 — версия SDK exteraGram 12.9.2 (assets/plugins_pysdk/v.txt), по
     * документации которой и написан наш Python-SDK. Заявлять её честно:
     * поверхность имён и семантика соответствуют, отсутствующее покрыто
     * явными исключениями, а не молчаливыми заглушками.
     */
    public static final String SDK_VERSION = "1.4.5.3";

    /** Имя движка в карте getEngines(); плагины каталога берут его отсюда. */
    public static final String PYTHON = "python";

    /** События приложения (строки протокола, как у exteraGram). */
    public static final String EVENT_APP_START = "app_start";
    public static final String EVENT_APP_STOP = "app_stop";
    public static final String EVENT_APP_PAUSE = "app_pause";
    public static final String EVENT_APP_RESUME = "app_resume";

    /** Стратегии HookResult (строки = значениям Python-enum HookStrategy). */
    public static final String STRATEGY_DEFAULT = "DEFAULT";
    public static final String STRATEGY_CANCEL = "CANCEL";
    public static final String STRATEGY_MODIFY = "MODIFY";
    public static final String STRATEGY_MODIFY_FINAL = "MODIFY_FINAL";

    /** Лимиты валидации __id__ (по документации plugins.exteragram.app). */
    public static final int PLUGIN_ID_MIN = 2;
    public static final int PLUGIN_ID_MAX = 32;
}
