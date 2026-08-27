package app.exteraless.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.CountDownTimer;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;


import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.messages.AyuMessagesController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

import app.exteraless.backup.EtgBackup;
import app.exteraless.backup.EtgBackupUi;
import app.exteraless.drawer.MainMenuItem;
import app.exteraless.drawer.MainMenuLayout;
import app.exteraless.general.GeneralConfig;
import app.exteraless.pillstack.PillStackConfig;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import app.exteraless.pillstack.PillType;
import app.exteraless.general.GeneralHelper;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.filters.RegexFiltersSettingActivity;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.settings.GhostModeActivity;
import xyz.nextalone.nagram.NaConfig;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import tw.nekomimi.nekogram.utils.AlertUtil;
import tw.nekomimi.nekogram.utils.AndroidUtil;

/**
 * Экран «Other» раздела openExtera — повторяет Other из exteraGram
 * (секция Google + управление настройками).
 */
public class OpenExteraOtherActivity extends BaseNekoSettingsActivity {

    private static final int TYPE_EXPANDABLE_SWITCH = 104;
    private static final int TYPE_ROUND_CHECK = 105;
    private static final int SAVE_MEDIA_TOTAL = 5;

    /** Кнопка удаления остаётся заблокированной 30 секунд. */
    private static final long DELETE_ACCOUNT_DELAY = 30_000L;

    private static final int ETG_IMPORT_REQUEST_CODE = 22;

    /** Булевы функции секции; режим призрака гасится отдельно — он не один ConfigItem. */
    private static final ConfigItem[] AYU_FEATURE_CONFIGS = {
            NaConfig.INSTANCE.getRegexFiltersEnabled(),
            NaConfig.INSTANCE.getSaveLocalLastSeen(),
            NaConfig.INSTANCE.getEnableSaveDeletedMessages(),
            NaConfig.INSTANCE.getEnableSaveEditsHistory(),
            NaConfig.INSTANCE.getMessageSavingSaveMedia(),
            NaConfig.INSTANCE.getSaveDeletedMessageForBotUser(),
            NaConfig.INSTANCE.getSaveDeletedMessageForBot(),
            NaConfig.INSTANCE.getTranslucentDeletedMessages(),
            NaConfig.INSTANCE.getUseDeletedIcon(),
            NaConfig.INSTANCE.getForwardProtectedAsCopy(),
    };

    private int googleHeaderRow;
    private int crashReportsRow;
    private int googleDividerRow;
    private int nagramHeaderRow;
    private int nagramSettingsRow;
    private int ayuMomentsRow;
    private int ayuGhostRow;
    private int ayuRegexRow;
    private int ayuSaveLastSeenRow;
    private int ayuSaveDeletedRow;
    private int ayuSaveEditsRow;
    private int ayuSaveMediaRow;
    private int saveMediaPrivateChatsRow;
    private int saveMediaPublicChannelsRow;
    private int saveMediaPrivateChannelsRow;
    private int saveMediaPublicGroupsRow;
    private int saveMediaPrivateGroupsRow;
    private boolean saveMediaExpanded;
    private int ayuBotUserRow;
    private int ayuBotChatRow;
    private int ayuTranslucentRow;
    private int ayuDeletedIconRow;
    private int ayuDeletedMarkRow;
    private int ayuForwardProtectedRow;
    private int ayuClearDbRow;
    private int nagramDividerRow;

    private int exportEtgRow;
    private int importEtgRow;
    private int etgDividerRow;
    private int resetSettingsRow;
    private int deleteAccountRow;
    private int bottomDividerRow;

    private CountDownTimer deleteAccountTimer;

    public OpenExteraOtherActivity() {
        super();
    }

