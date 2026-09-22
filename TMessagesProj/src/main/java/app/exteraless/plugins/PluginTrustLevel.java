package app.exteraless.plugins;

import android.content.SharedPreferences;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Уровень доступа плагина — один рычаг вместо семи не связанных тумблеров.
 *
 * Разрешения отвечают на вопрос «что можно», уровень — на вопрос «насколько
 * вообще этому плагину верим». Это разные решения, и раньше второе принималось
 * незаметно: тумблер {@code hooks} выглядел восьмым в ряду, хотя включение его
 * отменяет действие всех остальных — плагин с хуками снимает наши же проверки.
 *
 * <ul>
 *   <li>{@link #ISOLATED} — только свои меню, диалоги и экран настроек. Ни сети,
 *       ни файлов, ни сообщений, ни хуков, что бы плагин ни просил и что бы ни
 *       было выдано раньше. Значение по умолчанию для новых установок.</li>
 *   <li>{@link #GATED} — разрешения выдаются по одному, гейт их проверяет.
 *       {@code hooks} на этом уровне недоступен: он и есть выход из гейта.</li>
 *   <li>{@link #TRUSTED} — доступны хуки. Проверки формально остаются, но плагин
 *       может их снять, и об этом сказано прямо на экране.</li>
 * </ul>
 *
 * Уровень главнее выданных разрешений: {@link PluginPermissions#getEffective}
 * срезает по нему всё лишнее. Поэтому понижение уровня действует сразу и не
 * зависит от того, что записано в {@code plugin_perms_<id>} — снятое понижением
 * не «всплывёт» обратно при повышении.
 */
public final class PluginTrustLevel {

    public static final int ISOLATED = 0;
    public static final int GATED = 1;
    public static final int TRUSTED = 2;

    /** Уровень новых установок. Права выдаются потом и осознанно. */
    public static final int DEFAULT = ISOLATED;

    private PluginTrustLevel() {
    }

    public static String prefsKey(String pluginId) {
        return PluginsConstants.KEY_PLUGIN_LEVEL_PREFIX + pluginId;
    }

    private static SharedPreferences prefs() {
        return PluginsController.getInstance().getPreferences();
    }

    /**
     * Уровень плагина. Если записи нет — плагин установлен до появления рычага;
     * тогда уровень выводится из того, с чем он фактически работал, и
     * закрепляется. Молча понизить уже работающий плагин нельзя: это выглядело бы
     * как «после обновления всё сломалось».
     */
    public static int getLevel(String pluginId) {
        SharedPreferences p = prefs();
        if (p == null || pluginId == null) {
            return DEFAULT;
        }
        String key = prefsKey(pluginId);
        if (p.contains(key)) {
            return clamp(p.getInt(key, DEFAULT));
        }
        int migrated = migrate(pluginId);
        p.edit().putInt(key, migrated).apply();
        return migrated;
    }

    /** Уровень для плагина, установленного до появления рычага. */
    private static int migrate(String pluginId) {
        List<String> effective = PluginPermissions.getEffectiveRaw(pluginId);
        if (effective.contains(PluginPermissions.HOOKS)
                || effective.contains(PluginPermissions.NATIVE)) {
            return TRUSTED;
        }
        for (String perm : effective) {
            if (!PluginPermissions.UI.equals(perm)) {
                return GATED;
            }
        }
        return ISOLATED;
    }

    /**
     * Сменить уровень. Несовместимые разрешения снимаются здесь же, а не
     * «фильтруются при чтении»: пользователь должен увидеть на экране то же
     * самое, что и получит плагин.
     */
    public static void setLevel(String pluginId, int level) {
        SharedPreferences p = prefs();
        if (p == null || pluginId == null) {
            return;
        }
        int value = clamp(level);
        p.edit().putInt(prefsKey(pluginId), value).apply();
        PluginPermissions.invalidateCache();
        if (value == ISOLATED) {
            PluginPermissions.setGranted(pluginId, new ArrayList<>());
        } else if (value == GATED) {
            PluginPermissions.revoke(pluginId, PluginPermissions.HOOKS);
            PluginPermissions.revoke(pluginId, PluginPermissions.NATIVE);
        }
        FileLog.d("PluginTrustLevel: " + pluginId + " -> " + name(value));
    }

    /** Уровень позволяет выдавать это разрешение? */
    public static boolean allows(int level, String perm) {
        if (PluginPermissions.UI.equals(perm)) {
            return true;
        }
        if (PluginPermissions.isUnsafeMode()) {
            return true;
        }
        if (level == ISOLATED) {
            return false;
        }
        return level == TRUSTED || !PluginPermissions.isDangerous(perm);
    }

    public static boolean allows(String pluginId, String perm) {
        return allows(getLevel(pluginId), perm);
    }

    /** Забыть уровень (удаление плагина). */
    public static void clear(String pluginId) {
        SharedPreferences p = prefs();
        if (p != null && pluginId != null) {
            p.edit().remove(prefsKey(pluginId)).apply();
            PluginPermissions.invalidateCache();
        }
    }

    private static int clamp(int level) {
        if (level < ISOLATED) {
            return ISOLATED;
        }
        return level > TRUSTED ? TRUSTED : level;
    }

    private static String name(int level) {
        switch (level) {
            case ISOLATED: return "isolated";
            case GATED: return "gated";
            case TRUSTED: return "trusted";
            default: return String.valueOf(level);
        }
    }
}
