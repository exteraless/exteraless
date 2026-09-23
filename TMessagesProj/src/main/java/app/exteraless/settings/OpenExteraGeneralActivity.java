package app.exteraless.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.graphics.Typeface;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.NotificationManagerCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UnifiedPushService;
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
import org.telegram.ui.LaunchActivity;

import java.util.regex.Pattern;

import app.exteraless.OpenExteraConfig;
import app.exteraless.general.GeneralConfig;
import app.exteraless.general.GeneralHelper;
import app.exteraless.nowplaying.LastFmNowPlaying;
import app.exteraless.nowplaying.LastFmWebFetcher;
import app.exteraless.nowplaying.ProfileMusicStamp;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.helpers.AppRestartHelper;
import tw.nekomimi.nekogram.helpers.MessageHelper;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import tw.nekomimi.nekogram.utils.AndroidUtil;
import xyz.nextalone.nagram.NaConfig;

/**
 * Экран «General» раздела openExtera — визуально 1:1 повторяет General из exteraGram.
 * Настройки по возможности привязаны к уже существующим ConfigItem NagramX; новые заводятся
 * только там, где в NagramX нет аналога, и помечены «UI-only» (без бэкенда, как в exteraGram 12.9.0).
 */
public class OpenExteraGeneralActivity extends BaseNekoSettingsActivity {

    private static final int TYPE_SLIDE = 100;
    private static final int PUSH_SERVICE_IN_APP = 0;
    private static final int PUSH_SERVICE_UNIFIED = 2;

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

    private int translateButtonRow = -1;
    private int translateChatButtonRow = -1;

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

    private int networkHeaderRow;
    private int useIPv6Row;
    private int dnsTypeRow;
    private int customDoHRow;
    private int networkDividerRow;

    private int storageHeaderRow;
    private int saveToChatSubfolderRow;
    private int savePathRow;
    private int storageDividerRow;

    private int profileHeaderRow;
    private int relativeLastSeenRow;
    private int hidePhoneRow;
    private int showIdAndDcRow;
    private int lastfmRow;
    private int profileDividerRow;

    private int archiveHeaderRow;
    private int sortByUnreadRow;
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
    private int pushServiceTypeRow;
    private int pushGatewayRow;
    private int residentNotificationRow;
    private int notificationBubblesRow;
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

        networkHeaderRow = addRow("networkHeader");
        useIPv6Row = addRow("IPv6");
        dnsTypeRow = addRow("dnsType", "DnsType");
        customDoHRow = NekoConfig.dnsType.Int() == NekoConfig.DNS_TYPE_CUSTOM_DOH ? addRow("customDoH", "CustomDoH") : -1;
        networkDividerRow = addRow();

        storageHeaderRow = addRow("storageHeader");
        saveToChatSubfolderRow = addRow("saveToChatSubfolder", "SaveToChatSubfolder");
        savePathRow = addRow("savePath");
        storageDividerRow = addRow();

        profileHeaderRow = addRow("profileHeader");
        relativeLastSeenRow = addRow("relativeLastSeen");
        hidePhoneRow = addRow("hidePhone");
        showIdAndDcRow = addRow("showIdAndDc");
        lastfmRow = addRow("lastfm");
        profileDividerRow = addRow();

        archiveHeaderRow = addRow("archiveHeader");
        sortByUnreadRow = addRow("sortByUnread", "SortByUnread");
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
        pushServiceTypeRow = addRow("pushServiceType", "PushServiceType");
        int pushServiceType = NaConfig.INSTANCE.getPushServiceType().Int();
        pushGatewayRow = pushServiceType == PUSH_SERVICE_UNIFIED
                ? addRow("pushServiceTypeUnifiedGateway", "PushServiceTypeUnifiedGateway") : -1;
        residentNotificationRow = pushServiceType == PUSH_SERVICE_IN_APP
                ? addRow("pushServiceTypeInAppDialog", "PushServiceTypeInAppDialog") : -1;
        notificationBubblesRow = addRow("disableNotificationBubbles");
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

        if (position == dnsTypeRow) {
            showDnsTypeSelector();
            return;
        }