    @Override
    public boolean onFragmentCreate() {
        GeneralConfig.init();
        if (GeneralConfig.showAyuMoments()) {
            AyuData.loadSizes(this::refreshAyuDataSize);
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        cancelDeleteAccountTimer();
        super.onFragmentDestroy();
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        // Секции Google (Analytics + Crashlytics) здесь больше нет. Отправлять
        // было нечего: shouldEnableCrashlytics требует applicationId
        // «nu.gpu.nagram», а у нас com.exteraless.app — переключатель стоял
        // мёртвым. На его месте вход в настройки NagramX, выключенный по
        // умолчанию.
        googleHeaderRow = addRow("googleHeader");
        crashReportsRow = addRow("crashReports");
        googleDividerRow = addRow();

        nagramHeaderRow = addRow("nagramHeader");
        nagramSettingsRow = addRow("nagramSettings");
        ayuMomentsRow = addRow("ayuMoments");
        ayuGhostRow = ayuRegexRow = ayuSaveLastSeenRow = ayuSaveDeletedRow = ayuSaveEditsRow = -1;
        ayuSaveMediaRow = ayuBotUserRow = ayuBotChatRow = ayuTranslucentRow = -1;
        saveMediaPrivateChatsRow = saveMediaPublicChannelsRow = saveMediaPrivateChannelsRow = -1;
        saveMediaPublicGroupsRow = saveMediaPrivateGroupsRow = -1;
        ayuDeletedIconRow = ayuDeletedMarkRow = ayuForwardProtectedRow = ayuClearDbRow = -1;
        if (GeneralConfig.showAyuMoments()) {
            ayuGhostRow = addRow("ayuGhost");
            ayuRegexRow = addRow(NaConfig.INSTANCE.getRegexFiltersEnabled().getKey());
            ayuSaveLastSeenRow = addRow(NaConfig.INSTANCE.getSaveLocalLastSeen().getKey());
            ayuSaveDeletedRow = addRow(NaConfig.INSTANCE.getEnableSaveDeletedMessages().getKey());
            ayuSaveEditsRow = addRow(NaConfig.INSTANCE.getEnableSaveEditsHistory().getKey());
            // Подчинённые строки живут только при включённом сохранении удалённых —
            // тот же порядок, что у NekoExperimentalSettingsActivity.checkSaveDeletedRows.
            if (NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
                ayuSaveMediaRow = addRow(NaConfig.INSTANCE.getMessageSavingSaveMedia().getKey());
                if (saveMediaExpanded) {
                    saveMediaPrivateChatsRow = addRow();
                    saveMediaPublicChannelsRow = addRow();
                    saveMediaPrivateChannelsRow = addRow();
                    saveMediaPublicGroupsRow = addRow();
                    saveMediaPrivateGroupsRow = addRow();
                }
                ayuBotUserRow = addRow(NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().getKey());
                if (NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().Bool()) {
                    ayuBotChatRow = addRow(NaConfig.INSTANCE.getSaveDeletedMessageForBot().getKey());
                }
                ayuTranslucentRow = addRow(NaConfig.INSTANCE.getTranslucentDeletedMessages().getKey());
                ayuDeletedIconRow = addRow(NaConfig.INSTANCE.getUseDeletedIcon().getKey());
                if (!NaConfig.INSTANCE.getUseDeletedIcon().Bool()) {
                    ayuDeletedMarkRow = addRow(NaConfig.INSTANCE.getCustomDeletedMark().getKey());
                }
            }
            ayuForwardProtectedRow = addRow(NaConfig.INSTANCE.getForwardProtectedAsCopy().getKey());
            ayuClearDbRow = addRow("ayuClearDatabase");
        }
        nagramDividerRow = addRow();

        exportEtgRow = addRow("exportEtgSettings");
        importEtgRow = addRow("importEtgSettings");
        etgDividerRow = addRow();

        resetSettingsRow = addRow("resetSettings");
        deleteAccountRow = addRow("deleteAccount");
        bottomDividerRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.OEGeneralOtherTitle);
    }

    @Override
    public int getSearchGuid() {
        return 23000;
    }

    @Override
    public int getSearchIcon() {
        return R.drawable.msg_fave;
    }

    @Override
    public String getSearchPrefix() {
        return "OEGeneral";
    }

