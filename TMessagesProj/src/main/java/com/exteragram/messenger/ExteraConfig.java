package com.exteragram.messenger;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;

import xyz.nextalone.nagram.NaConfig;

import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.icons.BaseIconPacks;

/**
 * Шим {@code com.exteragram.messenger.ExteraConfig} — статика, к которой
 * обращаются плагины оформления.
 *
 * У exteraGram это поля, а не геттеры, но поле не может читать живое значение
 * настройки, поэтому здесь методы. Chaquopy различает вызов и чтение атрибута,
 * так что плагин, написанный под поле, получил бы объект метода вместо значения;
 * форму выравнивает обёртка {@code extera_utils.class_aliases._FieldShapedClass},
 * которой перечислены имена из {@code _FIELD_SHAPED}. Новый метод, который у
 * эталона поле, надо дописывать туда же.
 */
public final class ExteraConfig {

    /** Значения совпадают с exteraGram: 0 — стоковые, 1 — Solar, 2 — Remix. */
    public static final int ICON_PACK_DEFAULT = BaseIconPacks.BASE_DEFAULT;
    public static final int ICON_PACK_SOLAR = BaseIconPacks.BASE_SOLAR;
    public static final int ICON_PACK_REMIX = BaseIconPacks.BASE_REMIX;

    private ExteraConfig() {
    }

    /** Включён ли safe mode движка плагинов. */
    public static boolean pluginsSafeMode() {
        return app.exteraless.plugins.PluginsController.getInstance().isSafeMode();
    }

    /** Текущий набор иконок: одна из констант {@code ICON_PACK_*}. */
    public static int iconPack() {
        return BaseIconPacks.getSelected();
    }

    /**
     * Радиус скругления аватарки для стороны {@code size} в dp — как у exteraGram,
     * где эта перегрузка сама переводит результат в пиксели. Наш AppearanceConfig
     * считает от пикселей, поэтому перевод делается здесь.
     */
    public static int getAvatarCorners(float size) {
        return getAvatarCorners(size, false, false);
    }

    public static float getAvatarCorners() {
        return AppearanceConfig.INSTANCE.avatarCorners();
    }

    public static int getAvatarCorners(float size, boolean inPixels) {
        return getAvatarCorners(size, inPixels, false);
    }

    public static int getAvatarCorners(float size, boolean inPixels, boolean forum) {
        float value = inPixels ? size : AndroidUtilities.dp(size);
        return AppearanceConfig.INSTANCE.getAvatarCorners(value,
                forum ? AppearanceConfig.CORNER_TYPE_FORUM : AppearanceConfig.CORNER_TYPE_DEFAULT,
                false);
    }

    public static boolean getCenterTitle() {
        return AppearanceConfig.INSTANCE.centerTitle();
    }

    public static boolean getGroupMessageMenu() {
        return NaConfig.INSTANCE.getGroupedMessageMenu().Bool();
    }

    public static boolean getPluginsEngine() {
        return app.exteraless.plugins.PluginsController.getInstance().isEngineEnabled();
    }

    public static boolean getPluginsSafeMode() {
        return pluginsSafeMode();
    }

    public static SharedPreferences.Editor getEditor() {
        return new SafeModeEditor();
    }

    /** Вибро-отклик внутри приложения; в этом форке за него отвечает NekoConfig.disableVibration. */
    public static boolean inAppVibration() {
        return !tw.nekomimi.nekogram.NekoConfig.disableVibration.Bool();
    }

    public static boolean getInAppVibration() {
        return inAppVibration();
    }

    public static boolean forceSnow() {
        return tw.nekomimi.nekogram.NekoConfig.actionBarDecoration.Int() == 1;
    }

    public static boolean getForceSnow() {
        return forceSnow();
    }

    public static void setForceSnow(boolean enabled) {
        tw.nekomimi.nekogram.NekoConfig.actionBarDecoration.setConfigInt(enabled ? 1 : 0);
        NaConfig.INSTANCE.getChatDecoration().setConfigInt(enabled ? 1 : 0);
    }

    private static final class SafeModeEditor implements SharedPreferences.Editor {

        private Boolean safeMode;

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            if (app.exteraless.plugins.PluginsConstants.KEY_SAFE_MODE.equals(key)) {
                safeMode = value;
            }
            return this;
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, java.util.Set<String> values) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            return this;
        }

        @Override
        public boolean commit() {
            apply();
            return true;
        }

        @Override
        public void apply() {
            if (safeMode != null) {
                app.exteraless.plugins.PluginsController.getInstance().setSafeMode(safeMode);
                safeMode = null;
            }
        }
    }
}