        if (position == customDoHRow) {
            showCustomDoHDialog();
            return;
        }

        if (position == pushServiceTypeRow) {
            showPushServiceTypeSelector();
            return;
        }

        if (position == pushGatewayRow) {
            showPushGatewayDialog();
            return;
        }

        ConfigItem item = null;
        boolean inverted = false;

        if (position == disableNumberRoundingRow) {
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
        } else if (position == useIPv6Row) {
            item = NekoConfig.useIPv6;
        } else if (position == saveToChatSubfolderRow) {
            item = NaConfig.INSTANCE.getSaveToChatSubfolder();
        } else if (position == sortByUnreadRow) {
            item = NaConfig.INSTANCE.getSortByUnread();
        } else if (position == residentNotificationRow) {
            item = NaConfig.INSTANCE.getPushServiceTypeInAppDialog();
        } else if (position == notificationBubblesRow) {
            item = NekoConfig.disableNotificationBubbles;
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
        if (position == sortByUnreadRow) {
            getMessagesController().sortDialogs(null);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload, true);
        }
        if (position == residentNotificationRow) {
            showRestartHint();
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

    private void showLastFmDebug() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        TextView logView = new TextView(context);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        logView.setTextIsSelectable(true);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(logView, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout firstRow = new LinearLayout(context);
        LinearLayout secondRow = new LinearLayout(context);
        actions.addView(firstRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36));
        actions.addView(secondRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 6, 0, 0));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 20f, 0f, 20f, 10f));
        container.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 320, 24f, 0f, 24f, 0f));

        Runnable[] refresh = new Runnable[1];
        AlertDialog dialog = new AlertDialog.Builder(context, resourcesProvider)
                .setTitle(getString(R.string.OEGeneralLastFmDebug))
                .setView(container)
                .setPositiveButton(getString(R.string.Close), null)
                .create();
        refresh[0] = () -> {
            String nick = GeneralConfig.lastfmNick();
            String text = "nick: " + (TextUtils.isEmpty(nick) ? "-" : nick)
                    + "\nmode: " + (LastFmNowPlaying.isWebMode() ? "webview" : "okhttp")
                    + "\nchallenge passed: " + LastFmWebFetcher.isReady()
                    + "\ncookies: " + LastFmWebFetcher.cookieSummary()
                    + "\n\n" + LastFmNowPlaying.debugLog();
            if (!TextUtils.equals(logView.getText(), text)) {
                boolean atBottom = !scrollView.canScrollVertically(1);
                logView.setText(text);
                if (atBottom) {
                    scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                }
            }
            if (dialog.isShowing()) {
                AndroidUtilities.runOnUIThread(refresh[0], 500);
            }
        };

        addLastFmDebugAction(firstRow, getString(R.string.OEGeneralLastFmDebugTest), () -> {
            String nick = GeneralConfig.lastfmNick();
            if (TextUtils.isEmpty(nick)) {
                LastFmNowPlaying.debug("test: nick is not set");
                return;
            }
            LastFmNowPlaying.forget(nick);
            LastFmNowPlaying.debug("test: " + nick);
            LastFmNowPlaying.request(nick, (resultNick, track) -> {
            });
        });
        addLastFmDebugAction(firstRow, getString(R.string.OEGeneralLastFmDebugMode),
                () -> LastFmNowPlaying.setWebMode(!LastFmNowPlaying.isWebMode()));
        addLastFmDebugAction(firstRow, getString(R.string.OEGeneralLastFmDebugPage), this::showLastFmWebView);
        addLastFmDebugAction(secondRow, getString(R.string.OEGeneralLastFmDebugCookies), LastFmWebFetcher::resetCookies);
        addLastFmDebugAction(secondRow, getString(R.string.OEGeneralLastFmDebugClear), LastFmNowPlaying::clearDebug);
        addLastFmDebugAction(secondRow, getString(R.string.OEGeneralLastFmDebugCopy),
                () -> AndroidUtilities.addToClipboard(logView.getText()));

        showDialog(dialog, d -> AndroidUtilities.cancelRunOnUIThread(refresh[0]));
        refresh[0].run();
    }

    private void addLastFmDebugAction(LinearLayout row, String text, Runnable action) {
        TextView button = new TextView(getParentActivity());
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8),
                Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), 0.1f),
                Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), 0.25f)));
        button.setOnClickListener(v -> action.run());
        row.addView(button, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f,
                row.getChildCount() == 0 ? 0 : 6, 0, 0, 0));
    }

    private void showLastFmWebView() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        WebView webView = LastFmWebFetcher.debugWebView();
        AndroidUtilities.removeFromParent(webView);
        FrameLayout frame = new FrameLayout(context);
        frame.addView(webView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 420));
        AlertDialog dialog = new AlertDialog.Builder(context, resourcesProvider)
                .setTitle(getString(R.string.OEGeneralLastFmDebugPage))
                .setView(frame)
                .setPositiveButton(getString(R.string.Close), null)
                .create();
        showDialog(dialog, d -> AndroidUtilities.removeFromParent(webView));
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

    private void showRestartHint() {
        if (getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(this)
                .createSimpleBulletin(R.raw.info, getString(R.string.OEAppearanceNeedRestart),
                        getString(R.string.OEAppearanceRestartNow),
                        () -> {
                            Activity activity = getParentActivity();
                            if (activity != null) {
                                AppRestartHelper.triggerRebirth(activity,
                                        new Intent(activity, LaunchActivity.class));
                            }
                        })
                .show();
    }

    private CharSequence[] dnsTypeOptions() {
        return new CharSequence[]{
                getString(R.string.MapPreviewProviderTelegram),
                getString(R.string.OEGeneralDnsOverHttps),
                getString(R.string.DnsTypeSystem),
                getString(R.string.CustomDoH)};
    }

    private void showDnsTypeSelector() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.DnsType));
        builder.setItems(dnsTypeOptions(), (dialog, which) -> {
            if (NekoConfig.dnsType.Int() == which) {
                return;
            }
            NekoConfig.dnsType.setConfigInt(which);
            updateRows();
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
            showRestartHint();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showCustomDoHDialog() {
        GeneralHelper.showTextInputDialog(this, getString(R.string.CustomDoH),
                "https://1.0.0.1/dns-query, https://...", NekoConfig.customDoH.String(), value -> {
                    NekoConfig.customDoH.setConfigString(value.trim());
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(customDoHRow);
                    }
                });
    }

    private CharSequence[] pushServiceTypeOptions() {
        return new CharSequence[]{
                getString(R.string.PushServiceTypeInApp),
                getString(R.string.PushServiceTypeFCM),
                getString(R.string.PushServiceTypeUnified),
                getString(R.string.PushServiceTypeMicroG)};
    }

    private void showPushServiceTypeSelector() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.PushServiceType));
        builder.setItems(pushServiceTypeOptions(), (dialog, which) -> {
            if (NaConfig.INSTANCE.getPushServiceType().Int() == which) {
                return;
            }
            NaConfig.INSTANCE.getPushServiceType().setConfigInt(which);
            PushListenerController.reconcilePushRegistration();
            if (which == PUSH_SERVICE_IN_APP) {
                AndroidUtil.setPushService(false);
            } else {
                NaConfig.INSTANCE.getPushServiceTypeInAppDialog().setConfigBool(false);
            }
            updateRows();
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
            showRestartHint();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showPushGatewayDialog() {
        ConfigItem gateway = NaConfig.INSTANCE.getPushServiceTypeUnifiedGateway();
        GeneralHelper.showTextInputDialog(this, getString(R.string.PushServiceTypeUnifiedGateway),
                UnifiedPushService.UP_GATEWAY_DEFAULT, gateway.String(), value -> {
                    String trimmed = value.trim();
                    gateway.setConfigString(trimmed.isEmpty() ? (String) gateway.defaultValue : trimmed);
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(pushGatewayRow);
                    }
                    showRestartHint();
                });
    }

    private void showUnifiedPushStatistics() {
        if (getParentActivity() == null) {
            return;
        }
        long received = UnifiedPushService.getNumOfReceivedNotifications();
        String text = received == 0
                ? getString(R.string.UnifiedPushNeverReceivedNotifications)
                : LocaleController.formatString(R.string.UnifiedPushLastReceivedNotification,
                        (SystemClock.elapsedRealtime() - UnifiedPushService.getLastReceivedNotification()) / 1000,
                        received);
        text += "\n\n" + LocaleController.formatString(R.string.UnifiedPushCurrentEndpoint, SharedConfig.pushString);
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.PushServiceTypeUnified))
                .setMessage(text)
                .setPositiveButton(getString(R.string.OK), null)
                .create());
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        if (position == pushGatewayRow) {
            showUnifiedPushStatistics();
            return true;
        }
        if (position == lastfmRow) {
            showLastFmDebug();
            return true;
        }
        return super.onItemLongClick(view, position, x, y);
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
                    } else if (position == networkHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralNetworkHeader));
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
                    } else if (position == useIPv6Row) {
                        cell.setTextAndCheck(getString(R.string.IPv6),
                                NekoConfig.useIPv6.Bool(), true);
                    } else if (position == saveToChatSubfolderRow) {
                        cell.setTextAndCheck(getString(R.string.SaveToChatSubfolder),
                                NaConfig.INSTANCE.getSaveToChatSubfolder().Bool(), true);
                    } else if (position == sortByUnreadRow) {
                        cell.setTextAndCheck(getString(R.string.SortByUnread),
                                NaConfig.INSTANCE.getSortByUnread().Bool(), true);
                    } else if (position == residentNotificationRow) {
                        cell.setTextAndCheck(getString(R.string.PushServiceTypeInAppDialog),
                                NaConfig.INSTANCE.getPushServiceTypeInAppDialog().Bool(), true);
                    } else if (position == notificationBubblesRow) {
                        cell.setTextAndCheck(getString(R.string.disableNotificationBubbles),
                                NekoConfig.disableNotificationBubbles.Bool(), true);
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
                    } else if (position == dnsTypeRow) {
                        CharSequence[] options = dnsTypeOptions();
                        int type = NekoConfig.dnsType.Int();
                        cell.setTextAndValue(getString(R.string.DnsType),
                                options[type < 0 || type >= options.length ? 0 : type], customDoHRow != -1);
                    } else if (position == customDoHRow) {
                        cell.setTextAndValue(getString(R.string.CustomDoH), NekoConfig.customDoH.String(), false);
                    } else if (position == pushServiceTypeRow) {
                        CharSequence[] options = pushServiceTypeOptions();
                        int type = NaConfig.INSTANCE.getPushServiceType().Int();
                        cell.setTextAndValue(getString(R.string.PushServiceType),
                                options[type < 0 || type >= options.length ? 0 : type], true);
                    } else if (position == pushGatewayRow) {
                        String gateway = NaConfig.INSTANCE.getPushServiceTypeUnifiedGateway().String();
                        cell.setTextAndValue(getString(R.string.PushServiceTypeUnifiedGateway),
                                TextUtils.isEmpty(gateway) ? UnifiedPushService.UP_GATEWAY_DEFAULT : gateway, true);
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
                    || position == generalHeaderRow || position == networkHeaderRow
                    || position == speedHeaderRow
                    || position == storageHeaderRow || position == profileHeaderRow
                    || position == archiveHeaderRow) {
                return TYPE_HEADER;
            } else if (position == mapsDividerRow
                    || position == notificationsDividerRow
                    || position == generalDividerRow
                    || position == speedDividerRow
                    || position == storageDividerRow || position == profileDividerRow
                    || position == archiveDividerRow) {
                return TYPE_INFO_PRIVACY;
            } else if (position == networkDividerRow) {
                return TYPE_SHADOW;
            } else if (position == downloadSpeedRow) {
                return TYPE_SLIDE;
            } else if (position == dnsTypeRow || position == customDoHRow
                    || position == pushServiceTypeRow || position == pushGatewayRow) {
                return TYPE_SETTINGS;
            } else if (position == mapProviderRow || position == mapPreviewRow
                    || position == pushStatusRow || position == batteryOptimizationRow
                    || position == savePathRow
                    || position == showIdAndDcRow || position == lastfmRow) {
                return TYPE_SETTINGS;
            }
            return TYPE_CHECK;
        }
    }
}