    @Override
    protected String getKey() {
        return "exteraless_other";
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == crashReportsRow) {
            boolean enabled = GeneralConfig.crashReports.toggleConfigBool();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(
                    AndroidUtil.shouldEnableCrashlytics());
        } else if (position == nagramSettingsRow) {
            boolean enabled = GeneralConfig.showNagramSettings.toggleConfigBool();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            // Строка появляется и исчезает в общем списке настроек, а он уже
            // построен и на свои уведомления её не пересобирает — поэтому
            // пересобираем вьюхи стека целиком, как это делают остальные
            // настройки, меняющие чужие экраны.
            if (getParentLayout() != null) {
                getParentLayout().rebuildAllFragmentViews(false, false);
            }
        } else if (position == ayuMomentsRow) {
            if (GeneralConfig.showAyuMoments()) {
                showHideAyuMomentsDialog(view);
            } else {
                GeneralConfig.showAyuMoments.setConfigBool(true);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(true);
                }
                rebuildRowsAndNotify();
                AyuData.loadSizes(this::refreshAyuDataSize);
            }
        } else if (position == ayuGhostRow) {
            presentFragment(new GhostModeActivity());
        } else if (position == ayuRegexRow) {
            // Как в эталоне: тап по тексту ведёт в список фильтров, тап по переключателю — включает.
            boolean onSwitch = LocaleController.isRTL
                    ? x <= AndroidUtilities.dp(76)
                    : x >= view.getMeasuredWidth() - AndroidUtilities.dp(76);
            if (onSwitch) {
                toggleAyuConfig(view, NaConfig.INSTANCE.getRegexFiltersEnabled(), false);
            } else {
                presentFragment(new RegexFiltersSettingActivity());
            }
        } else if (position == ayuSaveLastSeenRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveLocalLastSeen(), false);
        } else if (position == ayuSaveDeletedRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getEnableSaveDeletedMessages(), true);
        } else if (position == ayuSaveEditsRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getEnableSaveEditsHistory(), false);
        } else if (position == ayuSaveMediaRow) {
            saveMediaExpanded = !saveMediaExpanded;
            rebuildRowsAndNotify();
        } else if (position == saveMediaPrivateChatsRow) {
            toggleSaveMediaKind(view, NaConfig.INSTANCE.getSaveMediaInPrivateChats());
        } else if (position == saveMediaPublicChannelsRow) {
            toggleSaveMediaKind(view, NaConfig.INSTANCE.getSaveMediaInPublicChannels());
        } else if (position == saveMediaPrivateChannelsRow) {
            toggleSaveMediaKind(view, NaConfig.INSTANCE.getSaveMediaInPrivateChannels());
        } else if (position == saveMediaPublicGroupsRow) {
            toggleSaveMediaKind(view, NaConfig.INSTANCE.getSaveMediaInPublicGroups());
        } else if (position == saveMediaPrivateGroupsRow) {
            toggleSaveMediaKind(view, NaConfig.INSTANCE.getSaveMediaInPrivateGroups());
        } else if (position == ayuBotUserRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveDeletedMessageForBotUser(), true);
        } else if (position == ayuBotChatRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveDeletedMessageForBot(), false);
        } else if (position == ayuTranslucentRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getTranslucentDeletedMessages(), false);
        } else if (position == ayuDeletedIconRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getUseDeletedIcon(), true);
        } else if (position == ayuDeletedMarkRow) {
            showDeletedMarkDialog();
        } else if (position == ayuForwardProtectedRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getForwardProtectedAsCopy(), false);
        } else if (position == ayuClearDbRow) {
            showClearAyuDatabaseDialog();
        } else if (position == exportEtgRow) {
            exportEtgSettings();
        } else if (position == importEtgRow) {
            openEtgFilePicker();
        } else if (position == resetSettingsRow) {
            showResetSettingsDialog();
        } else if (position == deleteAccountRow) {
            showDeleteAccountDialog();
        }
    }

    /**
     * Скрыть секцию — не то же, что выключить функции: сохранение продолжает писать
     * в базу, даже когда строк не видно. Поэтому спрашиваем оба вопроса сразу.
     */
    private void showHideAyuMomentsDialog(View row) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        CheckBoxCell disableCell = new CheckBoxCell(context, 1, getResourceProvider());
        disableCell.setText(getString(R.string.OEGeneralAyuMomentsDisableAll), "", false, false);
        disableCell.setBackground(Theme.getSelectorDrawable(false));
        disableCell.setOnClickListener(v -> disableCell.setChecked(!disableCell.isChecked(), true));
        content.addView(disableCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        CheckBoxCell wipeCell = new CheckBoxCell(context, 1, getResourceProvider());
        wipeCell.setText(getString(R.string.OEGeneralAyuMomentsWipe),
                AyuData.totalSize > 0 ? AndroidUtilities.formatFileSize(AyuData.totalSize) : "", false, false);
        wipeCell.setBackground(Theme.getSelectorDrawable(false));
        wipeCell.setOnClickListener(v -> wipeCell.setChecked(!wipeCell.isChecked(), true));
        content.addView(wipeCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.OEGeneralAyuMomentsHideTitle))
                .setMessage(getString(R.string.OEGeneralAyuMomentsHideMessage))
                .setView(content)
                .setPositiveButton(getString(R.string.Hide), (dialog, which) ->
                        hideAyuMoments(row, disableCell.isChecked(), wipeCell.isChecked()))
                .setNegativeButton(getString(R.string.Cancel), (d, w) -> d.dismiss())
                .show();
    }

    /**
     * Убирает быстрые переключатели призрака. Раскладку меню трогаем только если она
     * уже своя: пункта нет в раскладке по умолчанию, а запись без нужды сделала бы
     * её кастомной и заморозила текущий состав.
     */
    private void removeGhostShortcuts() {
        PillStackConfig.setPillActive(PillType.GHOST.id, false);
        PillStackConfig.savePillsLayout();

        final int ghostId = MainMenuItem.GHOST_MODE.getId();
        final ArrayList<Integer> layout = MainMenuLayout.getLayoutMutable();
        if (layout.remove((Integer) ghostId)) {
            final ArrayList<Integer> hidden = MainMenuLayout.getHiddenItemsMutable();
            if (!hidden.contains(ghostId)) {
                hidden.add(ghostId);
            }
            MainMenuLayout.save(layout, hidden);
        }
    }

    private void hideAyuMoments(View row, boolean disableAll, boolean wipeDatabase) {
        if (disableAll) {
            for (ConfigItem config : AYU_FEATURE_CONFIGS) {
                if (config.Bool()) {
                    config.setConfigBool(false);
                }
            }
            // Через toggle, а не setGhostMode: он же отправляет пакет онлайна,
            // без которого мы останемся невидимыми уже без своего ведома.
            if (NekoConfig.isGhostModeActive()) {
                NekoConfig.toggleGhostMode();
                NotificationCenter.getInstance(currentAccount)
                        .postNotificationName(NotificationCenter.mainUserInfoChanged);
            }
            removeGhostShortcuts();
        }
        GeneralConfig.showAyuMoments.setConfigBool(false);
        if (row instanceof TextCheckCell) {
            ((TextCheckCell) row).setChecked(false);
        }
        rebuildRowsAndNotify();

        if (wipeDatabase) {
            Context context = getParentActivity();
            if (context == null) {
                return;
            }
            AlertDialog progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
            progressDialog.setCanCancel(false);
            progressDialog.show();
            Utilities.globalQueue.postRunnable(() -> {
                AyuMessagesController.getInstance().clean();
                AndroidUtilities.runOnUIThread(() -> {
                    progressDialog.dismiss();
                    BulletinFactory.of(this)
                            .createSimpleBulletin(R.raw.done, getString(R.string.ClearMessageDatabaseNotification))
                            .show();
                });
                AyuData.loadSizes(this::refreshAyuDataSize);
            });
        }
    }

    /**
     * Пересобрать список строк: базовый класс зовёт updateRows() только в onFragmentCreate,
     * а состав секции AyuMoments зависит от самих настроек.
     */
    private void rebuildRowsAndNotify() {
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    /** Заголовок берётся из ключа настройки — так же, как это делают ячейки NagramX. */
    private void bindAyuCheck(TextCheckCell cell, ConfigItem config, boolean divider) {
        cell.setTextAndCheck(getString(config.getKey()), config.Bool(), divider);
    }

    /** Переключает пункт AyuMoments; rebuild нужен там, где от него зависит состав строк. */
    private void toggleAyuConfig(View view, ConfigItem config, boolean affectsRows) {
        boolean enabled = config.toggleConfigBool();
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(enabled);
        }
        if (affectsRows) {
            rebuildRowsAndNotify();
        }
    }

    private void toggleSaveMediaKind(View view, ConfigItem config) {
        boolean enabled = config.toggleConfigBool();
        if (view instanceof CheckBoxCell) {
            ((CheckBoxCell) view).setChecked(enabled, true);
        }
        if (listAdapter != null && ayuSaveMediaRow >= 0) {
            listAdapter.notifyItemChanged(ayuSaveMediaRow);
        }
    }

    private ConfigItem[] saveMediaKinds() {
        return new ConfigItem[]{
                NaConfig.INSTANCE.getSaveMediaInPrivateChats(),
                NaConfig.INSTANCE.getSaveMediaInPublicChannels(),
                NaConfig.INSTANCE.getSaveMediaInPrivateChannels(),
                NaConfig.INSTANCE.getSaveMediaInPublicGroups(),
                NaConfig.INSTANCE.getSaveMediaInPrivateGroups(),
        };
    }

    private int saveMediaSelectedCount() {
        int selected = 0;
        for (ConfigItem config : saveMediaKinds()) {
            if (config.Bool()) {
                selected++;
            }
        }
        return selected;
    }

    private void toggleSaveMedia() {
        ConfigItem master = NaConfig.INSTANCE.getMessageSavingSaveMedia();
        boolean enable = !master.Bool();
        master.setConfigBool(enable);
        if (enable && saveMediaSelectedCount() == 0) {
            for (ConfigItem config : saveMediaKinds()) {
                config.setConfigBool(true);
            }
        }
        rebuildRowsAndNotify();
    }

    private static String ratio(int selected, int total) {
        return String.format(Locale.getDefault(), "%d/%d", selected, total);
    }

    private void refreshAyuDataSize() {
        if (ayuClearDbRow >= 0 && listAdapter != null) {
            listAdapter.notifyItemChanged(ayuClearDbRow);
        }
    }

    private void showDeletedMarkDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        ConfigItem config = NaConfig.INSTANCE.getCustomDeletedMark();

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(config.getKey()));

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
        editText.setPadding(0, 0, 0, AndroidUtilities.dp(6));
        editText.setText(config.String());
        editText.requestFocus();
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(10), 0));

        builder.setPositiveButton(getString(R.string.OK), null);
        builder.setView(linearLayout);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            config.setConfigString(editText.getText().toString());
            dialog.dismiss();
            if (listAdapter != null && ayuDeletedMarkRow >= 0) {
                listAdapter.notifyItemChanged(ayuDeletedMarkRow);
            }
        }));
        showDialog(dialog);
    }

    private void showClearAyuDatabaseDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.ClearMessageDatabase))
                .setMessage(getString(R.string.AreYouSure))
                .setPositiveButton(getString(R.string.Clear), (dialog, which) -> {
                    AlertDialog progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
                    progressDialog.setCanCancel(false);
                    progressDialog.show();
                    Utilities.globalQueue.postRunnable(() -> {
                        AyuMessagesController.getInstance().clean();
                        AndroidUtilities.runOnUIThread(() -> {
                            progressDialog.dismiss();
                            BulletinFactory.of(this)
                                    .createSimpleBulletin(R.raw.done, getString(R.string.ClearMessageDatabaseNotification))
                                    .show();
                        });
                        AyuData.loadSizes(this::refreshAyuDataSize);
                    });
                })
                .setNegativeButton(getString(R.string.Cancel), (d, w) -> d.dismiss())
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
    }

    private void exportEtgSettings() {
        EtgBackupUi.export(this);
    }

    private void openEtgFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, ETG_IMPORT_REQUEST_CODE);
        } catch (Exception e) {
            AlertUtil.showSimpleAlert(getParentActivity(), e);
        }
    }

    /**
     * Выбранный файл копируется в кэш под своим расширением: провайдер отдаёт content://,
     * а читать бэкап удобнее обычным файлом.
     */
    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != ETG_IMPORT_REQUEST_CODE) {
            super.onActivityResultFragment(requestCode, resultCode, data);
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        File file = new File(AndroidUtilities.getCacheDir(),
                UUID.randomUUID().toString().replace("-", "") + EtgBackup.EXTENSION);
        try (InputStream input = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return;
            }
            try (OutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            }
        } catch (Exception e) {
            AlertUtil.showSimpleAlert(getParentActivity(), e);
            return;
        }
        EtgBackupUi.confirmImport(this, file);
    }

    private void showResetSettingsDialog() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertUtil.showConfirm(activity,
                getString(R.string.OEGeneralResetSettings),
                getString(R.string.OEGeneralResetSettingsInfo),
                R.drawable.msg_reset,
                getString(R.string.OEGeneralResetSettings),
                true,
                () -> {
                    GeneralHelper.resetSettings();
                    LocaleController.getInstance().recreateFormatters();
                    // Ресурсы темы надо перечитать: иначе не подхватятся радиусы и цвета,
                    // сброшенные вместе с настройками.
                    Theme.reloadAllResources(activity);
                    if (getParentLayout() != null) {
                        getParentLayout().rebuildAllFragmentViews(false, false);
                    }
                    getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
                    getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
                    getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload, true);
                    // У exteraGram бюллетень именно «ошибочный» (красный) — это предупреждение, а не успех.
                    BulletinFactory.of(OpenExteraOtherActivity.this)
                            .createErrorBulletin(getString(R.string.OEGeneralResetSettingsDone))
                            .show();
                });
    }

    /**
     * Удаление аккаунта: подтверждение с обратным отсчётом, затем
     * TL_account.deleteAccount и локальный выход.
     */
    private void showDeleteAccountDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.OEGeneralDeleteAccount));
        builder.setMessage(getString(R.string.TosDeclineDeleteAccount));
        builder.setPositiveButton(getString(R.string.Deactivate), (dialog, which) -> deleteAccount());
        builder.setNegativeButton(getString(R.string.Cancel), null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            View button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (!(button instanceof TextView)) {
                return;
            }
            TextView textView = (TextView) button;
            textView.setTextColor(Theme.getColor(Theme.key_text_RedBold));
            textView.setEnabled(false);
            CharSequence text = textView.getText();
            cancelDeleteAccountTimer();
            deleteAccountTimer = new CountDownTimer(DELETE_ACCOUNT_DELAY, 100L) {
                @Override
                public void onTick(long millisUntilFinished) {
                    textView.setText(String.format(Locale.getDefault(), "%s • %d",
                            text, (millisUntilFinished / 1000) + 1));
                }

                @Override
                public void onFinish() {
                    textView.setText(text);
                    textView.setEnabled(true);
                }
            };
            deleteAccountTimer.start();
        });
        // Слушателя ставим через showDialog: BaseFragment.showDialog (:834) затирает
        // тот, что назначен диалогу напрямую.
        showDialog(dialog, d -> cancelDeleteAccountTimer());
    }

    private void cancelDeleteAccountTimer() {
        if (deleteAccountTimer != null) {
            deleteAccountTimer.cancel();
            deleteAccountTimer = null;
        }
    }

    private void deleteAccount() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        progressDialog.show();
        // Пауза exteraGram перед запросом: спиннер успевает появиться, а не мигнуть.
        AndroidUtilities.runOnUIThread(() -> {
            TL_account.deleteAccount req = new TL_account.deleteAccount();
            req.reason = "openExtera";
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                try {
                    progressDialog.dismiss();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                if (response instanceof TLRPC.TL_boolTrue) {
                    getMessagesController().performLogout(0);
                    return;
                }
                if (error != null && error.code == -1000) {
                    return;
                }
                if (getParentActivity() == null) {
                    return;
                }
                String message = getString(R.string.ErrorOccurred);
                if (error != null) {
                    message = message + "\n" + error.text;
                }
                AlertDialog.Builder alert = new AlertDialog.Builder(getParentActivity());
                alert.setTitle(getString(R.string.AppName));
                alert.setMessage(message);
                alert.setPositiveButton(getString(R.string.OK), null);
                showDialog(alert.create());
            }));
        }, 500);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_EXPANDABLE_SWITCH:
                    view = new TextCheckCell2(mContext);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_ROUND_CHECK: {
                    CheckBoxCell checkBoxCell = new CheckBoxCell(mContext, 4, 21, resourcesProvider);
                    checkBoxCell.getCheckBoxRound().setColor(Theme.key_switch2TrackChecked,
                            Theme.key_radioBackground, Theme.key_checkboxCheck);
                    checkBoxCell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view = checkBoxCell;
                    break;
                }
                default:
                    return super.onCreateViewHolder(parent, viewType);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            if (type == TYPE_EXPANDABLE_SWITCH || type == TYPE_ROUND_CHECK) {
                return true;
            }
            return super.isEnabled(holder);
        }

        @Override
        protected boolean isSectionContent(int viewType) {
            if (viewType == TYPE_EXPANDABLE_SWITCH || viewType == TYPE_ROUND_CHECK) {
                return true;
            }
            return super.isSectionContent(viewType);
        }

        private void bindSaveMediaGroup(TextCheckCell2 cell) {
            cell.useStandardSwitchColors();
            int selected = saveMediaSelectedCount();
            cell.setTextAndCheck(getString(NaConfig.INSTANCE.getMessageSavingSaveMedia().getKey()),
                    NaConfig.INSTANCE.getMessageSavingSaveMedia().Bool(), saveMediaExpanded);
            cell.setCollapseArrow(ratio(selected, SAVE_MEDIA_TOTAL), !saveMediaExpanded,
                    OpenExteraOtherActivity.this::toggleSaveMedia);
        }

        private void bindSaveMediaKind(CheckBoxCell cell, int position) {
            if (position == saveMediaPrivateChatsRow) {
                cell.setText(getString(R.string.MessageSavingSaveMediaInPrivateChats), "",
                        NaConfig.INSTANCE.getSaveMediaInPrivateChats().Bool(), true, true);
            } else if (position == saveMediaPublicChannelsRow) {
                cell.setText(getString(R.string.MessageSavingSaveMediaInPublicChannels), "",
                        NaConfig.INSTANCE.getSaveMediaInPublicChannels().Bool(), true, true);
            } else if (position == saveMediaPrivateChannelsRow) {
                cell.setText(getString(R.string.MessageSavingSaveMediaInPrivateChannels), "",
                        NaConfig.INSTANCE.getSaveMediaInPrivateChannels().Bool(), true, true);
            } else if (position == saveMediaPublicGroupsRow) {
                cell.setText(getString(R.string.MessageSavingSaveMediaInPublicGroups), "",
                        NaConfig.INSTANCE.getSaveMediaInPublicGroups().Bool(), true, true);
            } else if (position == saveMediaPrivateGroupsRow) {
                cell.setText(getString(R.string.MessageSavingSaveMediaInPrivateGroups), "",
                        NaConfig.INSTANCE.getSaveMediaInPrivateGroups().Bool(), true, true);
            }
            cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_EXPANDABLE_SWITCH:
                    bindSaveMediaGroup((TextCheckCell2) holder.itemView);
                    break;
                case TYPE_ROUND_CHECK:
                    bindSaveMediaKind((CheckBoxCell) holder.itemView, position);
                    break;
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == nagramHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralNagramHeader));
                    } else if (position == googleHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralGoogleHeader));
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == crashReportsRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralCrashReports),
                                GeneralConfig.crashReports(), false);
                        cell.setIcon(R.drawable.msg_report);
                    } else if (position == nagramSettingsRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralNagramSettings),
                                GeneralConfig.showNagramSettings(), true);
                        // setIcon после setTextAndCheck — тот сбрасывает отступы текста.
                        cell.setIcon(R.drawable.msg_settings);
                    } else if (position == ayuMomentsRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralAyuMoments),
                                GeneralConfig.showAyuMoments(), GeneralConfig.showAyuMoments());
                        cell.setIcon(R.drawable.ayu_ghost);
                    } else {
                        cell.setIcon(0);
                        if (position == ayuRegexRow) {
                            cell.setTextAndValueAndCheck(
                                    getString(NaConfig.INSTANCE.getRegexFiltersEnabled().getKey()),
                                    getString(R.string.RegexFiltersNotice),
                                    NaConfig.INSTANCE.getRegexFiltersEnabled().Bool(), true, true);
                        } else if (position == ayuSaveLastSeenRow) {
                            bindAyuCheck(cell, NaConfig.INSTANCE.getSaveLocalLastSeen(), true);
                        } else if (position == ayuSaveDeletedRow) {
                            bindAyuCheck(cell, NaConfig.INSTANCE.getEnableSaveDeletedMessages(), true);
                        } else if (position == ayuSaveEditsRow) {
                            bindAyuCheck(cell, NaConfig.INSTANCE.getEnableSaveEditsHistory(), true);
                        } else if (position == ayuBotUserRow) {
                            bindAyuCheck(cell, NaConfig.INSTANCE.getSaveDeletedMessageForBotUser(), true);
                        } else if (position == ayuBotChatRow) {
                            bindAyuCheck(cell, NaConfig.INSTANCE.getSaveDeletedMessageForBot(), true);
                        } else if (position == ayuTranslucentRow) {
                            bindAyuCheck(cell, NaConfig.INSTANCE.getTranslucentDeletedMessages(), true);
                        } else if (position == ayuDeletedIconRow) {
                            bindAyuCheck(cell, NaConfig.INSTANCE.getUseDeletedIcon(), true);
                        } else if (position == ayuForwardProtectedRow) {
                            cell.setTextAndValueAndCheck(
                                    getString(R.string.ForwardProtectedAsCopy),
                                    getString(R.string.ForwardProtectedAsCopyInfo),
                                    NaConfig.INSTANCE.getForwardProtectedAsCopy().Bool(), true, true);
                        }
                    }
                    break;
                }
                case TYPE_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == ayuGhostRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(getString(R.string.GhostMode), R.drawable.ayu_ghost, true);
                    } else if (position == exportEtgRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(getString(R.string.OEGeneralExportEtgSettings), R.drawable.msg_shareout, true);
                    } else if (position == importEtgRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(getString(R.string.OEGeneralImportEtgSettings), R.drawable.msg_download, false);
                    } else if (position == resetSettingsRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(getString(R.string.OEGeneralResetSettings), R.drawable.msg_reset, true);
                    } else if (position == deleteAccountRow) {
                        cell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedBold);
                        cell.setTextAndIcon(getString(R.string.OEGeneralDeleteAccount), R.drawable.msg_clearcache, false);
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == ayuDeletedMarkRow) {
                        cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                        cell.setTextAndValue(getString(NaConfig.INSTANCE.getCustomDeletedMark().getKey()),
                                NaConfig.INSTANCE.getCustomDeletedMark().String(), true);
                    } else if (position == ayuClearDbRow) {
                        cell.setTextColor(getThemedColor(Theme.key_text_RedRegular));
                        cell.setTextAndValue(getString(R.string.ClearMessageDatabase),
                                AyuData.totalSize > 0 ? AndroidUtilities.formatFileSize(AyuData.totalSize) : "...", false);
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    boolean bottom = position == bottomDividerRow;
                    if (position == googleDividerRow) {
                        cell.setText(getString(R.string.OEGeneralCrashReportsInfo));
                    } else if (position == nagramDividerRow) {
                        cell.setText(getString(R.string.OEGeneralNagramSettingsInfo));
                    } else if (position == etgDividerRow) {
                        cell.setText(getString(R.string.OEGeneralEtgSettingsInfo));
                    } else {
                        cell.setText(null);
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
            if (position == nagramHeaderRow || position == googleHeaderRow) {
                return TYPE_HEADER;
            } else if (position == nagramDividerRow || position == etgDividerRow
                    || position == googleDividerRow || position == bottomDividerRow) {
                return TYPE_INFO_PRIVACY;
            } else if (position == exportEtgRow || position == importEtgRow
                    || position == resetSettingsRow || position == deleteAccountRow
                    || position == ayuGhostRow) {
                return TYPE_TEXT;
            } else if (position == ayuDeletedMarkRow || position == ayuClearDbRow) {
                return TYPE_SETTINGS;
            } else if (position == ayuSaveMediaRow) {
                return TYPE_EXPANDABLE_SWITCH;
            } else if (position == saveMediaPrivateChatsRow || position == saveMediaPublicChannelsRow
                    || position == saveMediaPrivateChannelsRow || position == saveMediaPublicGroupsRow
                    || position == saveMediaPrivateGroupsRow) {
                return TYPE_ROUND_CHECK;
            }
            return TYPE_CHECK;
        }
    }
}
