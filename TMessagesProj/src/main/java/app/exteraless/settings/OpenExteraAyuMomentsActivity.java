package app.exteraless.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
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
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Locale;

import app.exteraless.drawer.MainMenuItem;
import app.exteraless.drawer.MainMenuLayout;
import app.exteraless.pillstack.PillStackConfig;
import app.exteraless.pillstack.PillType;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.filters.RegexFiltersSettingActivity;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.settings.GhostModeActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import xyz.nextalone.nagram.NaConfig;

public class OpenExteraAyuMomentsActivity extends BaseNekoSettingsActivity {

    private static final int TYPE_EXPANDABLE_SWITCH = 104;
    private static final int TYPE_ROUND_CHECK = 105;
    private static final int SAVE_MEDIA_TOTAL = 5;

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
            NaConfig.INSTANCE.getAskBeforeOpeningStory(),
    };

    private int headerRow;
    private int ghostRow;
    private int askStoryRow;
    private int regexRow;
    private int saveLastSeenRow;
    private int saveDeletedRow;
    private int saveEditsRow;
    private int saveMediaRow;
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
    private int forwardProtectedRow;
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
        regexRow = addRow(NaConfig.INSTANCE.getRegexFiltersEnabled().getKey());
        saveLastSeenRow = addRow(NaConfig.INSTANCE.getSaveLocalLastSeen().getKey());
        saveDeletedRow = addRow(NaConfig.INSTANCE.getEnableSaveDeletedMessages().getKey());
        saveEditsRow = addRow(NaConfig.INSTANCE.getEnableSaveEditsHistory().getKey());
        saveMediaRow = botUserRow = botChatRow = translucentRow = -1;
        saveDeletedPrivateRow = saveDeletedGroupsRow = saveDeletedChannelsRow = -1;
        replyToDeletedRow = deletedIconRow = deletedMarkRow = -1;
        saveMediaPrivateChatsRow = saveMediaPublicChannelsRow = saveMediaPrivateChannelsRow = -1;
        saveMediaPublicGroupsRow = saveMediaPrivateGroupsRow = -1;
        // Подчинённые строки живут только при включённом сохранении удалённых —
        // тот же порядок, что у NekoExperimentalSettingsActivity.checkSaveDeletedRows.
        if (NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            saveMediaRow = addRow(NaConfig.INSTANCE.getMessageSavingSaveMedia().getKey());
            if (saveMediaExpanded) {
                saveMediaPrivateChatsRow = addRow();
                saveMediaPublicChannelsRow = addRow();
                saveMediaPrivateChannelsRow = addRow();
                saveMediaPublicGroupsRow = addRow();
                saveMediaPrivateGroupsRow = addRow();
            }
            saveDeletedPrivateRow = addRow(NaConfig.INSTANCE.getSaveDeletedInPrivateChats().getKey());
            saveDeletedGroupsRow = addRow(NaConfig.INSTANCE.getSaveDeletedInGroups().getKey());
            saveDeletedChannelsRow = addRow(NaConfig.INSTANCE.getSaveDeletedInChannels().getKey());
            botUserRow = addRow(NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().getKey());
            if (NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().Bool()) {
                botChatRow = addRow(NaConfig.INSTANCE.getSaveDeletedMessageForBot().getKey());
            }
            replyToDeletedRow = addRow(NaConfig.INSTANCE.getReplyToDeletedAsQuote().getKey());
            translucentRow = addRow(NaConfig.INSTANCE.getTranslucentDeletedMessages().getKey());
            deletedIconRow = addRow(NaConfig.INSTANCE.getUseDeletedIcon().getKey());
            if (!NaConfig.INSTANCE.getUseDeletedIcon().Bool()) {
                deletedMarkRow = addRow(NaConfig.INSTANCE.getCustomDeletedMark().getKey());
            }
        }
        forwardProtectedRow = addRow(NaConfig.INSTANCE.getForwardProtectedAsCopy().getKey());
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
        } else if (position == saveLastSeenRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getSaveLocalLastSeen(), false);
        } else if (position == saveDeletedRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getEnableSaveDeletedMessages(), true);
        } else if (position == saveEditsRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getEnableSaveEditsHistory(), false);
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
        } else if (position == forwardProtectedRow) {
            toggleAyuConfig(view, NaConfig.INSTANCE.getForwardProtectedAsCopy(), false);
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
                        cell.setText(getString(R.string.OEGeneralAyuMoments));
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
                                NaConfig.INSTANCE.getAskBeforeOpeningStory().Bool(), true, true);
                    } else if (position == regexRow) {
                        cell.setTextAndValueAndCheck(
                                getString(NaConfig.INSTANCE.getRegexFiltersEnabled().getKey()),
                                getString(R.string.RegexFiltersNotice),
                                NaConfig.INSTANCE.getRegexFiltersEnabled().Bool(), true, true);
                    } else if (position == saveLastSeenRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getSaveLocalLastSeen(), true);
                    } else if (position == saveDeletedRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getEnableSaveDeletedMessages(), true);
                    } else if (position == saveEditsRow) {
                        bindAyuCheck(cell, NaConfig.INSTANCE.getEnableSaveEditsHistory(), true);
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
                        bindAyuCheck(cell, NaConfig.INSTANCE.getUseDeletedIcon(), true);
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
                    } else if (position == disableAllRow) {
                        cell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedBold);
                        cell.setTextAndIcon(getString(R.string.OEGeneralAyuMomentsDisableAll), R.drawable.msg_block, true);
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == deletedMarkRow) {
                        cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                        cell.setTextAndValue(getString(NaConfig.INSTANCE.getCustomDeletedMark().getKey()),
                                NaConfig.INSTANCE.getCustomDeletedMark().String(), true);
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
            if (position == headerRow) {
                return TYPE_HEADER;
            } else if (position == dividerRow) {
                return TYPE_INFO_PRIVACY;
            } else if (position == ghostRow || position == disableAllRow) {
                return TYPE_TEXT;
            } else if (position == deletedMarkRow || position == clearDbRow) {
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
