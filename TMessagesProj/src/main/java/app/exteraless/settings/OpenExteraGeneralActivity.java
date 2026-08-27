package app.exteraless.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.core.app.NotificationManagerCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SlideChooseView;
import org.telegram.ui.RestrictedLanguagesSelectActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import app.exteraless.OpenExteraConfig;
import app.exteraless.general.GeneralConfig;
import app.exteraless.nowplaying.ProfileMusicStamp;
import kotlin.Unit;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.NekoXConfig;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.helpers.MessageHelper;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.translate.Translator;
import tw.nekomimi.nekogram.translate.TranslatorKt;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import xyz.nextalone.nagram.NaConfig;

/**
 * Экран «General» раздела openExtera — визуально 1:1 повторяет General из exteraGram.
 * Настройки по возможности привязаны к уже существующим ConfigItem NagramX; новые заводятся
 * только там, где в NagramX нет аналога, и помечены «UI-only» (без бэкенда, как в exteraGram 12.9.0).
 */
public class OpenExteraGeneralActivity extends BaseNekoSettingsActivity {

    private static final int TYPE_SLIDE = 100;

    /** Проверяется имя папки, а не путь. */
    private static final Pattern LASTFM_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern SAVE_PATH_PATTERN = Pattern.compile("^(?!\\.{1,2}$)[A-Za-z0-9._ -]{1,255}$");

    /**
     * «Зальго»-образец для подписи под переключателем фильтра. У нашего фильтра порог срабатывания —
     * четыре подряд идущих комбинирующих знака (MessageHelper.ZALGO_PATTERN, :887), поэтому у образца
     * их по четыре на букву, иначе строка выглядела бы одинаково при включённом и выключенном фильтре.
     */
    private static final String ZALGO_SAMPLE =
            "Z\u0334\u034d\u030c\u0301a\u0308\u0325\u0347\u0303l\u0302\u031e\u0356\u0300"
                    + "g\u0300\u035d\u0345\u0330o\u0304\u0353\u0359\u0306";

    private int translateHeaderRow;
    private int translateButtonRow;
    private int translateChatButtonRow;
    private int translationProviderRow;
    private int translateToLangRow;
    private int doNotTranslateRow;
    private int translateDividerRow;

    private int generalHeaderRow;
    private int disableNumberRoundingRow;
    private int formatTimeWithSecondsRow;
    private int inAppVibrationRow;
    private int filterZalgoRow;
    private int generalDividerRow;

    private int speedHeaderRow;
    private int downloadSpeedRow;
    private int uploadBoostRow;
    private int speedDividerRow;

    private int storageHeaderRow;
    private int savePathRow;
    private int storageDividerRow;

    private int profileHeaderRow;
    private int relativeLastSeenRow;
    private int hidePhoneRow;
    private int showIdAndDcRow;
    private int lastfmRow;
    private int profileDividerRow;

    private int archiveHeaderRow;
    private int hideArchiveRow;
    private int archiveOnPullRow;
    private int disableUnarchiveSwipeRow;
    private int archiveDividerRow;

    private int mapsHeaderRow;
    private int mapProviderRow;
    private int mapDriftingFixRow;
    private int mapPreviewRow;
    private int mapsDividerRow;
    private int notificationsHeaderRow;
    private int pushStatusRow;
    private int batteryOptimizationRow;
    private int notificationsDividerRow;

    /** Момент «пять минут назад» для живого примера в строке Relative Last Seen. */
    private int fiveMinutesAgo;

    public OpenExteraGeneralActivity() {
        super();
    }

