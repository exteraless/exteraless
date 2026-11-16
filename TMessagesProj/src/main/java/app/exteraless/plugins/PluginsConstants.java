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
    public static final String KEY_NATIVE_HOOKS_BROKEN_STAMP = "native_hooks_unsupported_stamp";
    public static final String KEY_NATIVE_HOOKS_STRIKES = "native_hooks_init_strikes";
    /** Сколько раз процесс уже умирал на этом плагине. */
    public static final String KEY_WATCHDOG_STRIKES_PREFIX = "watchdog_strikes_";

    public static final String KEY_WATCHDOG_MUTED_PREFIX = "watchdog_muted_";

    public static final String KEY_WATCHDOG_EARLY_CRASHES = "watchdog_early_crashes";
    public static final String KEY_SAFE_MODE_AUTO = "pluginsSafeModeAuto";

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
     * Значение повторяет версию SDK exteraGram (в 12.9.2 — 1.4.5.3,
     * assets/plugins_pysdk/v.txt), по документации которой и написан наш
     * Python-SDK. Заявлять её честно:
     * поверхность имён и семантика соответствуют, отсутствующее покрыто
     * явными исключениями, а не молчаливыми заглушками.
     */
    public static final String SDK_VERSION = "1.4.6.3";

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

    public static final String SEND_MESSAGE_HOOK = "send_message_hook";
    public static final String STRATEGY = "strategy";
    public static final String PARAMS = "params";
    public static final String UPDATE = "update";
    public static final String UPDATES = "updates";
    public static final String REQUEST = "request";
    public static final String RESPONSE = "response";
    public static final String ERROR = "error";
    public static final String PLUGINS = "plugins";
    public static final String PLUGINS_EXT = ".plugin";
    public static final String PLUGINS_SDK = "plugins-sdk";
    public static final String CREATE_SETTINGS = "create_settings";
    public static final String APP_START = "app_start";
    public static final String APP_STOP = "app_stop";
    public static final String APP_PAUSE = "app_pause";
    public static final String APP_RESUME = "app_resume";
    public static final String ON_APP_EVENT = "on_app_event";
    public static final String ON_PLUGIN_LOAD = "on_plugin_load";
    public static final String ON_PLUGIN_UNLOAD = "on_plugin_unload";

    public static final class Settings {
        public static final String TYPE = "type";
        public static final String KEY = "key";
        public static final String TEXT = "text";
        public static final String SUBTEXT = "subtext";
        public static final String ICON = "icon";
        public static final String ACCENT = "accent";
        public static final String RED = "red";
        public static final String ON_CLICK = "on_click";
        public static final String DEFAULT = "default";
        public static final String ITEMS = "items";
        public static final String HINT = "hint";
        public static final String MULTILINE = "multiline";
        public static final String MAX_LENGTH = "max_length";
        public static final String MASK = "mask";
        public static final String ON_CHANGE = "on_change";
        public static final String TYPE_SWITCH = "switch";
        public static final String TYPE_INPUT = "input";
        public static final String TYPE_SELECTOR = "selector";
        public static final String TYPE_HEADER = "header";
        public static final String TYPE_DIVIDER = "divider";
        public static final String TYPE_TEXT = "text";
        public static final String TYPE_EDIT_TEXT = "edit_text";
        public static final String TYPE_CUSTOM = "custom";
        public static final String VIEW = "view";
        public static final String ITEM = "item";
        public static final String FACTORY = "factory";
        public static final String FACTORY_ARGS = "factory_args";
        public static final String CREATE_SUB_FRAGMENT = "create_sub_fragment";
        public static final String ON_LONG_CLICK = "on_long_click";
        public static final String LINK_ALIAS = "link_alias";

        private Settings() {
        }
    }

    public static final class Strategy {
        public static final String MODIFY = "MODIFY";
        public static final String CANCEL = "CANCEL";
        public static final String DEFAULT = "DEFAULT";
        public static final String MODIFY_FINAL = "MODIFY_FINAL";

        private Strategy() {
        }
    }

    public static final class DevServer {
        public static final String MODULE = "dev_server";
        public static final String CLASS = "DevServer";
        public static final String START_SERVER = "start_server";
        public static final String STOP_SERVER = "stop_server";

        private DevServer() {
        }
    }

    public static final class MenuItemTypes {
        public static final String MESSAGE_CONTEXT_MENU = "message_context_menu";
        public static final String DRAWER_MENU = "drawer_menu";
        public static final String MAIN_MENU = "main_menu";
        public static final String CHAT_ACTION_MENU = "chat_action_menu";
        public static final String PROFILE_ACTION_MENU = "profile_action_menu";

        private MenuItemTypes() {
        }
    }

    public static final class MenuItemProperties {
        public static final String MENU_TYPE = "menu_type";
        public static final String ITEM_ID = "item_id";
        public static final String TEXT = "text";
        public static final String SUBTEXT = "subtext";
        public static final String ICON = "icon";
        public static final String ON_CLICK = "on_click";
        public static final String CONDITION = "condition";
        public static final String PRIORITY = "priority";

        private MenuItemProperties() {
        }
    }

    public static final class HookFilterTypes {
        public static final String RESULT_IS_NULL = "result_is_null";
        public static final String RESULT_IS_TRUE = "result_is_true";
        public static final String RESULT_IS_FALSE = "result_is_false";
        public static final String RESULT_NOT_NULL = "result_not_null";
        public static final String RESULT_IS_INSTANCE_OF = "result_is_instance_of";
        public static final String RESULT_EQUAL = "result_equal";
        public static final String RESULT_NOT_EQUAL = "result_not_equal";
        public static final String ARGUMENT_IS_NULL = "argument_is_null";
        public static final String ARGUMENT_IS_TRUE = "argument_is_true";
        public static final String ARGUMENT_IS_FALSE = "argument_is_false";
        public static final String ARGUMENT_NOT_NULL = "argument_not_null";
        public static final String ARGUMENT_IS_INSTANCE_OF = "argument_is_instance_of";
        public static final String ARGUMENT_EQUAL = "argument_equal";
        public static final String ARGUMENT_NOT_EQUAL = "argument_not_equal";
        public static final String CONDITION = "condition";
        public static final String OR = "or";

        private HookFilterTypes() {
        }
    }

    public static final class Xposed {
        public static final String REPLACE_HOOKED_METHOD = "replace_hooked_method";
        public static final String BEFORE_HOOKED_METHOD = "before_hooked_method";
        public static final String AFTER_HOOKED_METHOD = "after_hooked_method";
        public static final String HOOK_FILTERS = "__hook_filters__";

        private Xposed() {
        }
    }
}
