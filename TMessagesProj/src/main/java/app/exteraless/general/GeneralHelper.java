package app.exteraless.general;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.LinearLayout;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import app.exteraless.OpenExteraConfig;
import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.chats.ChatsConfig;
import app.exteraless.config.LegacyDefaults;
import app.exteraless.icons.IconPacksConfig;
import app.exteraless.pillstack.PillStackConfig;
import app.exteraless.utils.UtilsConfig;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;
import xyz.nextalone.nagram.NaConfig;

/**
 * Мелкие помощники для экранов «General» и «Other» раздела openExtera.
 * Собственных состояний не держит, методов фреймворка не переопределяет.
 */
public final class GeneralHelper {

    private GeneralHelper() {
    }

    public interface StringCallback {
        void run(String value);
    }

    /**
     * Простой диалог ввода строки (по образцу ConfigCellTextDetail).
     * Значение отдаётся уже «как есть», нормализацию делает вызывающий.
     */
    public static void showTextInputDialog(BaseFragment fragment,
                                           String title,
                                           String hint,
                                           String current,
                                           StringCallback onApply) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setHandlesColor(Theme.getColor(Theme.key_chat_TextSelectionCursor));
        editText.setFocusable(true);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                Theme.getColor(Theme.key_text_RedRegular));
        editText.setPadding(0, 0, 0, dp(6));
        editText.setText(current == null ? "" : current);
        editText.setHint(hint == null ? "" : hint);
        editText.requestFocus();
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, dp(8), 0, dp(10), 0));

        builder.setView(linearLayout);
        builder.setPositiveButton(getString(R.string.OK), null);
        builder.setNegativeButton(getString(R.string.Cancel), null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            onApply.run(editText.getText().toString());
            dialog.dismiss();
        }));
        fragment.showDialog(dialog);
    }

    /** Нормализует имя папки сохранения так же, как это делает NekoGeneralSettingsActivity. */
    public static String sanitizeSavePath(String input) {
        if (TextUtils.isEmpty(input)) {
            return "";
        }
        String normalized = input.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.matches("^(?!\\.{1,2}$)[A-Za-z0-9._ -]{1,255}$")) {
            return normalized;
        }
        return (String) NekoConfig.customSavePath.defaultValue;
    }

    /**
     * Сброс всех настроек openExtera к значениям по умолчанию — так обещает диалог
     * OEGeneralResetSettingsInfo. Настройки NagramX, кроме перечисленных ниже, не трогаются.
     */
    public static void resetSettings() {
        OpenExteraConfig.reset();
        GeneralConfig.reset();
        AppearanceConfig.reset();
        ChatsConfig.reset();
        IconPacksConfig.reset();
        PillStackConfig.reset();
        UtilsConfig.reset();
        resetToDefault(NekoConfig.disableNumberRounding);
        resetToDefault(NekoConfig.showSeconds);
        resetToDefault(NekoConfig.disableVibration);
        resetToDefault(NaConfig.INSTANCE.getZalgoFilter());
        resetToDefault(NekoConfig.translationProvider);
        resetToDefault(NekoConfig.translateToLang);
        resetToDefault(NekoConfig.uploadBoost);
        resetToDefault(NekoConfig.customSavePath);
        resetToDefault(NekoConfig.hidePhone);
        resetToDefault(NaConfig.INSTANCE.getIdDcType());
        resetToDefault(NaConfig.INSTANCE.getHideArchive());
        resetToDefault(NekoConfig.openArchiveOnPull);
        resetToDefault(NaConfig.INSTANCE.getDoNotUnarchiveBySwipe());
    }

    public static void resetAllSettings() {
        resetSettings();
        Context context = ApplicationLoader.applicationContext;
        context.getSharedPreferences("nekocloud", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("nekox_config", Context.MODE_PRIVATE).edit().clear().commit();
        SharedPreferences.Editor editor = NekoConfig.getPreferences().edit().clear();
        LegacyDefaults.keepPinned(editor);
        editor.commit();
    }

    private static void resetToDefault(ConfigItem item) {
        if (item == null) {
            return;
        }
        switch (item.type) {
            case ConfigItem.configTypeBool -> item.setConfigBool((Boolean) item.defaultValue);
            case ConfigItem.configTypeInt -> item.setConfigInt((Integer) item.defaultValue);
            case ConfigItem.configTypeLong -> item.setConfigLong((Long) item.defaultValue);
            case ConfigItem.configTypeFloat -> item.setConfigFloat((Float) item.defaultValue);
            case ConfigItem.configTypeString -> item.setConfigString((String) item.defaultValue);
            default -> {
            }
        }
    }
}