    @Override
    public boolean onFragmentCreate() {
        GeneralConfig.init();
        fiveMinutesAgo = getConnectionsManager().getCurrentTime() - 300;
        return super.onFragmentCreate();
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        translateHeaderRow = addRow("translateHeader");
        translateButtonRow = addRow("translateButton");
        translateChatButtonRow = addRow("translateChatButton");
        translationProviderRow = addRow("translationProvider");
        translateToLangRow = addRow("translateToLang");
        doNotTranslateRow = addRow("doNotTranslate");
        translateDividerRow = addRow();

        generalHeaderRow = addRow("generalHeader");
        disableNumberRoundingRow = addRow("disableNumberRounding");
        formatTimeWithSecondsRow = addRow("formatTimeWithSeconds");
        inAppVibrationRow = addRow("inAppVibration");
        filterZalgoRow = addRow("filterZalgo");
        generalDividerRow = addRow();

        speedHeaderRow = addRow("speedHeader");
        downloadSpeedRow = addRow("downloadSpeed");
        uploadBoostRow = addRow("uploadBoost");
        speedDividerRow = addRow();

        storageHeaderRow = addRow("storageHeader");
        savePathRow = addRow("savePath");
        storageDividerRow = addRow();

        profileHeaderRow = addRow("profileHeader");
        relativeLastSeenRow = addRow("relativeLastSeen");
        hidePhoneRow = addRow("hidePhone");
        showIdAndDcRow = addRow("showIdAndDc");
        lastfmRow = addRow("lastfm");
        profileDividerRow = addRow();

        archiveHeaderRow = addRow("archiveHeader");
        hideArchiveRow = addRow("hideArchive");
        // Когда архив скрыт, строка уходит целиком: «открывать архив потягиванием»
        // нечего, если папки архива нет в списке.
        archiveOnPullRow = NaConfig.INSTANCE.getHideArchive().Bool() ? -1 : addRow("archiveOnPull");
        disableUnarchiveSwipeRow = addRow("disableUnarchiveSwipe");
        archiveDividerRow = addRow();

        mapsHeaderRow = addRow("mapsHeader");
        mapProviderRow = addRow("mapProvider");
        mapDriftingFixRow = NekoConfig.useOSMDroidMap.Bool() ? -1 : addRow("mapDriftingFix");
        mapPreviewRow = addRow("mapPreview");
        mapsDividerRow = addRow();

        notificationsHeaderRow = addRow("notificationsHeader");
        pushStatusRow = addRow("pushStatus");
        batteryOptimizationRow = addRow("batteryOptimization");
        notificationsDividerRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.OEGeneralTitle);
    }

    @Override
    public int getSearchGuid() {
        return 20000;
    }

    @Override
    public int getSearchIcon() {
        return R.drawable.msg_media;
    }

    @Override
    public String getSearchPrefix() {
        return "OEGeneral";
    }

    @Override
    protected String getKey() {
        return "exteraless_general";
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    /** Перерисовать экраны под нами. */
    private void rebuildAll() {
        if (getParentLayout() != null) {
            getParentLayout().rebuildAllFragmentViews(false, false);
        }
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == pushStatusRow) {
            copyPushStatus();
            return;
        }

        if (position == mapProviderRow) {
            showMapProviderSelector();
            return;
        }

        if (position == mapDriftingFixRow) {
            boolean value = !NekoConfig.mapDriftingFixForGoogleMaps.Bool();
            NekoConfig.mapDriftingFixForGoogleMaps.setConfigBool(value);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
            return;
        }

        if (position == mapPreviewRow) {
            showMapPreviewSelector();
            return;
        }

        if (position == batteryOptimizationRow) {
            openBatteryOptimizationSettings();
            return;
        }

        if (position == translationProviderRow) {
            Translator.showProviderSelect(view, provider -> {
                NekoConfig.translationProvider.setConfigInt(provider);
                listAdapter.notifyItemChanged(translationProviderRow);
                listAdapter.notifyItemChanged(translateToLangRow);
                return Unit.INSTANCE;
            });
            return;
        }

        if (position == translateToLangRow) {
            Translator.showTargetLangSelect(view, false, locale -> {
                NekoConfig.translateToLang.setConfigString(TranslatorKt.getLocale2code(locale));
                listAdapter.notifyItemChanged(translateToLangRow);
                return Unit.INSTANCE;
            });
            return;
        }

        if (position == doNotTranslateRow) {
            presentFragment(new RestrictedLanguagesSelectActivity());
            return;
        }

        if (position == savePathRow) {
            showCustomSavePathDialog();
            return;
        }

        if (position == showIdAndDcRow) {
            showIdAndDcSelector();
            return;
        }

        if (position == lastfmRow) {
            if (GeneralConfig.lastfmExplained.Bool()) {
                showLastFmDialog();
            } else {
                showLastFmAbout();
            }
            return;
        }

        ConfigItem item = null;
        boolean inverted = false;

        if (position == translateButtonRow) {
            item = NekoConfig.showTranslate;
        } else if (position == translateChatButtonRow) {
            item = NaConfig.INSTANCE.getTelegramUIAutoTranslate();
        } else if (position == disableNumberRoundingRow) {
            item = NekoConfig.disableNumberRounding;
        } else if (position == formatTimeWithSecondsRow) {
            item = NekoConfig.showSeconds;
        } else if (position == relativeLastSeenRow) {
            item = OpenExteraConfig.relativeLastSeen;
        } else if (position == inAppVibrationRow) {
            // NekoConfig.disableVibration инвертирована относительно подписи «In-App Vibration».
            item = NekoConfig.disableVibration;
            inverted = true;
        } else if (position == filterZalgoRow) {
            item = NaConfig.INSTANCE.getZalgoFilter();
        } else if (position == uploadBoostRow) {
            item = NekoConfig.uploadBoost;
        } else if (position == hidePhoneRow) {
            item = NekoConfig.hidePhone;
        } else if (position == hideArchiveRow) {
            item = NaConfig.INSTANCE.getHideArchive();
        } else if (position == archiveOnPullRow) {
            item = NekoConfig.openArchiveOnPull;
        } else if (position == disableUnarchiveSwipeRow) {
            item = NaConfig.INSTANCE.getDoNotUnarchiveBySwipe();
        }

        if (item == null) {
            return;
        }

        boolean raw = item.toggleConfigBool();
        boolean shown = inverted != raw;
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(shown);
        }

        if (position == translateButtonRow || position == translateChatButtonRow) {
            // Поиск по настройкам переиндексируется, экраны пересобираются — пункт
            // «Translate» появляется и исчезает в меню сообщения.
            getNotificationCenter().postNotificationName(NotificationCenter.updateSearchSettings);
            rebuildAll();
        }
        if (position == formatTimeWithSecondsRow) {
            LocaleController.getInstance().recreateFormatters();
            rebuildAll();
        }
        if (position == filterZalgoRow) {
            // Подпись секции показывает результат работы фильтра, значит меняется вместе с ним.
            listAdapter.notifyItemChanged(generalDividerRow);
            rebuildAll();
        }
        if (position == relativeLastSeenRow && view instanceof TextCheckCell) {
            // Подпись строки — живое превью самой настройки. Меняем только подпись:
            // пересборка ячейки оборвала бы анимацию переключателя, которая уже идёт.
            ((TextCheckCell) view).setValueText(
                    LocaleController.formatDateOnline(fiveMinutesAgo, new boolean[1]));
        }
        if (position == hidePhoneRow) {
            getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
            rebuildAll();
        }
        if (position == hideArchiveRow) {
            // Папка архива пересобирается сразу.
            getMessagesController().checkArchiveFolder();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload, true);
            // Со скрытым архивом строке «открывать архив потягиванием» нечего открывать,
            // она уходит из списка — но с анимацией: notifyDataSetChanged её убивает,
            // и строка исчезает рывком.
            final int wasPullRow = archiveOnPullRow;
            updateRows();
            if (listAdapter != null) {
                if (archiveOnPullRow == -1) {
                    listAdapter.notifyItemRemoved(wasPullRow);
                } else {
                    listAdapter.notifyItemInserted(archiveOnPullRow);
                }
            }
        }
    }

    private CharSequence[] idOptions() {
        return new CharSequence[]{getString(R.string.Hide), "Telegram API", "Bot API"};
    }

    private CharSequence[] mapProviderOptions() {
        return new CharSequence[]{"Google Maps", "OpenStreetMap"};
    }

    private CharSequence[] mapPreviewOptions() {
        return new CharSequence[]{
                getString(R.string.MapPreviewProviderTelegram),
                getString(R.string.MapPreviewProviderYandexNax),
                getString(R.string.MapPreviewProviderNobody)};
    }

    private void showMapProviderSelector() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.OEGeneralMapProvider));
        builder.setItems(mapProviderOptions(), (dialog, which) -> {
            NekoConfig.useOSMDroidMap.setConfigBool(which == 1);
            ApplicationLoader.resetMapsProvider();
            updateRows();
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showMapPreviewSelector() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.OEGeneralMapPreview));
        builder.setItems(mapPreviewOptions(), (dialog, which) -> {
            NekoConfig.mapPreviewProvider.setConfigInt(which);
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(mapPreviewRow);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showIdAndDcSelector() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.OEGeneralShowIdAndDc));
        builder.setItems(idOptions(), (dialog, which) -> {
            NaConfig.INSTANCE.getIdDcType().setConfigInt(which);
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(showIdAndDcRow);
            }
            getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
            rebuildAll();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /**
     * Диалог имени папки сохранения: неподходящее имя не «чинится» молча, а отбивается
     * тряской поля, диалог остаётся открытым.
     */
    private void showCustomSavePathDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.lineYFix = true;
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setText(NekoConfig.customSavePath.String());
        editText.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        editText.setHintColor(getThemedColor(Theme.key_groupcreate_hintText));
        editText.setHintText(getString(R.string.OEGeneralSavePathHint));
        editText.setFocusable(true);
        editText.setSingleLine(true);
        editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        editText.setBackground(null);
        editText.setLineColors(getThemedColor(Theme.key_windowBackgroundWhiteInputField),
                getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                getThemedColor(Theme.key_text_RedRegular));
        editText.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        editText.setPadding(0, dp(6), 0, dp(6));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 24f, 0f, 24f, 10f));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.OEGeneralSavePath));
        builder.makeCustomMaxHeight();
        builder.setView(container);
        builder.setWidth(dp(292));
        builder.setPositiveButton(getString(R.string.Done), (dialog, which) -> {
            String value = editText.getText() == null ? "" : editText.getText().toString().trim();
            if (!TextUtils.isEmpty(value) && !SAVE_PATH_PATTERN.matcher(value).matches()) {
                AndroidUtilities.shakeView(editText);
                return;
            }
            NekoConfig.customSavePath.setConfigString(value);
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(savePathRow);
                // Подпись секции зависит от значения — обновляем вместе со строкой.
                listAdapter.notifyItemChanged(storageDividerRow);
            }
            dialog.dismiss();
        });
        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            editText.setSelection(editText.length());
            AndroidUtilities.showKeyboard(editText);
        });
        // Без этого диалог закрылся бы раньше проверки и «тряска» была бы не видна.
        dialog.setDismissDialogByButtons(false);
        // Слушателя закрытия ставим через showDialog: BaseFragment.showDialog (:834)
        // затирает тот, что назначен диалогу напрямую.
        showDialog(dialog, d -> AndroidUtilities.hideKeyboard(editText));
    }

    private void showLastFmAbout() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.OEGeneralLastFm));
        builder.setMessage(getString(R.string.OEGeneralLastFmAbout));
        builder.setPositiveButton(getString(R.string.Continue), (dialog, which) -> {
            GeneralConfig.lastfmExplained.setConfigBool(true);
            dialog.dismiss();
            showLastFmDialog();
        });
        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
        showDialog(builder.create());
    }

    private void showLastFmDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.lineYFix = true;
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setText(GeneralConfig.lastfmNick());
        editText.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        editText.setHintColor(getThemedColor(Theme.key_groupcreate_hintText));
        editText.setHintText(getString(R.string.OEGeneralLastFmHint));
        editText.setFocusable(true);
        editText.setSingleLine(true);
        editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        editText.setBackground(null);
        editText.setLineColors(getThemedColor(Theme.key_windowBackgroundWhiteInputField),
                getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                getThemedColor(Theme.key_text_RedRegular));
        editText.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        editText.setPadding(0, dp(6), 0, dp(6));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 24f, 0f, 24f, 10f));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.OEGeneralLastFm));
        builder.makeCustomMaxHeight();
        builder.setView(container);
        builder.setWidth(dp(292));
        builder.setPositiveButton(getString(R.string.Done), (dialog, which) -> {
            String value = editText.getText() == null ? "" : editText.getText().toString().trim();
            if (value.startsWith("@")) {
                value = value.substring(1);
            }
            value = value.toLowerCase();
            if (!TextUtils.isEmpty(value) && !LASTFM_PATTERN.matcher(value).matches()) {
                AndroidUtilities.shakeView(editText);
                return;
            }
            GeneralConfig.lastfmNick.setConfigString(value);
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(lastfmRow);
            }
            dialog.dismiss();
            applyLastFmToProfileMusic(value);
        });
        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            editText.setSelection(editText.length());
            AndroidUtilities.showKeyboard(editText);
        });
        dialog.setDismissDialogByButtons(false);
        showDialog(dialog, d -> AndroidUtilities.hideKeyboard(editText));
    }

    private void applyLastFmToProfileMusic(String nick) {
        AlertDialog progress = getParentActivity() != null
                ? new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER) : null;
        if (progress != null) {
            progress.setCanCancel(false);
            progress.showDelayed(300);
        }
        ProfileMusicStamp.apply(currentAccount, nick, (ok, reason) -> {
            if (progress != null) {
                progress.dismiss();
            }
            if (getParentActivity() == null) {
                return;
            }
            int resId;
            int icon;
            if (ok) {
                resId = R.string.OEGeneralLastFmApplied;
                icon = R.raw.done;
            } else if (reason == ProfileMusicStamp.REASON_NO_MUSIC) {
                resId = R.string.OEGeneralLastFmNoMusic;
                icon = R.raw.info;
            } else if (reason == ProfileMusicStamp.REASON_DOWNLOAD) {
                resId = R.string.OEGeneralLastFmNoFile;
                icon = R.raw.error;
            } else if (reason == ProfileMusicStamp.REASON_UPLOAD) {
                resId = R.string.OEGeneralLastFmNoUpload;
                icon = R.raw.error;
            } else {
                resId = R.string.OEGeneralLastFmFailed;
                icon = R.raw.error;
            }
            BulletinFactory.of(this).createSimpleBulletin(icon, getString(resId)).show();
        });
    }

    private String getProviderName(int providerConstant) {
        int resId;
        if (providerConstant == Translator.providerGoogle) {
            resId = R.string.ProviderGoogleTranslate;
        } else if (providerConstant == Translator.providerYandex) {
            resId = R.string.ProviderYandexTranslate;
        } else if (providerConstant == Translator.providerLingo) {
            resId = R.string.ProviderLingocloud;
        } else if (providerConstant == Translator.providerMicrosoft) {
            resId = R.string.ProviderMicrosoftTranslator;
        } else if (providerConstant == Translator.providerRealMicrosoft) {
            resId = R.string.ProviderRealMicrosoftTranslator;
        } else if (providerConstant == Translator.providerDeepL) {
            resId = R.string.ProviderDeepLTranslate;
        } else if (providerConstant == Translator.providerTelegram) {
            resId = R.string.ProviderTelegramAPI;
        } else if (providerConstant == Translator.providerTranSmart) {
            resId = R.string.ProviderTranSmartTranslate;
        } else if (providerConstant == Translator.providerLLMTranslator) {
            resId = R.string.ProviderLLMTranslator;
        } else {
            return "";
        }
        return getString(resId);
    }

    private String getRestrictedLanguagesValue() {
        HashSet<String> langCodes = RestrictedLanguagesSelectActivity.getRestrictedLanguages();
        if (langCodes.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String lang : langCodes) {
            names.add(NekoXConfig.formatLang(lang));
        }
        return TextUtils.join(", ", names);
    }

    private String getSavePathInfo() {
        String path = NekoConfig.customSavePath.String();
        return TextUtils.isEmpty(path)
                ? getString(R.string.OEGeneralSavePathInfo)
                : LocaleController.formatString(R.string.OEGeneralSavePathInfoFolder, path);
    }

    private boolean osNotificationsEnabled() {
        try {
            return NotificationManagerCompat.from(ApplicationLoader.applicationContext).areNotificationsEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    private boolean batteryUnrestricted() {
        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isIgnoringBatteryOptimizations(ApplicationLoader.applicationContext.getPackageName());
        } catch (Exception e) {
            return true;
        }
    }

    private String standbyBucket() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "n/a";
        }
        try {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                    ApplicationLoader.applicationContext.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return "n/a";
            }
            int bucket = usm.getAppStandbyBucket();
            if (bucket <= 10) return "exempted";
            if (bucket <= 20) return "active";
            if (bucket <= 30) return "working_set";
            if (bucket <= 40) return "frequent";
            if (bucket <= 45) return "rare";
            return "restricted";
        } catch (Exception e) {
            return "n/a";
        }
    }

    private String formatPushStatus() {
        final int type = NaConfig.INSTANCE.getPushServiceType().Int();
        final String name = type == 0 ? "In-App" : type == 2 ? "UnifiedPush" : "FCM";
        if (!osNotificationsEnabled()) {
            return name + " \u00b7 " + getString(R.string.OEGeneralPushStatusBlocked);
        }
        if (TextUtils.isEmpty(SharedConfig.pushString)) {
            return name + " \u00b7 " + getString(R.string.OEGeneralPushStatusNoToken);
        }
        if (!UserConfig.getInstance(UserConfig.selectedAccount).registeredForPush) {
            return name + " \u00b7 " + getString(R.string.OEGeneralPushStatusNotRegistered);
        }
        if (SharedConfig.pushLastReceivedTime <= 0) {
            return name + " \u00b7 " + getString(R.string.OEGeneralPushStatusNothing);
        }
        return name + " \u00b7 " + LocaleController.formatDateTime(
                SharedConfig.pushLastReceivedTime / 1000L, true);
    }

    private void copyPushStatus() {
        final StringBuilder text = new StringBuilder();
        final boolean hasToken = !TextUtils.isEmpty(SharedConfig.pushString);
        text.append("push type: ").append(NaConfig.INSTANCE.getPushServiceType().Int()).append('\n');
        text.append("token: ").append(hasToken
                ? SharedConfig.pushString.length() + " chars" : "none").append('\n');
        if (!hasToken) {
            text.append("token status: ").append(SharedConfig.pushStringStatus).append('\n');
        }
        text.append("token fetch ms: ").append(
                SharedConfig.pushStringGetTimeEnd - SharedConfig.pushStringGetTimeStart).append('\n');
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (config.getClientUserId() != 0) {
                text.append("account ").append(a).append(" registered: ")
                        .append(config.registeredForPush).append('\n');
            }
        }
        text.append("last push: ").append(SharedConfig.pushLastReceivedTime <= 0 ? "never"
                : LocaleController.formatDateTime(SharedConfig.pushLastReceivedTime / 1000L, true)).append('\n');
        text.append("play services: ")
                .append(PushListenerController.getProvider().hasServices()).append('\n');
        text.append("keep alive: ").append(MessagesController
                .getNotificationsSettings(UserConfig.selectedAccount)
                .getBoolean("pushService", false)).append('\n');
        text.append("push connection: ").append(ConnectionsManager
                .getInstance(UserConfig.selectedAccount).isPushConnectionEnabled()).append('\n');
        text.append("os notifications: ").append(osNotificationsEnabled()).append('\n');
        text.append("battery unrestricted: ").append(batteryUnrestricted()).append('\n');
        text.append("standby bucket: ").append(standbyBucket());
        AndroidUtilities.addToClipboard(text.toString());
        BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
    }

    private void openBatteryOptimizationSettings() {
        if (getParentActivity() == null) {
            return;
        }
        final String pkg = ApplicationLoader.applicationContext.getPackageName();
        if (!batteryUnrestricted()) {
            try {
                getParentActivity().startActivity(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + pkg)));
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            getParentActivity().startActivity(
                    new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            BulletinFactory.of(this).createErrorBulletin(
                    getString(R.string.OEGeneralBatteryOptimizationUnavailable)).show();
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == TYPE_SLIDE) {
                SlideChooseView slide = new SlideChooseView(mContext, resourcesProvider);
                slide.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                slide.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                return new org.telegram.ui.Components.RecyclerListView.Holder(slide);
            }
            return super.onCreateViewHolder(parent, viewType);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == mapsHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralMapsHeader));
                    } else if (position == notificationsHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralNotificationsHeader));
                    } else if (position == translateHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralTranslateHeader));
                    } else if (position == generalHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralSectionHeader));
                    } else if (position == speedHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralSpeedHeader));
                    } else if (position == storageHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralStorageHeader));
                    } else if (position == profileHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralProfileHeader));
                    } else if (position == archiveHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralArchiveHeader));
                    }
                    break;
                }
                case TYPE_SLIDE: {
                    SlideChooseView slide = (SlideChooseView) holder.itemView;
                    slide.setCallback(index -> GeneralConfig.downloadSpeedBoost.setConfigInt(index));
                    slide.setOptions(GeneralConfig.downloadSpeedBoost.Int(),
                            getString(R.string.OEGeneralSpeedOff),
                            getString(R.string.OEGeneralSpeedFast),
                            getString(R.string.OEGeneralSpeedUltra));
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == mapDriftingFixRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralMapDriftingFix),
                                NekoConfig.mapDriftingFixForGoogleMaps.Bool(), true);
                    } else if (position == translateButtonRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralTranslateButton),
                                NekoConfig.showTranslate.Bool(), true);
                    } else if (position == translateChatButtonRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralTranslateWholeChat),
                                NaConfig.INSTANCE.getTelegramUIAutoTranslate().Bool(), true);
                    } else if (position == disableNumberRoundingRow) {
                        cell.setTextAndValueAndCheck(getString(R.string.OEGeneralDisableNumberRounding),
                                getString(R.string.OEGeneralDisableNumberRoundingValue),
                                NekoConfig.disableNumberRounding.Bool(), true, true);
                    } else if (position == formatTimeWithSecondsRow) {
                        cell.setTextAndValueAndCheck(getString(R.string.OEGeneralFormatTimeWithSeconds),
                                getString(R.string.OEGeneralFormatTimeWithSecondsValue),
                                NekoConfig.showSeconds.Bool(), true, true);
                    } else if (position == inAppVibrationRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralInAppVibration),
                                !NekoConfig.disableVibration.Bool(), true);
                    } else if (position == filterZalgoRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralFilterZalgo),
                                NaConfig.INSTANCE.getZalgoFilter().Bool(), false);
                    } else if (position == uploadBoostRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralUploadBoost),
                                NekoConfig.uploadBoost.Bool(), false);
                    } else if (position == relativeLastSeenRow) {
                        // Значение строки — живой пример «был(а) 5 минут назад».
                        cell.setTextAndValueAndCheck(getString(R.string.OEGeneralRelativeLastSeen),
                                LocaleController.formatDateOnline(fiveMinutesAgo, new boolean[1]),
                                OpenExteraConfig.relativeLastSeen.Bool(), false, true);
                    } else if (position == hidePhoneRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralHidePhone),
                                NekoConfig.hidePhone.Bool(), true);
                    } else if (position == hideArchiveRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralHideArchive),
                                NaConfig.INSTANCE.getHideArchive().Bool(), true);
                    } else if (position == archiveOnPullRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralArchiveOnPull),
                                NekoConfig.openArchiveOnPull.Bool(), true);
                    } else if (position == disableUnarchiveSwipeRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralDisableUnarchiveSwipe),
                                NaConfig.INSTANCE.getDoNotUnarchiveBySwipe().Bool(), false);
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == pushStatusRow) {
                        cell.setTextAndValue(getString(R.string.OEGeneralPushStatus),
                                formatPushStatus(), true);
                    } else if (position == batteryOptimizationRow) {
                        cell.setTextAndValue(getString(R.string.OEGeneralBatteryOptimization),
                                getString(batteryUnrestricted()
                                        ? R.string.OEGeneralBatteryOptimizationOff
                                        : R.string.OEGeneralBatteryOptimizationOn), false);
                    } else if (position == translationProviderRow) {
                        cell.setTextAndValue(getString(R.string.OEGeneralTranslationProvider),
                                getProviderName(NekoConfig.translationProvider.Int()), true);
                    } else if (position == translateToLangRow) {
                        String lang = NekoConfig.translateToLang.String();
                        String value = TextUtils.isEmpty(lang)
                                ? getString(R.string.OEGeneralTranslationTargetDefault)
                                : NekoXConfig.formatLang(lang);
                        cell.setTextAndValue(getString(R.string.OEGeneralTranslationTarget), value, true);
                    } else if (position == doNotTranslateRow) {
                        cell.setTextAndValue(getString(R.string.OEGeneralDoNotTranslate),
                                getRestrictedLanguagesValue(), true, false);
                    } else if (position == savePathRow) {
                        String path = NekoConfig.customSavePath.String();
                        cell.setTextAndValue(getString(R.string.OEGeneralSavePath),
                                TextUtils.isEmpty(path)
                                        ? getString(R.string.OEGeneralSavePathDefault)
                                        : path,
                                false);
                    } else if (position == mapProviderRow) {
                        cell.setTextAndValue(getString(R.string.OEGeneralMapProvider),
                                mapProviderOptions()[NekoConfig.useOSMDroidMap.Bool() ? 1 : 0], true);
                    } else if (position == mapPreviewRow) {
                        CharSequence[] previews = mapPreviewOptions();
                        int preview = NekoConfig.mapPreviewProvider.Int();
                        cell.setTextAndValue(getString(R.string.OEGeneralMapPreview),
                                previews[preview < 0 || preview >= previews.length ? 0 : preview], false);
                    } else if (position == showIdAndDcRow) {
                        // Выбор из трёх режимов, а не переключатель: Bot API отличается
                        // от Telegram API префиксом -100 у чатов и каналов.
                        int type = NaConfig.INSTANCE.getIdDcType().Int();
                        CharSequence[] options = idOptions();
                        cell.setTextAndValue(getString(R.string.OEGeneralShowIdAndDc),
                                options[type < 0 || type >= options.length ? 0 : type], true);
                    } else if (position == lastfmRow) {
                        String nick = GeneralConfig.lastfmNick();
                        cell.setTextAndValue(getString(R.string.OEGeneralLastFm),
                                TextUtils.isEmpty(nick) ? getString(R.string.OEGeneralLastFmNotSet) : nick,
                                false);
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    boolean bottom = position == notificationsDividerRow;
                    if (position == mapsDividerRow) {
                        cell.setText(getString(R.string.OEGeneralUseOsmMapInfo));
                    } else if (position == notificationsDividerRow) {
                        cell.setText(getString(R.string.OEGeneralNotificationsInfo));
                    } else if (position == translateDividerRow) {
                        cell.setText(getString(R.string.OEGeneralTranslateInfo));
                    } else if (position == generalDividerRow) {
                        cell.setText(LocaleController.formatString(R.string.OEGeneralFilterZalgoInfo,
                                MessageHelper.zalgoFilter(ZALGO_SAMPLE)));
                    } else if (position == speedDividerRow) {
                        cell.setText(getString(R.string.OEGeneralSpeedBoostInfo));
                    } else if (position == storageDividerRow) {
                        cell.setText(getSavePathInfo());
                    } else if (position == profileDividerRow) {
                        cell.setText(getString(R.string.OEGeneralShowIdAndDcInfo));
                    } else if (position == archiveDividerRow) {
                        cell.setText(getString(R.string.OEGeneralDisableUnarchiveSwipeInfo));
                    }
                    cell.setBackground(Theme.getThemedDrawable(mContext,
                            bottom ? R.drawable.greydivider_bottom : R.drawable.greydivider,
                            Theme.key_windowBackgroundGrayShadow));
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == mapsHeaderRow || position == notificationsHeaderRow
                    || position == translateHeaderRow
                    || position == generalHeaderRow
                    || position == speedHeaderRow
                    || position == storageHeaderRow || position == profileHeaderRow
                    || position == archiveHeaderRow) {
                return TYPE_HEADER;
            } else if (position == mapsDividerRow
                    || position == notificationsDividerRow || position == translateDividerRow
                    || position == generalDividerRow
                    || position == speedDividerRow
                    || position == storageDividerRow || position == profileDividerRow
                    || position == archiveDividerRow) {
                return TYPE_INFO_PRIVACY;
            } else if (position == downloadSpeedRow) {
                return TYPE_SLIDE;
            } else if (position == mapProviderRow || position == mapPreviewRow
                    || position == pushStatusRow || position == batteryOptimizationRow
                    || position == translationProviderRow || position == translateToLangRow
                    || position == doNotTranslateRow || position == savePathRow
                    || position == showIdAndDcRow || position == lastfmRow) {
                return TYPE_SETTINGS;
            }
            return TYPE_CHECK;
        }
    }
}
