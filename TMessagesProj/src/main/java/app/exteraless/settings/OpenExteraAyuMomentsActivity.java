package app.exteraless.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.text.InputType;
import android.content.Context;
import android.content.Intent;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.messages.AyuMessagesController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ColorPicker;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Locale;

import app.exteraless.backup.AyuDatabaseImport;
import app.exteraless.drawer.MainMenuItem;
import app.exteraless.drawer.MainMenuLayout;
import app.exteraless.pillstack.PillStackConfig;
import app.exteraless.pillstack.PillType;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.filters.AyuFilter;
import tw.nekomimi.nekogram.filters.RegexFiltersSettingActivity;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.settings.GhostModeActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import xyz.nextalone.nagram.NaConfig;

public class OpenExteraAyuMomentsActivity extends BaseNekoSettingsActivity {

    private static final int TYPE_EXPANDABLE_SWITCH = 104;
    private static final int TYPE_ROUND_CHECK = 105;
    private static final int SAVE_MEDIA_TOTAL = 5;
    private static final int DATABASE_IMPORT_REQUEST_CODE = 41;

    /** Булевы функции секции; режим призрака гасится отдельно — он не один ConfigItem. */
    private static final ConfigItem[] AYU_FEATURE_CONFIGS = {
            NaConfig.INSTANCE.getRegexFiltersEnabled(),
            NaConfig.INSTANCE.getHideFromBlocked(),
            NaConfig.INSTANCE.getSaveLocalLastSeen(),
            NaConfig.INSTANCE.getProbeLastSeenUsingOtherAccounts(),
            NaConfig.INSTANCE.getSaveReadDate(),
            NaConfig.INSTANCE.getEnableSaveDeletedMessages(),
            NaConfig.INSTANCE.getEnableSaveEditsHistory(),
            NaConfig.INSTANCE.getMessageSavingSaveMedia(),
            NaConfig.INSTANCE.getSaveDeletedMessageForBotUser(),
            NaConfig.INSTANCE.getSaveDeletedMessageForBot(),
            NaConfig.INSTANCE.getTranslucentDeletedMessages(),
            NaConfig.INSTANCE.getUseDeletedIcon(),
            NaConfig.INSTANCE.getForwardProtectedAsCopy(),
            NaConfig.INSTANCE.getAskBeforeOpeningStory(),
    };

    private int headerRow;
    private int ghostRow;
    private int askStoryRow;
    private int ghostDividerRow;
    private int savingHeaderRow;
    private int savingDividerRow;
    private int deletedHeaderRow;
    private int deletedDividerRow;
    private int otherHeaderRow;
    private int otherDividerRow;
    private int dangerHeaderRow;
    private int regexRow;
    private int hideFromBlockedRow;
    private int saveLastSeenRow;
    private int probeLastSeenUsingOtherAccountsRow;
    private int saveReadDateRow;
    private int saveDeletedRow;
    private int saveEditsRow;
    private int deletedExceptionsModeRow;
    private int editedExceptionsModeRow;
    private int saveInArchivedRow;
    private int saveMediaRow;
    private int saveMediaWiFiLimitRow;
    private int saveMediaCellularLimitRow;
    private int saveMediaCacheAutoCleanRow;
    private int saveMediaCacheLimitRow;
    private int saveMediaPrivateChatsRow;
    private int saveMediaPublicChannelsRow;
    private int saveMediaPrivateChannelsRow;
    private int saveMediaPublicGroupsRow;
    private int saveMediaPrivateGroupsRow;
    private boolean saveMediaExpanded;
    private int botUserRow;
    private int botChatRow;
    private int saveDeletedPrivateRow;
    private int saveDeletedGroupsRow;
    private int saveDeletedChannelsRow;
    private int replyToDeletedRow;
    private int translucentRow;
    private int deletedIconRow;
    private int deletedMarkRow;
    private int deletedMarkColorRow;
    private int forwardProtectedRow;
    private int exportDbRow;
    private int importDbRow;
    private int disableAllRow;
    private int clearDbRow;
    private int dividerRow;

    public OpenExteraAyuMomentsActivity() {
        super();
    }

    @Override
    public boolean onFragmentCreate() {
        AyuData.loadSizes(this::refreshAyuDataSize);
        return super.onFragmentCreate();
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        headerRow = addRow("ayuHeader");
        ghostRow = addRow("ayuGhost");
        askStoryRow = addRow(NaConfig.INSTANCE.getAskBeforeOpeningStory().getKey());
        ghostDividerRow = addRow();

        boolean saveDeleted = NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool();
        boolean saveEdits = NaConfig.INSTANCE.getEnableSaveEditsHistory().Bool();
        saveMediaRow = saveMediaWiFiLimitRow = saveMediaCellularLimitRow = -1;
        saveMediaCacheAutoCleanRow = saveMediaCacheLimitRow = -1;
        botUserRow = botChatRow = translucentRow = -1;
        deletedExceptionsModeRow = editedExceptionsModeRow = -1;
        saveDeletedPrivateRow = saveDeletedGroupsRow = saveDeletedChannelsRow = -1;
        replyToDeletedRow = deletedIconRow = deletedMarkRow = deletedMarkColorRow = -1;
        saveMediaPrivateChatsRow = saveMediaPublicChannelsRow = saveMediaPrivateChannelsRow = -1;
        saveMediaPublicGroupsRow = saveMediaPrivateGroupsRow = -1;
        probeLastSeenUsingOtherAccountsRow = -1;
        deletedHeaderRow = deletedDividerRow = -1;

        savingHeaderRow = addRow("ayuSavingHeader");
        saveDeletedRow = addRow(NaConfig.INSTANCE.getEnableSaveDeletedMessages().getKey());
        if (saveDeleted) {
            deletedExceptionsModeRow = addRow(NaConfig.INSTANCE.getSaveDeletedExceptionsMode().getKey());
        }
        saveEditsRow = addRow(NaConfig.INSTANCE.getEnableSaveEditsHistory().getKey());
        if (saveEdits) {
            editedExceptionsModeRow = addRow(NaConfig.INSTANCE.getSaveEditedExceptionsMode().getKey());
        }
        saveInArchivedRow = addRow("saveInArchived");
        if (saveDeleted) {
            saveMediaRow = addRow(NaConfig.INSTANCE.getMessageSavingSaveMedia().getKey());
            if (saveMediaExpanded) {
                saveMediaPrivateChatsRow = addRow();
                saveMediaPublicChannelsRow = addRow();
                saveMediaPrivateChannelsRow = addRow();
                saveMediaPublicGroupsRow = addRow();
                saveMediaPrivateGroupsRow = addRow();
            }
        saveMediaWiFiLimitRow = addRow(NaConfig.INSTANCE.getSaveMediaWiFiLimit().getKey());
        saveMediaCellularLimitRow = addRow(NaConfig.INSTANCE.getSaveMediaCellularLimit().getKey());
        saveMediaCacheAutoCleanRow = addRow(NaConfig.INSTANCE.getSaveMediaCacheAutoClean().getKey());
        saveMediaCacheLimitRow = addRow(NaConfig.INSTANCE.getSaveMediaCacheLimit().getKey());
            saveDeletedPrivateRow = addRow(NaConfig.INSTANCE.getSaveDeletedInPrivateChats().getKey());
            saveDeletedGroupsRow = addRow(NaConfig.INSTANCE.getSaveDeletedInGroups().getKey());
            saveDeletedChannelsRow = addRow(NaConfig.INSTANCE.getSaveDeletedInChannels().getKey());
            botUserRow = addRow(NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().getKey());
            if (NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().Bool()) {
                botChatRow = addRow(NaConfig.INSTANCE.getSaveDeletedMessageForBot().getKey());
            }
        }
        saveLastSeenRow = addRow(NaConfig.INSTANCE.getSaveLocalLastSeen().getKey());
        probeLastSeenUsingOtherAccountsRow = addRow(NaConfig.INSTANCE.getProbeLastSeenUsingOtherAccounts().getKey());
        saveReadDateRow = addRow(NaConfig.INSTANCE.getSaveReadDate().getKey());
        savingDividerRow = addRow();

        if (saveDeleted) {
            deletedHeaderRow = addRow("ayuDeletedHeader");
            replyToDeletedRow = addRow(NaConfig.INSTANCE.getReplyToDeletedAsQuote().getKey());
            translucentRow = addRow(NaConfig.INSTANCE.getTranslucentDeletedMessages().getKey());
            deletedIconRow = addRow(NaConfig.INSTANCE.getUseDeletedIcon().getKey());
            if (!NaConfig.INSTANCE.getUseDeletedIcon().Bool()) {
                deletedMarkRow = addRow(NaConfig.INSTANCE.getCustomDeletedMark().getKey());
            }
            deletedMarkColorRow = addRow(NaConfig.INSTANCE.getDeletedMarkColor().getKey());
            deletedDividerRow = addRow();
        }

        otherHeaderRow = addRow("ayuOtherHeader");
        regexRow = addRow(NaConfig.INSTANCE.getRegexFiltersEnabled().getKey());
        hideFromBlockedRow = addRow(NaConfig.INSTANCE.getHideFromBlocked().getKey());
        forwardProtectedRow = addRow(NaConfig.INSTANCE.getForwardProtectedAsCopy().getKey());
        exportDbRow = addRow("databaseExport");
        importDbRow = addRow("databaseImport");
        otherDividerRow = addRow();

        dangerHeaderRow = addRow("ayuDangerHeader");
        disableAllRow = addRow("ayuDisableAll");
        clearDbRow = addRow("ayuClearDatabase");
        dividerRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.OEGeneralAyuMoments);
    }

    @Override
    public int getSearchGuid() {
        return 27000;
    }

    @Override
    public int getSearchIcon() {
        return R.drawable.ayu_ghost;
    }

    @Override
    public String getSearchPrefix() {
        return "OEAyu";
    }

    @Override
    protected String getKey() {
        return "exteraless_ayumoments";
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == ghostRow) {
            presentFragment(new GhostModeActivity());
        } else if (position == askStoryRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getAskBeforeOpeningStory(), false);
        } else if (position == regexRow) {
            // Как в эталоне: тап по тексту ведёт в список фильтров, тап по переключателю — включает.
            boolean onSwitch = LocaleController.isRTL
                    ? x <= AndroidUtilities.dp(76)
                    : x >= view.getMeasuredWidth() - AndroidUtilities.dp(76);
            if (onSwitch) {
                toggleAyuConfig(view, NaConfig.INSTANCE.getRegexFiltersEnabled(), false);
            } else {
                presentFragment(new RegexFiltersSettingActivity());
            }
        } else if (position == hideFromBlockedRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getHideFromBlocked(), false);
            if (NaConfig.INSTANCE.getHideFromBlocked().Bool()) {
                if (!NaConfig.INSTANCE.getRegexFiltersEnabled().Bool()) {
                    NaConfig.INSTANCE.getRegexFiltersEnabled().setConfigBool(true);
                    AyuFilter.rebuildCache();
                }
                MessagesController.getInstance(currentAccount).getBlockedPeers(false);
            }
        } else if (position == saveLastSeenRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveLocalLastSeen(), false);
        } else if (position == probeLastSeenUsingOtherAccountsRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getProbeLastSeenUsingOtherAccounts(), false);
        } else if (position == saveReadDateRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveReadDate(), false);
        } else if (position == saveDeletedRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getEnableSaveDeletedMessages(), true);
        } else if (position == deletedExceptionsModeRow) {
            showSaveExceptionsModeDialog(NaConfig.INSTANCE.getSaveDeletedExceptionsMode());
        } else if (position == saveEditsRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getEnableSaveEditsHistory(), false);
        } else if (position == editedExceptionsModeRow) {
            showSaveExceptionsModeDialog(NaConfig.INSTANCE.getSaveEditedExceptionsMode());
        } else if (position == saveInArchivedRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveInArchivedChats(), false);
        } else if (position == saveMediaRow) {
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
        } else if (position == saveMediaWiFiLimitRow) {
            showSaveMediaLimitDialog(NaConfig.INSTANCE.getSaveMediaWiFiLimit());
        } else if (position == saveMediaCellularLimitRow) {
            showSaveMediaLimitDialog(NaConfig.INSTANCE.getSaveMediaCellularLimit());
        } else if (position == saveMediaCacheAutoCleanRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveMediaCacheAutoClean(), false);
            if (NaConfig.INSTANCE.getSaveMediaCacheAutoClean().Bool()) {
                AyuMessagesController.getInstance().scheduleAttachmentsCacheCleanup();
            }
        } else if (position == saveMediaCacheLimitRow) {
            showSaveMediaLimitDialog(NaConfig.INSTANCE.getSaveMediaCacheLimit());
        } else if (position == botUserRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveDeletedMessageForBotUser(), true);
        } else if (position == botChatRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveDeletedMessageForBot(), false);
        } else if (position == saveDeletedPrivateRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveDeletedInPrivateChats(), false);
        } else if (position == saveDeletedGroupsRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveDeletedInGroups(), false);
        } else if (position == saveDeletedChannelsRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveDeletedInChannels(), false);
        } else if (position == replyToDeletedRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getReplyToDeletedAsQuote(), false);
        } else if (position == translucentRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getTranslucentDeletedMessages(), false);
        } else if (position == deletedIconRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getUseDeletedIcon(), true);
        } else if (position == deletedMarkRow) {
            showDeletedMarkDialog();
        } else if (position == deletedMarkColorRow) {
            showDeletedMarkColorPicker();
        } else if (position == forwardProtectedRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getForwardProtectedAsCopy(), false);
        } else if (position == exportDbRow) {
            AyuData.exportAyuDatabase(this);
        } else if (position == importDbRow) {
            openDatabasePicker();
        } else if (position == disableAllRow) {
            showDisableAllDialog();
        } else if (position == clearDbRow) {
            showClearAyuDatabaseDialog();
        }
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

    private void openDatabasePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, DATABASE_IMPORT_REQUEST_CODE);
        } catch (Exception e) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.ErrorOccurred)).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != DATABASE_IMPORT_REQUEST_CODE) {
            super.onActivityResultFragment(requestCode, resultCode, data);
            return;
        }
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            AyuDatabaseImport.importFromUri(this, data.getData());
        }
    }

    private void showDisableAllDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.OEGeneralAyuMomentsDisableAll))
                .setMessage(getString(R.string.AreYouSure))
                .setPositiveButton(getString(R.string.OK), (dialog, which) -> disableAllFeatures())
                .setNegativeButton(getString(R.string.Cancel), (d, w) -> d.dismiss())
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
    }

    private void disableAllFeatures() {
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
        rebuildRowsAndNotify();
    }

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
        if (listAdapter != null && saveMediaRow >= 0) {
            listAdapter.notifyItemChanged(saveMediaRow);
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
        if (clearDbRow >= 0 && listAdapter != null) {
            listAdapter.notifyItemChanged(clearDbRow);
        }
    }

    private void showSaveMediaLimitDialog(ConfigItem config) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(config.getKey()));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setHandlesColor(Theme.getColor(Theme.key_chat_TextSelectionCursor));
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setFocusable(true);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                Theme.getColor(Theme.key_text_RedRegular));
        editText.setPadding(0, 0, 0, AndroidUtilities.dp(6));
        editText.setText(String.valueOf(config.Int()));
        editText.requestFocus();
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(10), 0));

        builder.setPositiveButton(getString(R.string.OK), null);
        builder.setView(linearLayout);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int value = Integer.parseInt(editText.getText().toString());
                if (value >= 0) {
                    config.setConfigInt(value);
                    if (config == NaConfig.INSTANCE.getSaveMediaCacheLimit()
                            && NaConfig.INSTANCE.getSaveMediaCacheAutoClean().Bool()) {
                        AyuMessagesController.getInstance().scheduleAttachmentsCacheCleanup();
                    }
                    dialog.dismiss();
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(config == NaConfig.INSTANCE.getSaveMediaWiFiLimit()
                                ? saveMediaWiFiLimitRow
                                : config == NaConfig.INSTANCE.getSaveMediaCacheLimit()
                                ? saveMediaCacheLimitRow
                                : saveMediaCellularLimitRow);
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }));
        showDialog(dialog);
    }

    private String getSaveMediaLimitValue(ConfigItem config) {
        return config.Int() == 0
                ? getString(R.string.SaveMediaLimitUnlimited)
                : config.Int() + " MB";
    }

    private void showSaveExceptionsModeDialog(ConfigItem config) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        String[] modes = {
                getString(R.string.SaveExceptionsAllExceptSelected),
                getString(R.string.SaveExceptionsOnlySelected)
        };
        new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.SaveExceptionsMode))
                .setSingleChoiceItems(modes, config.Int(), (dialog, which) -> {
                    config.setConfigInt(which);
                    dialog.dismiss();
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(config == NaConfig.INSTANCE.getSaveDeletedExceptionsMode()
                                ? deletedExceptionsModeRow
                                : editedExceptionsModeRow);
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), (dialog, which) -> dialog.dismiss())
                .show();
    }

    private String getSaveExceptionsModeValue(ConfigItem config) {
        return getString(config.Int() == 1
                ? R.string.SaveExceptionsOnlySelected
                : R.string.SaveExceptionsAllExceptSelected);
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
            if (listAdapter != null && deletedMarkRow >= 0) {
                listAdapter.notifyItemChanged(deletedMarkRow);
            }
        }));
        showDialog(dialog);
    }

    private void showDeletedMarkColorPicker() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        ColorPicker colorPicker = new ColorPicker(context, false, (color, num, applyNow) ->
                NaConfig.INSTANCE.getDeletedMarkColor().setConfigInt(color)) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(300), MeasureSpec.EXACTLY));
            }
        };
        int color = NaConfig.INSTANCE.getDeletedMarkColor().Int();
        colorPicker.setColor(color == 0 ? getThemedColor(Theme.key_chat_messageTextIn) : color, 0);
        colorPicker.setType(-1, true, 1, 1, false, 0, false);
        BottomSheet bottomSheet = new BottomSheet(context, false);
        bottomSheet.setCustomView(colorPicker);
        bottomSheet.setOnDismissListener(dialog -> {
            if (listAdapter != null && deletedMarkColorRow >= 0) {
                listAdapter.notifyItemChanged(deletedMarkColorRow);
            }
        });
        bottomSheet.show();
    }

    private String getDeletedMarkColorValue() {
        int color = NaConfig.INSTANCE.getDeletedMarkColor().Int();
        return color == 0
                ? getString(R.string.DeletedMarkColorDefault)
                : String.format(Locale.US, "#%08X", color);
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
                    OpenExteraAyuMomentsActivity.this::toggleSaveMedia);
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
                    if (position == headerRow) {
                        cell.setText(getString(R.string.OEAyuGhostHeader));
                    } else if (position == savingHeaderRow) {
                        cell.setText(getString(R.string.OEAyuSavingHeader));
                    } else if (position == deletedHeaderRow) {
                        cell.setText(getString(R.string.OEAyuDeletedHeader));
                    } else if (position == otherHeaderRow) {
                        cell.setText(getString(R.string.OEAyuOtherHeader));
                    } else if (position == dangerHeaderRow) {
                        cell.setText(getString(R.string.OEAyuDangerHeader));
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setEnabled(true, null);
                    cell.setIcon(0);
                    if (position == askStoryRow) {
                        cell.setTextAndValueAndCheck(
                                getString(R.string.AskBeforeOpeningStory),
                                getString(R.string.AskBeforeOpeningStoryInfo),
                                NaConfig.INSTANCE.getAskBeforeOpeningStory().Bool(), true, false);
                    } else if (position == regexRow) {
                        cell.setTextAndValueAndCheck(
                                getString(NaConfig.INSTANCE.getRegexFiltersEnabled().getKey()),
                                getString(R.string.RegexFiltersNotice),
                                NaConfig.INSTANCE.getRegexFiltersEnabled().Bool(), true, true);
                    } else if (position == hideFromBlockedRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getHideFromBlocked(), true);
                    } else if (position == saveLastSeenRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getSaveLocalLastSeen(), true);
                    } else if (position == probeLastSeenUsingOtherAccountsRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getProbeLastSeenUsingOtherAccounts(), true);
                    } else if (position == saveReadDateRow) {
                        cell.setTextAndValueAndCheck(
                                getString(R.string.OEAyuSaveReadDate),
                                getString(R.string.OEAyuSaveReadDateInfo),
                                NaConfig.INSTANCE.getSaveReadDate().Bool(), true, false);
                    } else if (position == saveDeletedRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getEnableSaveDeletedMessages(), true);
                    } else if (position == saveEditsRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getEnableSaveEditsHistory(), true);
                    } else if (position == saveInArchivedRow) {
                        cell.setTextAndValueAndCheck(
                                getString(R.string.OEAyuSaveInArchived),
                                getString(R.string.OEAyuSaveInArchivedInfo),
                                NaConfig.INSTANCE.getSaveInArchivedChats().Bool(), true, true);
                    } else if (position == saveMediaCacheAutoCleanRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getSaveMediaCacheAutoClean(), true);
                    } else if (position == botUserRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getSaveDeletedMessageForBotUser(), true);
                    } else if (position == botChatRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getSaveDeletedMessageForBot(), true);
                    } else if (position == saveDeletedPrivateRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getSaveDeletedInPrivateChats(), true);
                    } else if (position == saveDeletedGroupsRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getSaveDeletedInGroups(), true);
                    } else if (position == saveDeletedChannelsRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getSaveDeletedInChannels(), true);
                    } else if (position == replyToDeletedRow) {
                        cell.setTextAndValueAndCheck(
                                getString(R.string.ReplyToDeletedAsQuote),
                                getString(R.string.ReplyToDeletedAsQuoteInfo),
                                NaConfig.INSTANCE.getReplyToDeletedAsQuote().Bool(), true, true);
                    } else if (position == translucentRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getTranslucentDeletedMessages(), true);
                    } else if (position == deletedIconRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getUseDeletedIcon(), deletedMarkColorRow != -1);
                    } else if (position == forwardProtectedRow) {
                        cell.setTextAndValueAndCheck(
                                getString(R.string.ForwardProtectedAsCopy),
                                getString(R.string.ForwardProtectedAsCopyInfo),
                                NaConfig.INSTANCE.getForwardProtectedAsCopy().Bool(), true, true);
                    }
                    break;
                }
                case TYPE_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == ghostRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(getString(R.string.GhostMode), R.drawable.ayu_ghost, true);
                    } else if (position == exportDbRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(getString(R.string.OEAyuDatabaseExport), R.drawable.msg_share, true);
                    } else if (position == importDbRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(getString(R.string.OEAyuDatabaseImport), R.drawable.msg_download, false);
                    } else if (position == disableAllRow) {
                        cell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedBold);
                        cell.setTextAndIcon(getString(R.string.OEGeneralAyuMomentsDisableAll), R.drawable.msg_block, true);
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == saveMediaWiFiLimitRow) {
                        cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                        cell.setTextAndValue(getString(NaConfig.INSTANCE.getSaveMediaWiFiLimit().getKey()),
                                getSaveMediaLimitValue(NaConfig.INSTANCE.getSaveMediaWiFiLimit()), true);
                    } else if (position == saveMediaCellularLimitRow) {
                        cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                        cell.setTextAndValue(getString(NaConfig.INSTANCE.getSaveMediaCellularLimit().getKey()),
                                getSaveMediaLimitValue(NaConfig.INSTANCE.getSaveMediaCellularLimit()), true);
                    } else if (position == saveMediaCacheLimitRow) {
                        cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                        cell.setTextAndValue(getString(NaConfig.INSTANCE.getSaveMediaCacheLimit().getKey()),
                                getSaveMediaLimitValue(NaConfig.INSTANCE.getSaveMediaCacheLimit()), true);
                    } else if (position == deletedExceptionsModeRow) {
                        cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                        cell.setTextAndValue(getString(NaConfig.INSTANCE.getSaveDeletedExceptionsMode().getKey()),
                                getSaveExceptionsModeValue(NaConfig.INSTANCE.getSaveDeletedExceptionsMode()), true);
                    } else if (position == editedExceptionsModeRow) {
                        cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                        cell.setTextAndValue(getString(NaConfig.INSTANCE.getSaveEditedExceptionsMode().getKey()),
                                getSaveExceptionsModeValue(NaConfig.INSTANCE.getSaveEditedExceptionsMode()), true);
                    } else if (position == deletedMarkRow) {
                        cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                        cell.setTextAndValue(getString(NaConfig.INSTANCE.getCustomDeletedMark().getKey()),
                                NaConfig.INSTANCE.getCustomDeletedMark().String(), false);
                    } else if (position == deletedMarkColorRow) {
                        cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                        cell.setTextAndValue(getString(NaConfig.INSTANCE.getDeletedMarkColor().getKey()),
                                getDeletedMarkColorValue(), false);
                    } else if (position == clearDbRow) {
                        cell.setTextColor(getThemedColor(Theme.key_text_RedRegular));
                        cell.setTextAndValue(getString(R.string.ClearMessageDatabase),
                                AyuData.totalSize > 0 ? AndroidUtilities.formatFileSize(AyuData.totalSize) : "...", false);
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == dividerRow) {
                        cell.setText(getString(R.string.OEGeneralAyuMomentsInfo));
                        cell.setBackground(Theme.getThemedDrawable(mContext,
                                R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow || position == savingHeaderRow || position == deletedHeaderRow
                    || position == otherHeaderRow || position == dangerHeaderRow) {
                return TYPE_HEADER;
            } else if (position == ghostDividerRow || position == savingDividerRow
                    || position == deletedDividerRow || position == otherDividerRow) {
                return TYPE_SHADOW;
            } else if (position == dividerRow) {
                return TYPE_INFO_PRIVACY;
            } else if (position == ghostRow || position == disableAllRow
                    || position == exportDbRow || position == importDbRow) {
                return TYPE_TEXT;
            } else if (position == saveMediaWiFiLimitRow || position == saveMediaCellularLimitRow
                    || position == saveMediaCacheLimitRow
                    || position == deletedExceptionsModeRow || position == editedExceptionsModeRow
                    || position == deletedMarkRow || position == deletedMarkColorRow
                    || position == clearDbRow) {
                return TYPE_SETTINGS;
            } else if (position == saveMediaRow) {
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
