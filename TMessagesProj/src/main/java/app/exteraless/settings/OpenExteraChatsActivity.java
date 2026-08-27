package app.exteraless.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Stories.recorder.DualCameraView;
import org.telegram.ui.ThemeActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import app.exteraless.OpenExteraConfig;
import app.exteraless.chats.ChatsConfig;
import app.exteraless.chats.DoubleTapCell;
import app.exteraless.chats.StickerShapeCell;
import app.exteraless.chats.WideChannelPostsPreviewCell;
import app.exteraless.icons.BaseIconPacks;
import kotlin.Unit;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.PopupBuilder;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import tw.nekomimi.nekogram.ui.cells.StickerSizePreviewMessagesCell;
import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.helper.DoubleTap;

/**
 * Экран «Chats» раздела openExtera — визуальный порт экрана exteraGram
 * (com.exteragram.messenger.preferences.ChatsPreferencesActivity, читаемый исходник 10.10.1).
 *
 * Настройки, которые уже есть в NagramX, переиспользуются из {@link NekoConfig} / {@link NaConfig};
 * то, чего нет — новые ConfigItem в {@link ChatsConfig} (помечены «только UI»).
 * Группы-мультивыбор (Replies, Hide Reactions, Quick Swipe Transition, Message Menu,
 * Extended Settings, Auto-Pause) сделаны разворачивающимися: строка «x/y» + чекбоксы.
 */
public class OpenExteraChatsActivity extends BaseNekoSettingsActivity {

    private static final int TYPE_STICKER_SIZE = 100;
    private static final int TYPE_STICKER_SHAPE = 101;
    private static final int TYPE_DOUBLE_TAP = 102;
    private static final int TYPE_SET_REACTION = 103;
    /** Сворачиваемая группа со счётчиком и шевроном. */
    private static final int TYPE_EXPANDABLE_SWITCH = 104;
    /** Круглая галочка внутри группы. */
    private static final int TYPE_ROUND_CHECK = 105;
    private static final int TYPE_WIDE_CHANNEL_PREVIEW = 106;

    /** Размер стикеров по умолчанию: к нему возвращает кнопка сброса в шапке. */
    private static final float STICKER_SIZE_DEFAULT = 14.0f;

    private StickerSizeCell stickerSizeCell;
    private StickerShapeCell stickerShapeCell;
    private DoubleTapCell doubleTapCell;
    private WideChannelPostsPreviewCell wideChannelPostsPreviewCell;
    private ActionBarMenuItem resetItem;

    private boolean repliesExpanded;
    private boolean hideReactionsExpanded;
    private boolean quickTransitionExpanded;
    private boolean messageMenuExpanded;
    private boolean mediaViewerMenuExpanded;
    private boolean actionBarButtonsExpanded;
    private boolean extendedSettingsExpanded;
    private boolean pauseExpanded;

    // Sticker Size
    private int stickerSizeRow;
    private int hideTimeOnStickersRow;
    private int repliesGroupRow;
    private int replyColorsRow;
    private int replyEmojiRow;
    private int replyBackgroundRow;
    private int stickerSizeDividerRow;

    // Sticker Shape
    private int stickerShapeHeaderRow;
    private int stickerShapeRow;
    private int stickerShapeDividerRow;

    // Links
    private int aiChatRow;
    private int chatSettingsRow;
    private int linksDividerRow;

    // Stickers and Emoji
    private int stickersHeaderRow;
    private int unlimitedRecentStickersRow;
    private int hideReactionsGroupRow;
    private int hideReactionsChannelsRow;
    private int hideReactionsGroupsRow;
    private int hideReactionsPrivateRow;
    private int stickersDividerRow;

    // Double Tap
    private int doubleTapHeaderRow;
    private int doubleTapRow;
    private int doubleTapIncomingRow;
    private int doubleTapOutgoingRow;
    private int doubleTapReactionRow;
    private int doubleTapDividerRow;

    // Chats
    private int chatsHeaderRow;
    private int bottomButtonRow;
    private int adminShortcutsRow;
    private int quickTransitionGroupRow;
    private int quickTransitionChannelsRow;
    private int quickTransitionTopicsRow;
    private int disableGreetingRow;
    private int hideKeyboardOnScrollRow;
    private int addCommaRow;
    private int hideSendAsPeerRow;
    private int tapToSwitchRecordRow;
    private int keepAttachButtonRow;
    private int chatsDividerRow;

    // Messages
    private int messagesHeaderRow;
    private int removeMessageTailRow;
    private int replaceEditedRow;
    private int showOnlineStatusRow;
    private int hideShareButtonRow;
    private int showResultsBeforeVotingRow;
    private int messageMenuGroupRow;
    private int menuCopyPhotoRow;
    private int menuSaveRow;
    private int menuRepeatRow;
    private int menuClearRow;
    private int menuHistoryRow;
    private int menuReportRow;
    private int menuDetailsRow;
    private int menuReactionsRow;
    private int menuReplyInPrivateRow;
    private int menuCopyLinkRow;
    private int menuCopyFrameRow;
    private int menuCopyAsStickerRow;
    private int menuAddToStickersRow;
    private int menuAddToFavoritesRow;
    private int menuNoQuoteForwardRow;
    private int menuSetReminderRow;
    private int menuBookmarkRow;
    private int menuRepeatAsCopyRow;
    private int menuTranslateRow;
    private int menuTranslateLlmRow;
    private int menuShareRow;
    private int menuHideRow;
    private int menuAdminActionsRow;
    private int menuPermissionsRow;
    private int mediaViewerMenuGroupRow;
    private int mediaMenuForwardRow;
    private int mediaMenuNoQuoteForwardRow;
    private int mediaMenuCopyFrameRow;
    private int mediaMenuCopyPhotoRow;
    private int mediaMenuProfilePhotoRow;
    private int mediaMenuQrRow;
    private int actionBarButtonsGroupRow;
    private int actionBarReplyRow;
    private int actionBarEditRow;
    private int actionBarSelectBetweenRow;
    private int actionBarCopyRow;
    private int actionBarForwardRow;
    private int groupedMessageMenuRow;
    private int messagesDividerRow;

    private int channelPostsHeaderRow;
    private int wideChannelPostsPreviewRow;
    private int wideChannelPostsRow;
    private int wideFeedPostsRow;
    private int channelPostsDividerRow;

    // Camera
    private int cameraHeaderRow;
    private int cameraTypeRow;
    private int extendedSettingsGroupRow;
    private int seamlessSwitchingRow;
    private int extendedFpsRow;
    private int cameraStabilizationRow;
    private int cameraMirrorModeRow;
    private int startWithWideAngleRow;
    private int videoMessagesCameraRow;
    private int rememberLastUsedCameraRow;
    private int zoomSliderRow;
    private int staticZoomRow;
    private int cameraDividerRow;

    // Photos
    private int photoHeaderRow;
    private int alwaysSendHdRow;
    private int hideCameraTileRow;
    private int photoDividerRow;

    // Videos
    private int videosHeaderRow;
    private int doubleTapSeekDurationRow;
    private int preferOriginalQualityRow;
    private int swipeToPipRow;
    private int unmuteWithVolumeButtonsRow;
    private int pauseGroupRow;
    private int pauseVideoRow;
    private int pauseVoiceRow;
    private int pauseRoundRow;
    private int videosDividerRow;

    public OpenExteraChatsActivity() {
        super();
        ChatsConfig.ensureLoaded();
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        stickerSizeRow = addRow("stickerSize");
        hideTimeOnStickersRow = addRow("hideTimeOnStickers");
        repliesGroupRow = addRow("replies");
        if (repliesExpanded) {
            replyColorsRow = addRow();
            replyEmojiRow = addRow();
            replyBackgroundRow = addRow();
        } else {
            replyColorsRow = replyEmojiRow = replyBackgroundRow = -1;
        }
        stickerSizeDividerRow = addRow();

        stickerShapeHeaderRow = addRow("stickerShapeHeader");
        stickerShapeRow = addRow("stickerShape");
        stickerShapeDividerRow = addRow();

        aiChatRow = addRow("aiChat");
        chatSettingsRow = addRow("chatSettings");
        linksDividerRow = addRow();

        stickersHeaderRow = addRow("stickersHeader");
        unlimitedRecentStickersRow = addRow("unlimitedRecentStickers");
        hideReactionsGroupRow = addRow("hideReactions");
        if (hideReactionsExpanded) {
            hideReactionsChannelsRow = addRow();
            hideReactionsGroupsRow = addRow();
            hideReactionsPrivateRow = addRow();
        } else {
            hideReactionsChannelsRow = hideReactionsGroupsRow = hideReactionsPrivateRow = -1;
        }
        stickersDividerRow = addRow();

        doubleTapHeaderRow = addRow("doubleTapHeader");
        doubleTapRow = addRow("doubleTapPreview");
        doubleTapIncomingRow = addRow("doubleTapIncoming");
        doubleTapOutgoingRow = addRow("doubleTapOutgoing");
        if (NaConfig.INSTANCE.getDoubleTapAction().Int() == DoubleTap.DOUBLE_TAP_ACTION_SEND_REACTIONS
                || NaConfig.INSTANCE.getDoubleTapActionOut().Int() == DoubleTap.DOUBLE_TAP_ACTION_SEND_REACTIONS) {
            doubleTapReactionRow = addRow("doubleTapReaction");
        } else {
            doubleTapReactionRow = -1;
        }
        doubleTapDividerRow = addRow();

        chatsHeaderRow = addRow("chatsHeader");
        bottomButtonRow = addRow("bottomButton");
        adminShortcutsRow = addRow("adminShortcuts");
        quickTransitionGroupRow = addRow("quickTransition");
        if (quickTransitionExpanded) {
            quickTransitionChannelsRow = addRow();
            quickTransitionTopicsRow = addRow();
        } else {
            quickTransitionChannelsRow = quickTransitionTopicsRow = -1;
        }
        disableGreetingRow = addRow("disableGreeting");
        hideKeyboardOnScrollRow = addRow("hideKeyboardOnScroll");
        addCommaRow = addRow("addCommaAfterMention");
        hideSendAsPeerRow = addRow("hideSendAsPeer");
        tapToSwitchRecordRow = addRow("tapToSwitchRecord");
        keepAttachButtonRow = addRow("keepAttachButton");
        chatsDividerRow = addRow();

        messagesHeaderRow = addRow("messagesHeader");
        removeMessageTailRow = addRow("removeMessageTail");
        replaceEditedRow = addRow("replaceEdited");
        showOnlineStatusRow = addRow("showOnlineStatus");
        hideShareButtonRow = addRow("hideShareButton");
        showResultsBeforeVotingRow = addRow("showResultsBeforeVoting");
        messageMenuGroupRow = addRow("messageMenu");
        if (messageMenuExpanded) {
            menuReactionsRow = addRow();
            menuReplyInPrivateRow = addRow();
            menuCopyLinkRow = addRow();
            menuCopyFrameRow = addRow();
            menuCopyPhotoRow = addRow();
            menuCopyAsStickerRow = addRow();
            menuAddToStickersRow = addRow();
            menuAddToFavoritesRow = addRow();
            menuNoQuoteForwardRow = addRow();
            menuSetReminderRow = addRow();
            menuSaveRow = addRow();
            menuBookmarkRow = addRow();
            menuRepeatRow = addRow();
            menuRepeatAsCopyRow = addRow();
            menuClearRow = addRow();
            menuHistoryRow = addRow();
            menuTranslateRow = addRow();
            menuTranslateLlmRow = addRow();
            menuShareRow = addRow();
            menuHideRow = addRow();
            menuReportRow = addRow();
            menuAdminActionsRow = addRow();
            menuPermissionsRow = addRow();
            menuDetailsRow = addRow();
        } else {
            menuReactionsRow = menuReplyInPrivateRow = menuCopyLinkRow = menuCopyFrameRow = -1;
            menuCopyPhotoRow = menuCopyAsStickerRow = menuAddToStickersRow = menuAddToFavoritesRow = -1;
            menuNoQuoteForwardRow = menuSetReminderRow = menuSaveRow = menuBookmarkRow = -1;
            menuRepeatRow = menuRepeatAsCopyRow = menuClearRow = menuHistoryRow = -1;
            menuTranslateRow = menuTranslateLlmRow = menuShareRow = menuHideRow = -1;
            menuReportRow = menuAdminActionsRow = menuPermissionsRow = menuDetailsRow = -1;
        }
        mediaViewerMenuGroupRow = addRow("mediaViewerMenu");
        if (mediaViewerMenuExpanded) {
            mediaMenuForwardRow = addRow();
            mediaMenuNoQuoteForwardRow = addRow();
            mediaMenuCopyFrameRow = addRow();
            mediaMenuCopyPhotoRow = addRow();
            mediaMenuProfilePhotoRow = addRow();
            mediaMenuQrRow = addRow();
        } else {
            mediaMenuForwardRow = mediaMenuNoQuoteForwardRow = mediaMenuCopyFrameRow = -1;
            mediaMenuCopyPhotoRow = mediaMenuProfilePhotoRow = mediaMenuQrRow = -1;
        }
        actionBarButtonsGroupRow = addRow("actionBarButtons");
        if (actionBarButtonsExpanded) {
            actionBarReplyRow = addRow();
            actionBarEditRow = addRow();
            actionBarSelectBetweenRow = addRow();
            actionBarCopyRow = addRow();
            actionBarForwardRow = addRow();
        } else {
            actionBarReplyRow = actionBarEditRow = actionBarSelectBetweenRow = -1;
            actionBarCopyRow = actionBarForwardRow = -1;
        }
        groupedMessageMenuRow = addRow("groupedMessageMenu");
        messagesDividerRow = addRow();

        channelPostsHeaderRow = addRow("channelPostsHeader");
        wideChannelPostsPreviewRow = addRow("wideChannelPostsPreview");
        wideChannelPostsRow = addRow("wideChannelPosts");
        wideFeedPostsRow = addRow("wideFeedPosts");
        channelPostsDividerRow = addRow();

        cameraHeaderRow = addRow("cameraHeader");
        cameraTypeRow = addRow("cameraType");
        seamlessSwitchingRow = extendedFpsRow = cameraStabilizationRow = -1;
        cameraMirrorModeRow = startWithWideAngleRow = -1;
        // При системной камере вся группа скрыта: расширенные настройки
        // относятся только к Camera2 и CameraX.
        if (cameraTypeIndex() != CAMERA_TYPE_SYSTEM) {
            extendedSettingsGroupRow = addRow("extendedSettings");
            if (extendedSettingsExpanded) {
                // Бесшовное переключение есть не на всяком железе.
                if (isSeamlessSwitchingAvailable()) {
                    seamlessSwitchingRow = addRow();
                }
                extendedFpsRow = addRow();
                cameraStabilizationRow = addRow();
                // Зеркало и широкий угол умеет только CameraX.
                if (cameraTypeIndex() == CAMERA_TYPE_CAMERA_X) {
                    cameraMirrorModeRow = addRow();
                    startWithWideAngleRow = addRow();
                }
            }
            if (!isSeamlessSwitchingAvailable() && isSeamlessSwitchingEnabled()) {
                // На устройстве без второй камеры флаг гасится принудительно.
                setSeamlessSwitching(false);
            }
        } else {
            extendedSettingsGroupRow = -1;
        }
        videoMessagesCameraRow = addRow("videoMessagesCamera");
        // Запоминать нечего, пока камера спрашивается каждый раз.
        if (NaConfig.INSTANCE.getCameraInVideoMessages().Int() != VIDEO_CAMERA_ASK) {
            rememberLastUsedCameraRow = addRow("rememberLastUsedCamera");
        } else {
            rememberLastUsedCameraRow = -1;
        }
        zoomSliderRow = addRow("zoomSlider");
        staticZoomRow = addRow("staticZoom");
        cameraDividerRow = addRow();

        photoHeaderRow = addRow("photoHeader");
        alwaysSendHdRow = addRow("alwaysSendInHD");
        hideCameraTileRow = addRow("hideCameraTile");
        photoDividerRow = addRow();

        videosHeaderRow = addRow("videosHeader");
        doubleTapSeekDurationRow = addRow("doubleTapSeekDuration");
        preferOriginalQualityRow = addRow("preferOriginalQuality");
        swipeToPipRow = addRow("swipeToPip");
        unmuteWithVolumeButtonsRow = addRow("unmuteWithVolumeButtons");
        pauseGroupRow = addRow("pauseOnMinimize");
        if (pauseExpanded) {
            pauseVideoRow = addRow();
            pauseVoiceRow = addRow();
            pauseRoundRow = addRow();
        } else {
            pauseVideoRow = pauseVoiceRow = pauseRoundRow = -1;
        }
        videosDividerRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.OpenExteraChats);
    }

    @Override
    public int getSearchGuid() {
        return 22000;
    }

    @Override
    public int getSearchIcon() {
        return R.drawable.msg_discussion;
    }

    @Override
    public String getSearchPrefix() {
        return "OEChats";
    }

    @Override
    protected String getKey() {
        return "exteraless_chats";
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private void reloadList() {
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    /** Пересобрать открытые чаты: часть настроек видна прямо в них. */
    private void rebuildChats() {
        if (parentLayout != null) {
            parentLayout.rebuildFragments(0);
        }
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        // Кнопка сброса размера стикеров в шапке: появляется, как только
        // размер отличается от стандартного.
        if (actionBar != null) {
            resetItem = actionBar.createMenu().addItem(0, R.drawable.msg_reset);
            resetItem.setContentDescription(getString(R.string.Reset));
            // Только через updateViewVisibilityAnimated: она проставляет и видимость,
            // и тег. Прямой setVisibility оставлял тег от addItem (id = 0, то есть
            // не null), а показ в updateViewVisibilityAnimated идёт по ветке
            // tag == null — кнопка навсегда оставалась скрытой.
            AndroidUtilities.updateViewVisibilityAnimated(resetItem,
                    NekoConfig.stickerSize.Float() != STICKER_SIZE_DEFAULT, 0.5f, false);
            resetItem.setOnClickListener(v -> resetStickerSize());
        }
        return view;
    }

    /** Плавный возврат слайдера к стандартному размеру за 200 мс. */
    private void resetStickerSize() {
        if (resetItem != null) {
            AndroidUtilities.updateViewVisibilityAnimated(resetItem, false, 0.5f, true);
        }
        ValueAnimator animator = ValueAnimator.ofFloat(NekoConfig.stickerSize.Float(), STICKER_SIZE_DEFAULT);
        animator.setDuration(200);
        animator.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();
            NekoConfig.stickerSize.setConfigFloat(value);
            if (stickerSizeCell != null) {
                stickerSizeCell.setStickerSize(value);
            }
        });
        animator.start();
    }

    /** Кнопка возвращается при первом же движении слайдера. */
    private void showResetItem() {
        if (resetItem != null && resetItem.getVisibility() != View.VISIBLE) {
            AndroidUtilities.updateViewVisibilityAnimated(resetItem, true, 0.5f, true);
        }
    }

    // ---- Сворачиваемые группы: счётчики и мастер-переключатели ----

    private static final int REPLIES_TOTAL = 3;
    private static final int HIDE_REACTIONS_TOTAL = 3;
    private static final int QUICK_TRANSITIONS_TOTAL = 2;
    private static final int MESSAGE_MENU_TOTAL = 24;
    private static final int MEDIA_VIEWER_MENU_TOTAL = 6;
    private static final int ACTION_BAR_BUTTONS_TOTAL = 5;
    private static final int PAUSE_TOTAL = 3;

    private static int repliesSelectedCount() {
        return count(ChatsConfig.replyColors.Bool(), ChatsConfig.replyEmoji.Bool(), ChatsConfig.replyBackground.Bool());
    }

    private static int hideReactionsSelectedCount() {
        return count(ChatsConfig.hideReactionsInChannels.Bool(), ChatsConfig.hideReactionsInGroups.Bool(),
                ChatsConfig.hideReactionsInPrivate.Bool());
    }

    private static int quickTransitionsSelectedCount() {
        return count(quickTransitionForChannels(), quickTransitionForTopics());
    }

    private static int messageMenuSelectedCount() {
        return count(NaConfig.INSTANCE.getShowReactions().Bool(), NaConfig.INSTANCE.getShowReplyInPrivate().Bool(),
                NaConfig.INSTANCE.getShowCopyLink().Bool(), NaConfig.INSTANCE.getShowCopyFrame().Bool(),
                NaConfig.INSTANCE.getShowCopyPhoto().Bool(), NaConfig.INSTANCE.getShowCopyAsSticker().Bool(),
                NaConfig.INSTANCE.getShowAddToStickers().Bool(), NaConfig.INSTANCE.getShowAddToFavorites().Bool(),
                NaConfig.INSTANCE.getShowNoQuoteForward().Bool(), NaConfig.INSTANCE.getShowSetReminder().Bool(),
                NekoConfig.showAddToSavedMessages.Bool(), NaConfig.INSTANCE.getShowAddToBookmark().Bool(),
                NekoConfig.showRepeat.Bool(), NaConfig.INSTANCE.getShowRepeatAsCopy().Bool(),
                NekoConfig.showDeleteDownloadedFile.Bool(), NekoConfig.showViewHistory.Bool(),
                NekoConfig.showTranslate.Bool(), NaConfig.INSTANCE.getShowTranslateMessageLLM().Bool(),
                NekoConfig.showShareMessages.Bool(), NekoConfig.showMessageHide.Bool(),
                NekoConfig.showReport.Bool(), NekoConfig.showAdminActions.Bool(),
                NekoConfig.showChangePermissions.Bool(), NekoConfig.showMessageDetails.Bool());
    }

    private static int mediaViewerMenuSelectedCount() {
        return count(NaConfig.INSTANCE.getMediaViewerMenuItemForward().Bool(),
                NaConfig.INSTANCE.getMediaViewerMenuItemNoQuoteForward().Bool(),
                NaConfig.INSTANCE.getMediaViewerMenuItemCopyFrame().Bool(),
                NaConfig.INSTANCE.getMediaViewerMenuItemCopyPhoto().Bool(),
                NaConfig.INSTANCE.getMediaViewerMenuItemSetProfilePhoto().Bool(),
                NaConfig.INSTANCE.getMediaViewerMenuItemScanQRCode().Bool());
    }

    private static int actionBarButtonsSelectedCount() {
        return count(NaConfig.INSTANCE.getActionBarButtonReply().Bool(),
                NaConfig.INSTANCE.getActionBarButtonEdit().Bool(),
                NaConfig.INSTANCE.getActionBarButtonSelectBetween().Bool(),
                NaConfig.INSTANCE.getActionBarButtonCopy().Bool(),
                NaConfig.INSTANCE.getActionBarButtonForward().Bool());
    }

    private static int pauseSelectedCount() {
        return count(NekoConfig.autoPauseVideo.Bool(), ChatsConfig.pauseOnMinimizeVoice.Bool(),
                ChatsConfig.pauseOnMinimizeRound.Bool());
    }

    /**
     * Знаменатель «N/M» считается по реально показанным строкам. Зеркалирование и
     * широкоугольная камера считаются только при CameraX: обе настройки применяет
     * CameraXSession, другим движкам их передать некуда.
     */
    private static int cameraSettingsTotal() {
        int total = isSeamlessSwitchingAvailable() ? 3 : 2;
        if (ChatsConfig.cameraType() == CAMERA_TYPE_CAMERA_X) {
            total += 2;  // зеркало и широкий угол
        }
        return total;
    }

    private static int cameraSettingsSelected() {
        int selected = count(ChatsConfig.extendedFramesPerSecond.Bool(), ChatsConfig.cameraStabilization.Bool());
        if (isSeamlessSwitchingAvailable() && isSeamlessSwitchingEnabled()) {
            selected++;
        }
        if (ChatsConfig.cameraType() == CAMERA_TYPE_CAMERA_X) {
            selected += count(ChatsConfig.cameraMirrorMode.Bool(), ChatsConfig.startWithWideAngleCamera.Bool());
        }
        return selected;
    }

    private void toggleAllReplies() {
        boolean enable = repliesSelectedCount() == 0;
        ChatsConfig.replyColors.setConfigBool(enable);
        ChatsConfig.replyEmoji.setConfigBool(enable);
        ChatsConfig.replyBackground.setConfigBool(enable);
        if (stickerSizeCell != null) {
            stickerSizeCell.invalidate();
        }
        rebuildChats();
        reloadList();
    }

    private void toggleAllHideReactions() {
        boolean enable = hideReactionsSelectedCount() == 0;
        ChatsConfig.hideReactionsInChannels.setConfigBool(enable);
        ChatsConfig.hideReactionsInGroups.setConfigBool(enable);
        ChatsConfig.hideReactionsInPrivate.setConfigBool(enable);
        rebuildChats();
        reloadList();
    }

    private void toggleAllQuickTransitions() {
        boolean enable = quickTransitionsSelectedCount() == 0;
        NekoConfig.disableSwipeToNext.setConfigBool(!enable);
        NekoConfig.disableSwipeToNextTopic.setConfigBool(!enable);
        reloadList();
    }

    private void toggleAllMessageMenu() {
        boolean enable = messageMenuSelectedCount() == 0;
        NaConfig.INSTANCE.getShowReactions().setConfigBool(enable);
        NaConfig.INSTANCE.getShowReplyInPrivate().setConfigBool(enable);
        NaConfig.INSTANCE.getShowCopyLink().setConfigBool(enable);
        NaConfig.INSTANCE.getShowCopyFrame().setConfigBool(enable);
        NaConfig.INSTANCE.getShowCopyPhoto().setConfigBool(enable);
        NaConfig.INSTANCE.getShowCopyAsSticker().setConfigBool(enable);
        NaConfig.INSTANCE.getShowAddToStickers().setConfigBool(enable);
        NaConfig.INSTANCE.getShowAddToFavorites().setConfigBool(enable);
        NaConfig.INSTANCE.getShowNoQuoteForward().setConfigBool(enable);
        NaConfig.INSTANCE.getShowSetReminder().setConfigBool(enable);
        NekoConfig.showAddToSavedMessages.setConfigBool(enable);
        NaConfig.INSTANCE.getShowAddToBookmark().setConfigBool(enable);
        NekoConfig.showRepeat.setConfigBool(enable);
        NaConfig.INSTANCE.getShowRepeatAsCopy().setConfigBool(enable);
        NekoConfig.showDeleteDownloadedFile.setConfigBool(enable);
        NekoConfig.showViewHistory.setConfigBool(enable);
        NekoConfig.showTranslate.setConfigBool(enable);
        NaConfig.INSTANCE.getShowTranslateMessageLLM().setConfigBool(enable);
        NekoConfig.showShareMessages.setConfigBool(enable);
        NekoConfig.showMessageHide.setConfigBool(enable);
        NekoConfig.showReport.setConfigBool(enable);
        NekoConfig.showAdminActions.setConfigBool(enable);
        NekoConfig.showChangePermissions.setConfigBool(enable);
        NekoConfig.showMessageDetails.setConfigBool(enable);
        rebuildChats();
        reloadList();
    }

    private void toggleAllMediaViewerMenu() {
        boolean enable = mediaViewerMenuSelectedCount() == 0;
        NaConfig.INSTANCE.getMediaViewerMenuItemForward().setConfigBool(enable);
        NaConfig.INSTANCE.getMediaViewerMenuItemNoQuoteForward().setConfigBool(enable);
        NaConfig.INSTANCE.getMediaViewerMenuItemCopyFrame().setConfigBool(enable);
        NaConfig.INSTANCE.getMediaViewerMenuItemCopyPhoto().setConfigBool(enable);
        NaConfig.INSTANCE.getMediaViewerMenuItemSetProfilePhoto().setConfigBool(enable);
        NaConfig.INSTANCE.getMediaViewerMenuItemScanQRCode().setConfigBool(enable);
        reloadList();
    }

    private void toggleAllActionBarButtons() {
        boolean enable = actionBarButtonsSelectedCount() == 0;
        NaConfig.INSTANCE.getActionBarButtonReply().setConfigBool(enable);
        NaConfig.INSTANCE.getActionBarButtonEdit().setConfigBool(enable);
        NaConfig.INSTANCE.getActionBarButtonSelectBetween().setConfigBool(enable);
        NaConfig.INSTANCE.getActionBarButtonCopy().setConfigBool(enable);
        NaConfig.INSTANCE.getActionBarButtonForward().setConfigBool(enable);
        rebuildChats();
        reloadList();
    }

    private void toggleAllCameraSettings() {
        boolean enable = cameraSettingsSelected() == 0;
        // При недоступном железе флаг всегда гасится.
        setSeamlessSwitching(enable && isSeamlessSwitchingAvailable());
        ChatsConfig.extendedFramesPerSecond.setConfigBool(enable);
        ChatsConfig.cameraStabilization.setConfigBool(enable);
        reloadList();
    }

    private void toggleAllPause() {
        boolean enable = pauseSelectedCount() == 0;
        NekoConfig.autoPauseVideo.setConfigBool(enable);
        ChatsConfig.pauseOnMinimizeVoice.setConfigBool(enable);
        ChatsConfig.pauseOnMinimizeRound.setConfigBool(enable);
        reloadList();
    }

    // ---- Настройки, которые лежат не в ConfigItem ----

    /** «Быстрый свайп-переход» — это ключи NekoConfig.disableSwipeToNext*, только наоборот. */
    private static boolean quickTransitionForChannels() {
        return !NekoConfig.disableSwipeToNext.Bool();
    }

    private static boolean quickTransitionForTopics() {
        return !NekoConfig.disableSwipeToNextTopic.Bool();
    }

    /** Вторая камера для «бесшовного переключения» есть не на всех устройствах. */
    private static boolean isSeamlessSwitchingAvailable() {
        Context context = ApplicationLoader.applicationContext;
        return context != null && DualCameraView.dualAvailableStatic(context);
    }

    private static boolean isSeamlessSwitchingEnabled() {
        Context context = ApplicationLoader.applicationContext;
        return context != null && DualCameraView.roundDualAvailableStatic(context);
    }

    /**
     * Настройка живёт в глобальных префах, а не в ChatsConfig: этот же ключ
     * читает InstantCameraView.
     */
    private static void setSeamlessSwitching(boolean value) {
        MessagesController.getGlobalMainSettings().edit().putBoolean("rounddual_available", value).apply();
    }

    private static final int CAMERA_TYPE_SYSTEM = 0;
    private static final int CAMERA_TYPE_CAMERA2 = 1;
    private static final int CAMERA_TYPE_CAMERA_X = 2;
    /** Индекс варианта «спрашивать каждый раз» в NaConfig.cameraInVideoMessages. */
    private static final int VIDEO_CAMERA_ASK = 2;

    /**
     * Тип камеры для кружков. Системный вариант оставлен за SharedConfig — там же, где
     * его переключает отладочное меню Telegram; два других выбираются явно и живут в
     * своём ключе, потому что булевым флагом три состояния не выразить.
     */
    private int cameraTypeIndex() {
        return ChatsConfig.cameraType();
    }

    private void setCameraTypeIndex(int index) {
        if (cameraTypeIndex() != index) {
            ChatsConfig.cameraType.setConfigInt(index);
        }
    }

    /**
     * Иконка строки AI-чата. Варианты под иконпаки нужны потому, что BaseIconPacks
     * подменяет иконки по всему приложению — здесь должно быть так же.
     */
    private static int aiChatIcon() {
        switch (BaseIconPacks.getSelected()) {
            case BaseIconPacks.BASE_SOLAR:
                return R.drawable.ai_chat_solar;
            case BaseIconPacks.BASE_REMIX:
                return R.drawable.ai_chat_remix;
            default:
                return R.drawable.ai_chat;
        }
    }

    // ---- Значения для строк с выбором ----

    private void setBottomButton(int index) {
        ChatsConfig.bottomButton.setConfigInt(index);
        NaConfig.INSTANCE.getDisableChannelMuteButton()
                .setConfigBool(index == ChatsConfig.BOTTOM_BUTTON_HIDE);
    }

    private CharSequence[] bottomButtonOptions() {
        return new CharSequence[]{
                getString(R.string.Hide),
                getString(R.string.ChannelMuteNoCaps),
                getString(R.string.OEChatsBottomButtonDiscuss)
        };
    }

    private CharSequence[] cameraTypeOptions() {
        return new CharSequence[]{
                getString(R.string.Default),
                getString(R.string.OEChatsCameraTypeCamera2),
                getString(R.string.OEChatsCameraTypeCameraX)
        };
    }

    private CharSequence[] videoMessagesCameraOptions() {
        return new CharSequence[]{
                getString(R.string.CameraInVideoMessagesFront),
                getString(R.string.CameraInVideoMessagesRear),
                getString(R.string.CameraInVideoMessagesAsk)
        };
    }

    private CharSequence[] seekDurationOptions() {
        int[] durations = ChatsConfig.SEEK_DURATIONS;
        CharSequence[] result = new CharSequence[durations.length];
        for (int a = 0; a < durations.length; a++) {
            result[a] = LocaleController.formatPluralString("Seconds", durations[a]);
        }
        return result;
    }

    private static int clampIndex(int value, int size) {
        return value >= 0 && value < size ? value : 0;
    }

    private static int count(boolean... values) {
        int c = 0;
        for (boolean v : values) {
            if (v) c++;
        }
        return c;
    }

    private static String ratio(int selected, int total) {
        return String.format(Locale.getDefault(), "%d/%d", selected, total);
    }

    private void showOptions(View view, int position, CharSequence[] options, ConfigItem item) {
        showOptions(view, options, index -> {
            item.setConfigInt(index);
            listAdapter.notifyItemChanged(position);
        });
    }

    private interface OnIndexSelected {
        void run(int index);
    }

    private void showOptions(View view, CharSequence[] options, OnIndexSelected onSelected) {
        PopupBuilder builder = new PopupBuilder(view);
        builder.setItems(new ArrayList<CharSequence>(Arrays.asList(options)), (index, text) -> {
            onSelected.run(index);
            return Unit.INSTANCE;
        });
        builder.show();
    }

    /**
     * Иконки действий двойного тапа по индексам {@link DoubleTap} (0..10).
     * Такая же таблица есть в DoubleTapCell, но она приватная — при случае стоит открыть её там
     * и удалить этот дубль.
     */
    private static final int[] DOUBLE_TAP_ICONS = new int[]{
            R.drawable.msg_block,
            R.drawable.msg_reactions,
            R.drawable.msg_reactions2,
            R.drawable.msg_translate,
            R.drawable.msg_reply_small,
            R.drawable.msg_saved,
            R.drawable.msg_repeat,
            R.drawable.msg_copy,
            R.drawable.msg_edit,
            R.drawable.msg_translate,
            R.drawable.msg_delete
    };

    /**
     * Список с заголовком и иконками действий, а не голый попап. Набор действий
     * наш, из NagramX: TRANSLATE_LLM и REPEAT_AS_COPY.
     */
    private void showDoubleTapOptions(int position, boolean outgoing) {
        if (getParentActivity() == null) {
            return;
        }
        List<Integer> types = new ArrayList<>();
        types.add(DoubleTap.DOUBLE_TAP_ACTION_NONE);
        types.add(DoubleTap.DOUBLE_TAP_ACTION_SEND_REACTIONS);
        types.add(DoubleTap.DOUBLE_TAP_ACTION_SHOW_REACTIONS);
        types.add(DoubleTap.DOUBLE_TAP_ACTION_TRANSLATE);
        types.add(DoubleTap.DOUBLE_TAP_ACTION_TRANSLATE_LLM);
        types.add(DoubleTap.DOUBLE_TAP_ACTION_REPLY);
        types.add(DoubleTap.DOUBLE_TAP_ACTION_SAVE);
        types.add(DoubleTap.DOUBLE_TAP_ACTION_REPEAT);
        types.add(DoubleTap.DOUBLE_TAP_ACTION_REPEAT_AS_COPY);
        if (outgoing) {
            types.add(DoubleTap.DOUBLE_TAP_ACTION_EDIT);
        }
        types.add(DoubleTap.DOUBLE_TAP_ACTION_DELETE);

        CharSequence[] titles = new CharSequence[types.size()];
        int[] icons = new int[types.size()];
        for (int a = 0; a < types.size(); a++) {
            int action = types.get(a);
            titles[a] = DoubleTap.doubleTapActionMap.get(action);
            icons[a] = action >= 0 && action < DOUBLE_TAP_ICONS.length
                    ? DOUBLE_TAP_ICONS[action] : DOUBLE_TAP_ICONS[0];
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(outgoing ? R.string.DoubleTapOutgoing : R.string.DoubleTapIncoming));
        builder.setItems(titles, icons, (dialog, which) -> {
            boolean hadReaction = doubleTapReactionRow != -1;
            int action = types.get(which);
            if (outgoing) {
                NaConfig.INSTANCE.getDoubleTapActionOut().setConfigInt(action);
            } else {
                NaConfig.INSTANCE.getDoubleTapAction().setConfigInt(action);
            }
            if (doubleTapCell != null) {
                // 1 — обновить только входящее сообщение, 2 — только исходящее.
                doubleTapCell.updateIcons(outgoing ? 2 : 1, true);
                doubleTapCell.invalidate();
            }
            boolean hasReaction = NaConfig.INSTANCE.getDoubleTapAction().Int() == DoubleTap.DOUBLE_TAP_ACTION_SEND_REACTIONS
                    || NaConfig.INSTANCE.getDoubleTapActionOut().Int() == DoubleTap.DOUBLE_TAP_ACTION_SEND_REACTIONS;
            if (hadReaction != hasReaction) {
                reloadList();
            } else {
                listAdapter.notifyItemChanged(position);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /**
     * «Безлимит недавних стикеров» из exteraGram — один тумблер поверх двух рабочих ключей
     * NagramX: лимита недавних ({@link NekoConfig#maxRecentStickerCount}, шкала 20…200) и
     * безлимитных избранных ({@link NekoConfig#unlimitedFavedStickers}).
     */
    private static final int RECENT_STICKERS_DEFAULT = 20;
    private static final int RECENT_STICKERS_MAX = 200;

    private static boolean isUnlimitedRecentStickers() {
        return NekoConfig.maxRecentStickerCount.Int() > RECENT_STICKERS_DEFAULT;
    }

    private void toggleUnlimitedRecentStickers(View view) {
        boolean value = !isUnlimitedRecentStickers();
        NekoConfig.maxRecentStickerCount.setConfigInt(value ? RECENT_STICKERS_MAX : RECENT_STICKERS_DEFAULT);
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
    }

    /**
     * «Быстрые действия администратора» из exteraGram — один тумблер поверх пяти пунктов меню чата
     * NagramX ({@code NaConfig.shortcuts*}) и пункта «Права администратора» в меню сообщения
     * ({@link NekoConfig#showAdminActions}). Включён, пока включён хотя бы один пункт.
     */
    private static ConfigItem[] adminShortcutItems() {
        return new ConfigItem[]{
                NaConfig.INSTANCE.getShortcutsAdministrators(),
                NaConfig.INSTANCE.getShortcutsRecentActions(),
                NaConfig.INSTANCE.getShortcutsStatistics(),
                NaConfig.INSTANCE.getShortcutsPermissions(),
                NaConfig.INSTANCE.getShortcutsMembers(),
                NekoConfig.showAdminActions
        };
    }

    private static boolean isQuickAdminShortcuts() {
        for (ConfigItem item : adminShortcutItems()) {
            if (item.Bool()) {
                return true;
            }
        }
        return false;
    }

    private void toggleQuickAdminShortcuts(View view) {
        boolean value = !isQuickAdminShortcuts();
        for (ConfigItem item : adminShortcutItems()) {
            item.setConfigBool(value);
        }
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
    }

    private void toggleHighQualityPhoto(View view) {
        boolean value = !ChatsConfig.alwaysSendInHD.Bool();
        ChatsConfig.alwaysSendInHD.setConfigBool(value);
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        // Клик по телу строки-группы только сворачивает её; мастер-переключатель живёт
        // в правой зоне TextCheckCell2 за разделителем.
        if (position == repliesGroupRow) {
            repliesExpanded = !repliesExpanded;
            reloadList();
            return;
        } else if (position == hideReactionsGroupRow) {
            hideReactionsExpanded = !hideReactionsExpanded;
            reloadList();
            return;
        } else if (position == quickTransitionGroupRow) {
            quickTransitionExpanded = !quickTransitionExpanded;
            reloadList();
            return;
        } else if (position == messageMenuGroupRow) {
            messageMenuExpanded = !messageMenuExpanded;
            reloadList();
            return;
        } else if (position == mediaViewerMenuGroupRow) {
            mediaViewerMenuExpanded = !mediaViewerMenuExpanded;
            reloadList();
            return;
        } else if (position == actionBarButtonsGroupRow) {
            actionBarButtonsExpanded = !actionBarButtonsExpanded;
            reloadList();
            return;
        } else if (position == extendedSettingsGroupRow) {
            extendedSettingsExpanded = !extendedSettingsExpanded;
            reloadList();
            return;
        } else if (position == pauseGroupRow) {
            pauseExpanded = !pauseExpanded;
            reloadList();
            return;
        }

        if (groupHeaderFor(position) != -1) {
            onGroupItemClick(view, position);
            return;
        }

        // Строки с выбором из списка
        if (position == doubleTapIncomingRow) {
            showDoubleTapOptions(position, false);
            return;
        } else if (position == doubleTapOutgoingRow) {
            showDoubleTapOptions(position, true);
            return;
        } else if (position == doubleTapReactionRow) {
            DoubleTapCell.SetReactionCell.showSelectStatusDialog((DoubleTapCell.SetReactionCell) view, this);
            return;
        } else if (position == bottomButtonRow) {
            showOptions(view, bottomButtonOptions(), index -> {
                setBottomButton(index);
                listAdapter.notifyItemChanged(position);
                rebuildChats();
            });
            return;
        } else if (position == cameraTypeRow) {
            showOptions(view, cameraTypeOptions(), index -> {
                setCameraTypeIndex(index);
                // от типа камеры зависит, показывать ли группу расширенных настроек
                reloadList();
            });
            return;
        } else if (position == videoMessagesCameraRow) {
            showOptions(view, videoMessagesCameraOptions(), index -> {
                NaConfig.INSTANCE.getCameraInVideoMessages().setConfigInt(index);
                // от варианта зависит, показывать ли «запоминать последнюю камеру»
                reloadList();
            });
            return;
        } else if (position == doubleTapSeekDurationRow) {
            showOptions(view, position, seekDurationOptions(), ChatsConfig.doubleTapSeekDuration);
            return;
        } else if (position == alwaysSendHdRow) {
            toggleHighQualityPhoto(view);
            return;
        } else if (position == unlimitedRecentStickersRow) {
            toggleUnlimitedRecentStickers(view);
            return;
        } else if (position == adminShortcutsRow) {
            toggleQuickAdminShortcuts(view);
            return;
        } else if (position == chatSettingsRow) {
            presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC));
            return;
        } else if (position == aiChatRow) {
            presentFragment(new app.exteraless.ai.ui.AiSettingsActivity());
            return;
        }

        if (position == tapToSwitchRecordRow) {
            boolean tapToSwitch = NekoConfig.useChatAttachMediaMenu.Bool();
            NekoConfig.useChatAttachMediaMenu.setConfigBool(!tapToSwitch);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(tapToSwitch);
            }
            return;
        }

        ConfigItem item = configForRow(position);
        if (item == null) {
            return;
        }
        boolean value = item.toggleConfigBool();
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
        if (position == hideTimeOnStickersRow && stickerSizeCell != null) {
            stickerSizeCell.invalidate();
        } else if (position == removeMessageTailRow) {
            // Пузырь рисуется закешированным drawable — без сброса эффекта не видно.
            // Обнуление и пересоздание — строго вместе: Theme.createChatResources
            // восстанавливает весь блок именно по условию chat_msgInDrawable == null.
            // Обнулить без активити означало бы оставить статик null и уронить
            // отрисовку первого же пузыря.
            android.app.Activity activity = getParentActivity();
            if (activity != null) {
                Theme.chat_msgInDrawable = null;
                Theme.createChatResources(activity, false);
            }
            rebuildChats();
        } else if (position == showResultsBeforeVotingRow) {
            rebuildChats();
        } else if (position == wideChannelPostsRow) {
            if (wideChannelPostsPreviewCell != null) {
                wideChannelPostsPreviewCell.setWide(value, true);
            }
            rebuildChats();
        } else if (position == wideFeedPostsRow) {
            rebuildChats();
        }
    }

    /** Клик по вложенному пункту группы: переключить, обновить счётчик заголовка, применить. */
    private void onGroupItemClick(View view, int position) {
        boolean value = !isRowChecked(position);
        setRowChecked(position, value);
        if (view instanceof CheckBoxCell) {
            ((CheckBoxCell) view).setChecked(value, true);
        }
        int header = groupHeaderFor(position);
        if (header != -1 && listAdapter != null) {
            listAdapter.notifyItemChanged(header);
        }
        if (header == repliesGroupRow || header == hideReactionsGroupRow) {
            if (stickerSizeCell != null) {
                stickerSizeCell.invalidate();
            }
            rebuildChats();
        } else if (header == messageMenuGroupRow) {
            // Меню сообщения собирается при создании фрагмента чата.
            rebuildChats();
        }
    }

    /** Заголовок группы, которой принадлежит строка, либо -1 для одиночных строк. */
    private int groupHeaderFor(int position) {
        if (position < 0) {
            return -1;
        }
        if (position == replyColorsRow || position == replyEmojiRow || position == replyBackgroundRow) {
            return repliesGroupRow;
        } else if (position == hideReactionsChannelsRow || position == hideReactionsGroupsRow
                || position == hideReactionsPrivateRow) {
            return hideReactionsGroupRow;
        } else if (position == quickTransitionChannelsRow || position == quickTransitionTopicsRow) {
            return quickTransitionGroupRow;
        } else if (position == menuCopyPhotoRow || position == menuSaveRow || position == menuRepeatRow
                || position == menuClearRow || position == menuHistoryRow || position == menuReportRow
                || position == menuDetailsRow || position == menuReactionsRow
                || position == menuReplyInPrivateRow || position == menuCopyLinkRow
                || position == menuCopyFrameRow || position == menuCopyAsStickerRow
                || position == menuAddToStickersRow || position == menuAddToFavoritesRow
                || position == menuNoQuoteForwardRow || position == menuSetReminderRow
                || position == menuBookmarkRow || position == menuRepeatAsCopyRow
                || position == menuTranslateRow || position == menuTranslateLlmRow
                || position == menuShareRow || position == menuHideRow
                || position == menuAdminActionsRow || position == menuPermissionsRow) {
            return messageMenuGroupRow;
        } else if (position == mediaMenuForwardRow || position == mediaMenuNoQuoteForwardRow
                || position == mediaMenuCopyFrameRow || position == mediaMenuCopyPhotoRow
                || position == mediaMenuProfilePhotoRow || position == mediaMenuQrRow) {
            return mediaViewerMenuGroupRow;
        } else if (position == actionBarReplyRow || position == actionBarEditRow
                || position == actionBarSelectBetweenRow || position == actionBarCopyRow
                || position == actionBarForwardRow) {
            return actionBarButtonsGroupRow;
        } else if (position == seamlessSwitchingRow || position == extendedFpsRow
                || position == cameraStabilizationRow || position == cameraMirrorModeRow
                || position == startWithWideAngleRow) {
            return extendedSettingsGroupRow;
        } else if (position == pauseVideoRow || position == pauseVoiceRow || position == pauseRoundRow) {
            return pauseGroupRow;
        }
        return -1;
    }

    private boolean isRowChecked(int position) {
        if (position == quickTransitionChannelsRow) return quickTransitionForChannels();
        if (position == quickTransitionTopicsRow) return quickTransitionForTopics();
        if (position == seamlessSwitchingRow) return isSeamlessSwitchingEnabled();
        ConfigItem item = configForRow(position);
        return item != null && item.Bool();
    }

    private void setRowChecked(int position, boolean value) {
        if (position == quickTransitionChannelsRow) {
            NekoConfig.disableSwipeToNext.setConfigBool(!value);
            return;
        }
        if (position == quickTransitionTopicsRow) {
            NekoConfig.disableSwipeToNextTopic.setConfigBool(!value);
            return;
        }
        if (position == seamlessSwitchingRow) {
            setSeamlessSwitching(value);
            return;
        }
        ConfigItem item = configForRow(position);
        if (item != null) {
            item.setConfigBool(value);
        }
    }

    private ConfigItem configForRow(int position) {
        if (position == hideTimeOnStickersRow) return NekoConfig.hideTimeForSticker;
        if (position == replyColorsRow) return ChatsConfig.replyColors;
        if (position == replyEmojiRow) return ChatsConfig.replyEmoji;
        if (position == replyBackgroundRow) return ChatsConfig.replyBackground;
        if (position == hideReactionsChannelsRow) return ChatsConfig.hideReactionsInChannels;
        if (position == hideReactionsGroupsRow) return ChatsConfig.hideReactionsInGroups;
        if (position == hideReactionsPrivateRow) return ChatsConfig.hideReactionsInPrivate;
        if (position == disableGreetingRow) return NekoConfig.dontSendGreetingSticker;
        if (position == hideKeyboardOnScrollRow) return NekoConfig.hideKeyboardOnChatScroll;
        if (position == addCommaRow) return OpenExteraConfig.addCommaAfterMention;
        if (position == hideSendAsPeerRow) return NekoConfig.hideSendAsChannel;
        if (position == keepAttachButtonRow) return ChatsConfig.keepAttachButton;
        if (position == removeMessageTailRow) return ChatsConfig.removeMessageTail;
        if (position == replaceEditedRow) return NaConfig.INSTANCE.getUseEditedIcon();
        if (position == showOnlineStatusRow) return NaConfig.INSTANCE.getShowOnlineStatus();
        if (position == hideShareButtonRow) return NaConfig.INSTANCE.getHideShareButtonInChannel();
        if (position == showResultsBeforeVotingRow) return ChatsConfig.showResultsBeforeVoting;
        if (position == menuCopyPhotoRow) return NaConfig.INSTANCE.getShowCopyPhoto();
        if (position == menuSaveRow) return NekoConfig.showAddToSavedMessages;
        if (position == menuRepeatRow) return NekoConfig.showRepeat;
        if (position == menuClearRow) return NekoConfig.showDeleteDownloadedFile;
        if (position == menuHistoryRow) return NekoConfig.showViewHistory;
        if (position == menuReportRow) return NekoConfig.showReport;
        if (position == menuDetailsRow) return NekoConfig.showMessageDetails;
        if (position == menuReactionsRow) return NaConfig.INSTANCE.getShowReactions();
        if (position == menuReplyInPrivateRow) return NaConfig.INSTANCE.getShowReplyInPrivate();
        if (position == menuCopyLinkRow) return NaConfig.INSTANCE.getShowCopyLink();
        if (position == menuCopyFrameRow) return NaConfig.INSTANCE.getShowCopyFrame();
        if (position == menuCopyAsStickerRow) return NaConfig.INSTANCE.getShowCopyAsSticker();
        if (position == menuAddToStickersRow) return NaConfig.INSTANCE.getShowAddToStickers();
        if (position == menuAddToFavoritesRow) return NaConfig.INSTANCE.getShowAddToFavorites();
        if (position == menuNoQuoteForwardRow) return NaConfig.INSTANCE.getShowNoQuoteForward();
        if (position == menuSetReminderRow) return NaConfig.INSTANCE.getShowSetReminder();
        if (position == menuBookmarkRow) return NaConfig.INSTANCE.getShowAddToBookmark();
        if (position == menuRepeatAsCopyRow) return NaConfig.INSTANCE.getShowRepeatAsCopy();
        if (position == menuTranslateRow) return NekoConfig.showTranslate;
        if (position == menuTranslateLlmRow) return NaConfig.INSTANCE.getShowTranslateMessageLLM();
        if (position == menuShareRow) return NekoConfig.showShareMessages;
        if (position == menuHideRow) return NekoConfig.showMessageHide;
        if (position == menuAdminActionsRow) return NekoConfig.showAdminActions;
        if (position == menuPermissionsRow) return NekoConfig.showChangePermissions;
        if (position == mediaMenuForwardRow) return NaConfig.INSTANCE.getMediaViewerMenuItemForward();
        if (position == mediaMenuNoQuoteForwardRow) return NaConfig.INSTANCE.getMediaViewerMenuItemNoQuoteForward();
        if (position == mediaMenuCopyFrameRow) return NaConfig.INSTANCE.getMediaViewerMenuItemCopyFrame();
        if (position == mediaMenuCopyPhotoRow) return NaConfig.INSTANCE.getMediaViewerMenuItemCopyPhoto();
        if (position == mediaMenuProfilePhotoRow) return NaConfig.INSTANCE.getMediaViewerMenuItemSetProfilePhoto();
        if (position == mediaMenuQrRow) return NaConfig.INSTANCE.getMediaViewerMenuItemScanQRCode();
        if (position == actionBarReplyRow) return NaConfig.INSTANCE.getActionBarButtonReply();
        if (position == actionBarEditRow) return NaConfig.INSTANCE.getActionBarButtonEdit();
        if (position == actionBarSelectBetweenRow) return NaConfig.INSTANCE.getActionBarButtonSelectBetween();
        if (position == actionBarCopyRow) return NaConfig.INSTANCE.getActionBarButtonCopy();
        if (position == actionBarForwardRow) return NaConfig.INSTANCE.getActionBarButtonForward();
        if (position == groupedMessageMenuRow) return NaConfig.INSTANCE.getGroupedMessageMenu();
        if (position == wideChannelPostsRow) return ChatsConfig.wideChannelPosts;
        if (position == wideFeedPostsRow) return ChatsConfig.wideFeedPosts;
        if (position == extendedFpsRow) return ChatsConfig.extendedFramesPerSecond;
        if (position == cameraStabilizationRow) return ChatsConfig.cameraStabilization;
        if (position == cameraMirrorModeRow) return ChatsConfig.cameraMirrorMode;
        if (position == startWithWideAngleRow) return ChatsConfig.startWithWideAngleCamera;
        if (position == rememberLastUsedCameraRow) return ChatsConfig.rememberLastUsedCamera;
        if (position == zoomSliderRow) return ChatsConfig.zoomSlider;
        if (position == staticZoomRow) return ChatsConfig.staticZoom;
        if (position == hideCameraTileRow) return ChatsConfig.hideCameraTile;
        if (position == preferOriginalQualityRow) return ChatsConfig.preferOriginalQuality;
        if (position == swipeToPipRow) return ChatsConfig.swipeToPip;
        if (position == unmuteWithVolumeButtonsRow) return ChatsConfig.unmuteWithVolumeButtons;
        if (position == pauseVideoRow) return NekoConfig.autoPauseVideo;
        if (position == pauseVoiceRow) return ChatsConfig.pauseOnMinimizeVoice;
        if (position == pauseRoundRow) return ChatsConfig.pauseOnMinimizeRound;
        return null;
    }

    /** Слайдер размера стикеров с живым превью переписки. */
    /**
     * Слайдер размера стикеров. Оформление перенесено из exteraGram 12.9.0
     * (AltSeekbar): синий жирный заголовок 15sp, рядом плашка со значением
     * (12sp bold, фон — тот же цвет с alpha 0.15, скругление 4dp), под слайдером
     * серые подписи краёв 13sp. Диапазон 4..20.
     */
    private class StickerSizeCell extends FrameLayout {

        private final StickerSizePreviewMessagesCell messagesCell;
        private final SeekBarView sizeBar;
        private final TextView headerValue;
        private final int startStickerSize = 4;
        private final int endStickerSize = 20;

        public StickerSizeCell(Context context) {
            super(context);
            setWillNotDraw(false);

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
            title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
            title.setText(getString(R.string.StickerSize));
            header.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            headerValue = new TextView(context);
            headerValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            headerValue.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
            headerValue.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
            headerValue.setPadding(dp(5.33f), dp(2), dp(5.33f), dp(2));
            headerValue.setBackground(Theme.createRoundRectDrawable(dp(4),
                    Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader), 0.15f)));
            header.addView(headerValue, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 6, 1, 0, 0));

            addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 21, 17, 21, 0));

            FrameLayout edges = new FrameLayout(context);
            TextView left = new TextView(context);
            left.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            left.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            left.setText(getString(R.string.OEStickerSizeSmall));
            edges.addView(left, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));
            TextView right = new TextView(context);
            right.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            right.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            right.setText(getString(R.string.OEStickerSizeLarge));
            edges.addView(right, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
            addView(edges, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 21, 52, 21, 0));

            sizeBar = new SeekBarView(context);
            sizeBar.setReportChanges(true);
            sizeBar.setSeparatorsCount(endStickerSize - startStickerSize + 1);
            sizeBar.setDelegate((stop, progress) -> {
                NekoConfig.stickerSize.setConfigFloat(startStickerSize
                        + (endStickerSize - startStickerSize) * progress);
                updateValueText();
                StickerSizeCell.this.invalidate();
                showResetItem();
            });
            addView(sizeBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38,
                    Gravity.LEFT | Gravity.TOP, 9, 78, 9, 0));

            messagesCell = new StickerSizePreviewMessagesCell(context, OpenExteraChatsActivity.this);
            addView(messagesCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 0, 126, 0, 0));

            updateValueText();
        }

        private void updateValueText() {
            headerValue.setText(String.valueOf(Math.round(NekoConfig.stickerSize.Float())));
        }

        /** Программная установка размера — ею пользуется кнопка сброса в шапке. */
        void setStickerSize(float size) {
            NekoConfig.stickerSize.setConfigFloat(size);
            sizeBar.setProgress((size - startStickerSize) / (float) (endStickerSize - startStickerSize));
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            sizeBar.setProgress((NekoConfig.stickerSize.Float() - startStickerSize)
                    / (float) (endStickerSize - startStickerSize));
        }

        @Override
        public void invalidate() {
            super.invalidate();
            if (messagesCell != null) messagesCell.invalidate();
            if (sizeBar != null) sizeBar.invalidate();
            updateValueText();
        }
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
                case TYPE_STICKER_SIZE:
                    stickerSizeCell = new StickerSizeCell(mContext);
                    stickerSizeCell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view = stickerSizeCell;
                    break;
                case TYPE_STICKER_SHAPE:
                    stickerShapeCell = new StickerShapeCell(mContext) {
                        @Override
                        protected void updateStickerPreview() {
                            if (stickerSizeCell != null) {
                                stickerSizeCell.invalidate();
                            }
                        }
                    };
                    view = stickerShapeCell;
                    break;
                case TYPE_DOUBLE_TAP:
                    doubleTapCell = new DoubleTapCell(mContext);
                    view = doubleTapCell;
                    break;
                case TYPE_WIDE_CHANNEL_PREVIEW:
                    wideChannelPostsPreviewCell = new WideChannelPostsPreviewCell(mContext,
                            OpenExteraChatsActivity.this);
                    view = wideChannelPostsPreviewCell;
                    break;
                case TYPE_SET_REACTION:
                    view = new DoubleTapCell.SetReactionCell(mContext);
                    break;
                case TYPE_EXPANDABLE_SWITCH:
                    view = new TextCheckCell2(mContext);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_ROUND_CHECK: {
                    // Тип 4 — круглая галочка с отступом под вложенный пункт.
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
            if (type == TYPE_STICKER_SIZE || type == TYPE_STICKER_SHAPE || type == TYPE_DOUBLE_TAP
                    || type == TYPE_WIDE_CHANNEL_PREVIEW) {
                return false;
            }
            if (type == TYPE_SET_REACTION || type == TYPE_EXPANDABLE_SWITCH || type == TYPE_ROUND_CHECK) {
                return true;
            }
            return super.isEnabled(holder);
        }

        /**
         * Без этого карточка-секция обрывается на каждой строке-группе: базовый класс
         * знает только про свои типы, а 104/105 — обычные строки той же секции.
         */
        @Override
        protected boolean isSectionContent(int viewType) {
            if (viewType == TYPE_EXPANDABLE_SWITCH || viewType == TYPE_ROUND_CHECK
                    || viewType == TYPE_WIDE_CHANNEL_PREVIEW) {
                return true;
            }
            return super.isSectionContent(viewType);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_SET_REACTION:
                    ((DoubleTapCell.SetReactionCell) holder.itemView).update(false);
                    break;
                case TYPE_WIDE_CHANNEL_PREVIEW:
                    ((WideChannelPostsPreviewCell) holder.itemView)
                            .setWide(ChatsConfig.wideChannelPosts.Bool(), false);
                    break;
                case TYPE_HEADER:
                    bindHeader((HeaderCell) holder.itemView, position);
                    break;
                case TYPE_CHECK:
                    bindCheck((TextCheckCell) holder.itemView, position);
                    break;
                case TYPE_EXPANDABLE_SWITCH:
                    bindGroupHeader((TextCheckCell2) holder.itemView, position);
                    break;
                case TYPE_ROUND_CHECK:
                    bindRoundCheck((CheckBoxCell) holder.itemView, position);
                    break;
                case TYPE_TEXT:
                    bindText((TextCell) holder.itemView, position);
                    break;
                case TYPE_SETTINGS:
                    bindSettings((TextSettingsCell) holder.itemView, position);
                    break;
                case TYPE_INFO_PRIVACY:
                    bindInfo((TextInfoPrivacyCell) holder.itemView, position);
                    break;
            }
        }

        private void bindHeader(HeaderCell cell, int position) {
            if (position == stickerShapeHeaderRow) {
                cell.setText(getString(R.string.OEChatsStickerShape));
            } else if (position == stickersHeaderRow) {
                cell.setText(getString(R.string.OEChatsStickersAndEmoji));
            } else if (position == doubleTapHeaderRow) {
                cell.setText(getString(R.string.OEChatsDoubleTap));
            } else if (position == chatsHeaderRow) {
                cell.setText(getString(R.string.OpenExteraChats));
            } else if (position == messagesHeaderRow) {
                cell.setText(getString(R.string.OEChatsMessages));
            } else if (position == channelPostsHeaderRow) {
                cell.setText(getString(R.string.OEChatsChannelPosts));
            } else if (position == cameraHeaderRow) {
                cell.setText(getString(R.string.VoipCamera));
            } else if (position == photoHeaderRow) {
                cell.setText(getString(R.string.OEChatsPhoto));
            } else if (position == videosHeaderRow) {
                cell.setText(getString(R.string.OEChatsVideos));
            }
        }

        /**
         * Заголовок сворачиваемой группы: мастер-переключатель слева от шеврона,
         * счётчик «N/M» рядом с ним.
         */
        private void bindGroupHeader(TextCheckCell2 cell, int position) {
            // Иначе заголовок группы красный, пока в ней ничего не выбрано:
            // Switch по умолчанию идёт в «разрешительных» цветах экрана прав.
            cell.useStandardSwitchColors();
            // Третий аргумент setTextAndCheck — разделитель. У свёрнутой группы
            // заголовок оказывается последней строкой карточки, и линия под ним
            // висела бы в воздухе; поэтому разделитель = «группа раскрыта».
            if (position == repliesGroupRow) {
                int selected = repliesSelectedCount();
                cell.setTextAndCheck(getString(R.string.OEChatsReplies), selected > 0, repliesExpanded);
                cell.setCollapseArrow(ratio(selected, REPLIES_TOTAL), !repliesExpanded,
                        OpenExteraChatsActivity.this::toggleAllReplies);
            } else if (position == hideReactionsGroupRow) {
                int selected = hideReactionsSelectedCount();
                cell.setTextAndCheck(getString(R.string.OEChatsHideReactions), selected > 0, hideReactionsExpanded);
                cell.setCollapseArrow(ratio(selected, HIDE_REACTIONS_TOTAL), !hideReactionsExpanded,
                        OpenExteraChatsActivity.this::toggleAllHideReactions);
            } else if (position == quickTransitionGroupRow) {
                int selected = quickTransitionsSelectedCount();
                cell.setTextAndCheck(getString(R.string.OEChatsQuickTransitions), selected > 0, quickTransitionExpanded);
                cell.setCollapseArrow(ratio(selected, QUICK_TRANSITIONS_TOTAL), !quickTransitionExpanded,
                        OpenExteraChatsActivity.this::toggleAllQuickTransitions);
            } else if (position == messageMenuGroupRow) {
                int selected = messageMenuSelectedCount();
                cell.setTextAndCheck(getString(R.string.MessageMenu), selected > 0, messageMenuExpanded);
                cell.setCollapseArrow(ratio(selected, MESSAGE_MENU_TOTAL), !messageMenuExpanded,
                        OpenExteraChatsActivity.this::toggleAllMessageMenu);
            } else if (position == mediaViewerMenuGroupRow) {
                int selected = mediaViewerMenuSelectedCount();
                cell.setTextAndCheck(getString(R.string.MediaViewerMenu), selected > 0, mediaViewerMenuExpanded);
                cell.setCollapseArrow(ratio(selected, MEDIA_VIEWER_MENU_TOTAL), !mediaViewerMenuExpanded,
                        OpenExteraChatsActivity.this::toggleAllMediaViewerMenu);
            } else if (position == actionBarButtonsGroupRow) {
                int selected = actionBarButtonsSelectedCount();
                cell.setTextAndCheck(getString(R.string.ActionBarButtons), selected > 0, actionBarButtonsExpanded);
                cell.setCollapseArrow(ratio(selected, ACTION_BAR_BUTTONS_TOTAL), !actionBarButtonsExpanded,
                        OpenExteraChatsActivity.this::toggleAllActionBarButtons);
            } else if (position == extendedSettingsGroupRow) {
                int selected = cameraSettingsSelected();
                cell.setTextAndCheck(getString(R.string.OEChatsExtendedSettings), selected > 0, extendedSettingsExpanded);
                cell.setCollapseArrow(ratio(selected, cameraSettingsTotal()), !extendedSettingsExpanded,
                        OpenExteraChatsActivity.this::toggleAllCameraSettings);
            } else if (position == pauseGroupRow) {
                int selected = pauseSelectedCount();
                cell.setTextAndCheck(getString(R.string.OEChatsPauseOnMinimize), selected > 0, pauseExpanded);
                cell.setCollapseArrow(ratio(selected, PAUSE_TOTAL), !pauseExpanded,
                        OpenExteraChatsActivity.this::toggleAllPause);
            }
        }

        private void bindRoundCheck(CheckBoxCell cell, int position) {
            if (position == replyColorsRow) {
                cell.setText(getString(R.string.OEChatsReplyColors), "", ChatsConfig.replyColors.Bool(), true, true);
            } else if (position == replyEmojiRow) {
                cell.setText(getString(R.string.OEChatsReplyEmoji), "", ChatsConfig.replyEmoji.Bool(), true, true);
            } else if (position == replyBackgroundRow) {
                cell.setText(getString(R.string.OEChatsReplyBackground), "", ChatsConfig.replyBackground.Bool(), false, true);
            } else if (position == hideReactionsChannelsRow) {
                cell.setText(getString(R.string.OEChatsHideReactionsChannels), "", ChatsConfig.hideReactionsInChannels.Bool(), true, true);
            } else if (position == hideReactionsGroupsRow) {
                cell.setText(getString(R.string.OEChatsHideReactionsGroups), "", ChatsConfig.hideReactionsInGroups.Bool(), true, true);
            } else if (position == hideReactionsPrivateRow) {
                cell.setText(getString(R.string.OEChatsHideReactionsPrivate), "", ChatsConfig.hideReactionsInPrivate.Bool(), false, true);
            } else if (position == quickTransitionChannelsRow) {
                cell.setText(getString(R.string.OEChatsQuickTransitionChannels), "", quickTransitionForChannels(), true, true);
            } else if (position == quickTransitionTopicsRow) {
                cell.setText(getString(R.string.OEChatsQuickTransitionTopics), "", quickTransitionForTopics(), true, true);
            } else if (position == menuCopyPhotoRow) {
                cell.setText(getString(R.string.OEChatsMenuCopyPhoto), "", NaConfig.INSTANCE.getShowCopyPhoto().Bool(), true, true);
            } else if (position == menuSaveRow) {
                cell.setText(getString(R.string.OEChatsMenuSave), "", NekoConfig.showAddToSavedMessages.Bool(), true, true);
            } else if (position == menuRepeatRow) {
                cell.setText(getString(R.string.OEChatsMenuRepeat), "", NekoConfig.showRepeat.Bool(), true, true);
            } else if (position == menuClearRow) {
                cell.setText(getString(R.string.OEChatsMenuClear), "", NekoConfig.showDeleteDownloadedFile.Bool(), true, true);
            } else if (position == menuHistoryRow) {
                cell.setText(getString(R.string.OEChatsMenuHistory), "", NekoConfig.showViewHistory.Bool(), true, true);
            } else if (position == menuReportRow) {
                cell.setText(getString(R.string.OEChatsMenuReport), "", NekoConfig.showReport.Bool(), true, true);
            } else if (position == menuDetailsRow) {
                cell.setText(getString(R.string.OEChatsMenuDetails), "", NekoConfig.showMessageDetails.Bool(), true, true);
            } else if (position == menuReactionsRow) {
                cell.setText(getString(R.string.Reactions), "", NaConfig.INSTANCE.getShowReactions().Bool(), true, true);
            } else if (position == menuReplyInPrivateRow) {
                cell.setText(getString(R.string.ReplyInPrivate), "", NaConfig.INSTANCE.getShowReplyInPrivate().Bool(), true, true);
            } else if (position == menuCopyLinkRow) {
                cell.setText(getString(R.string.CopyLink), "", NaConfig.INSTANCE.getShowCopyLink().Bool(), true, true);
            } else if (position == menuCopyFrameRow) {
                cell.setText(getString(R.string.CopyVideoFrame), "", NaConfig.INSTANCE.getShowCopyFrame().Bool(), true, true);
            } else if (position == menuCopyAsStickerRow) {
                cell.setText(getString(R.string.CopyPhotoAsSticker), "", NaConfig.INSTANCE.getShowCopyAsSticker().Bool(), true, true);
            } else if (position == menuAddToStickersRow) {
                cell.setText(getString(R.string.AddToStickers), "", NaConfig.INSTANCE.getShowAddToStickers().Bool(), true, true);
            } else if (position == menuAddToFavoritesRow) {
                cell.setText(getString(R.string.AddToFavorites), "", NaConfig.INSTANCE.getShowAddToFavorites().Bool(), true, true);
            } else if (position == menuNoQuoteForwardRow) {
                cell.setText(getString(R.string.NoQuoteForward), "", NaConfig.INSTANCE.getShowNoQuoteForward().Bool(), true, true);
            } else if (position == menuSetReminderRow) {
                cell.setText(getString(R.string.SetReminder), "", NaConfig.INSTANCE.getShowSetReminder().Bool(), true, true);
            } else if (position == menuBookmarkRow) {
                cell.setText(getString(R.string.AddBookmark), "", NaConfig.INSTANCE.getShowAddToBookmark().Bool(), true, true);
            } else if (position == menuRepeatAsCopyRow) {
                cell.setText(getString(R.string.RepeatAsCopy), "", NaConfig.INSTANCE.getShowRepeatAsCopy().Bool(), true, true);
            } else if (position == menuTranslateRow) {
                cell.setText(getString(R.string.Translate), "", NekoConfig.showTranslate.Bool(), true, true);
            } else if (position == menuTranslateLlmRow) {
                cell.setText(getString(R.string.TranslateMessageLLM), "", NaConfig.INSTANCE.getShowTranslateMessageLLM().Bool(), true, true);
            } else if (position == menuShareRow) {
                cell.setText(getString(R.string.ShareMessages), "", NekoConfig.showShareMessages.Bool(), true, true);
            } else if (position == menuHideRow) {
                cell.setText(getString(R.string.Hide), "", NekoConfig.showMessageHide.Bool(), true, true);
            } else if (position == menuAdminActionsRow) {
                cell.setText(getString(R.string.EditAdminRights), "", NekoConfig.showAdminActions.Bool(), true, true);
            } else if (position == menuPermissionsRow) {
                cell.setText(getString(R.string.ChangePermissions), "", NekoConfig.showChangePermissions.Bool(), true, true);
            } else if (position == mediaMenuForwardRow) {
                cell.setText(getString(R.string.Forward), "", NaConfig.INSTANCE.getMediaViewerMenuItemForward().Bool(), true, true);
            } else if (position == mediaMenuNoQuoteForwardRow) {
                cell.setText(getString(R.string.NoQuoteForward), "", NaConfig.INSTANCE.getMediaViewerMenuItemNoQuoteForward().Bool(), true, true);
            } else if (position == mediaMenuCopyFrameRow) {
                cell.setText(getString(R.string.CopyVideoFrame), "", NaConfig.INSTANCE.getMediaViewerMenuItemCopyFrame().Bool(), true, true);
            } else if (position == mediaMenuCopyPhotoRow) {
                cell.setText(getString(R.string.CopyPhoto), "", NaConfig.INSTANCE.getMediaViewerMenuItemCopyPhoto().Bool(), true, true);
            } else if (position == mediaMenuProfilePhotoRow) {
                cell.setText(getString(R.string.SetProfilePhoto), "", NaConfig.INSTANCE.getMediaViewerMenuItemSetProfilePhoto().Bool(), true, true);
            } else if (position == mediaMenuQrRow) {
                cell.setText(getString(R.string.ScanQRCode), "", NaConfig.INSTANCE.getMediaViewerMenuItemScanQRCode().Bool(), true, true);
            } else if (position == actionBarReplyRow) {
                cell.setText(getString(R.string.Reply), "", NaConfig.INSTANCE.getActionBarButtonReply().Bool(), true, true);
            } else if (position == actionBarEditRow) {
                cell.setText(getString(R.string.Edit), "", NaConfig.INSTANCE.getActionBarButtonEdit().Bool(), true, true);
            } else if (position == actionBarSelectBetweenRow) {
                cell.setText(getString(R.string.SelectBetween), "", NaConfig.INSTANCE.getActionBarButtonSelectBetween().Bool(), true, true);
            } else if (position == actionBarCopyRow) {
                cell.setText(getString(R.string.Copy), "", NaConfig.INSTANCE.getActionBarButtonCopy().Bool(), true, true);
            } else if (position == actionBarForwardRow) {
                cell.setText(getString(R.string.Forward), "", NaConfig.INSTANCE.getActionBarButtonForward().Bool(), true, true);
            } else if (position == seamlessSwitchingRow) {
                cell.setText(getString(R.string.OEChatsSeamlessSwitching), "", isSeamlessSwitchingEnabled(), true, true);
            } else if (position == extendedFpsRow) {
                cell.setText(getString(R.string.OEChatsExtendedFps), "", ChatsConfig.extendedFramesPerSecond.Bool(), true, true);
            } else if (position == cameraStabilizationRow) {
                cell.setText(getString(R.string.OEChatsCameraStabilization), "", ChatsConfig.cameraStabilization.Bool(), true, true);
            } else if (position == cameraMirrorModeRow) {
                cell.setText(getString(R.string.OEChatsCameraMirrorMode), "", ChatsConfig.cameraMirrorMode.Bool(), true, true);
            } else if (position == startWithWideAngleRow) {
                cell.setText(getString(R.string.OEChatsStartWithWideAngle), "", ChatsConfig.startWithWideAngleCamera.Bool(), true, true);
            } else if (position == pauseVideoRow) {
                cell.setText(getString(R.string.OEChatsPauseVideo), "", NekoConfig.autoPauseVideo.Bool(), true, true);
            } else if (position == pauseVoiceRow) {
                cell.setText(getString(R.string.OEChatsPauseVoice), "", ChatsConfig.pauseOnMinimizeVoice.Bool(), true, true);
            } else if (position == pauseRoundRow) {
                cell.setText(getString(R.string.OEChatsPauseRound), "", ChatsConfig.pauseOnMinimizeRound.Bool(), false, true);
            }
            cell.setPad(1);
            // По умолчанию ячейка этого типа красит текст серым; вложенные пункты
            // должны быть того же цвета, что и обычные строки.
            cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        }

        private void bindCheck(TextCheckCell cell, int position) {
            if (position == hideTimeOnStickersRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsHideTimeOnStickers), NekoConfig.hideTimeForSticker.Bool(), true);
            } else if (position == unlimitedRecentStickersRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsUnlimitedRecentStickers), isUnlimitedRecentStickers(), true);
            } else if (position == adminShortcutsRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsAdminShortcuts), isQuickAdminShortcuts(), true);
            } else if (position == disableGreetingRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsDisableGreetingSticker), NekoConfig.dontSendGreetingSticker.Bool(), true);
            } else if (position == hideKeyboardOnScrollRow) {
                cell.setTextAndCheck(getString(R.string.HideKeyboardOnChatScroll), NekoConfig.hideKeyboardOnChatScroll.Bool(), true);
            } else if (position == addCommaRow) {
                cell.setTextAndCheck(getString(R.string.AddCommaAfterMention), OpenExteraConfig.addCommaAfterMention.Bool(), true);
            } else if (position == hideSendAsPeerRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsHideSendAsPeer), NekoConfig.hideSendAsChannel.Bool(), true);
            } else if (position == tapToSwitchRecordRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsTapToSwitchRecord), !NekoConfig.useChatAttachMediaMenu.Bool(), true);
            } else if (position == keepAttachButtonRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsKeepAttachButton), ChatsConfig.keepAttachButton.Bool(), false);
            } else if (position == removeMessageTailRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsRemoveMessageTail), ChatsConfig.removeMessageTail.Bool(), true);
            } else if (position == replaceEditedRow) {
                // В подпись подставляется локализованное «edited».
                cell.setTextAndCheck(LocaleController.formatString(R.string.OEChatsReplaceEditedWithIcon,
                        getString(R.string.EditedMessage)), NaConfig.INSTANCE.getUseEditedIcon().Bool(), true);
            } else if (position == showOnlineStatusRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsShowOnlineStatus), NaConfig.INSTANCE.getShowOnlineStatus().Bool(), true);
            } else if (position == hideShareButtonRow) {
                // В подпись подставляется название кнопки «Share».
                cell.setTextAndCheck(LocaleController.formatString(R.string.OEChatsHideShareButton,
                        getString(R.string.ShareFile)), NaConfig.INSTANCE.getHideShareButtonInChannel().Bool(), true);
            } else if (position == showResultsBeforeVotingRow) {
                cell.setTextAndValueAndCheck(getString(R.string.OEChatsShowResultsBeforeVoting),
                        getString(R.string.OEChatsShowResultsBeforeVotingInfo),
                        ChatsConfig.showResultsBeforeVoting.Bool(), true, true);
            } else if (position == wideChannelPostsRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsWideChannelPosts),
                        ChatsConfig.wideChannelPosts.Bool(), true, true);
            } else if (position == wideFeedPostsRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsWideFeedPosts),
                        ChatsConfig.wideFeedPosts.Bool(), false, true);
            } else if (position == groupedMessageMenuRow) {
                cell.setTextAndCheck(getString(R.string.GroupedMessageMenu), NaConfig.INSTANCE.getGroupedMessageMenu().Bool(), false);
            } else if (position == rememberLastUsedCameraRow) {
                cell.setTextAndValueAndCheck(getString(R.string.OEChatsRememberLastUsedCamera),
                        getString(R.string.OEChatsRememberLastUsedCameraInfo),
                        ChatsConfig.rememberLastUsedCamera.Bool(), true, true);
            } else if (position == zoomSliderRow) {
                cell.setTextAndValueAndCheck(getString(R.string.OEChatsZoomSlider),
                        getString(R.string.OEChatsZoomSliderInfo),
                        ChatsConfig.zoomSlider.Bool(), true, true);
            } else if (position == staticZoomRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsStaticZoom), ChatsConfig.staticZoom.Bool(), false);
            } else if (position == alwaysSendHdRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsAlwaysSendInHD), ChatsConfig.alwaysSendInHD.Bool(), true);
            } else if (position == hideCameraTileRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsHideCameraTile), ChatsConfig.hideCameraTile.Bool(), false);
            } else if (position == preferOriginalQualityRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsPreferOriginalQuality), ChatsConfig.preferOriginalQuality.Bool(), true);
            } else if (position == swipeToPipRow) {
                cell.setTextAndCheck(getString(R.string.OEChatsSwipeToPip), ChatsConfig.swipeToPip.Bool(), true);
            } else if (position == unmuteWithVolumeButtonsRow) {
                cell.setTextAndValueAndCheck(getString(R.string.OEChatsUnmuteWithVolumeButtons),
                        getString(R.string.OEChatsUnmuteWithVolumeButtonsInfo),
                        ChatsConfig.unmuteWithVolumeButtons.Bool(), true, true);
            }
        }

        private void bindText(TextCell cell, int position) {
            if (position == aiChatRow) {
                cell.setTextAndIcon(getString(R.string.OEChatsAiChat), aiChatIcon(), true);
                cell.setSubtitle(getString(R.string.OEChatsAiChatInfo));
            } else if (position == chatSettingsRow) {
                cell.setTextAndIcon(getString(R.string.OEChatsChatSettings), R.drawable.msg_discussion, false);
                cell.setSubtitle(getString(R.string.OEChatsChatSettingsInfo));
            }
            // Обе строки двухстрочные: подпись под заголовком, высота 64,
            // отступ текста от иконки 60. Ставится после setTextAndIcon —
            // тот сбрасывает offsetFromImage.
            cell.heightDp = 64;
            cell.offsetFromImage = 60;
        }

        private void bindSettings(TextSettingsCell cell, int position) {
            if (position == doubleTapIncomingRow) {
                cell.setTextAndValue(getString(R.string.DoubleTapIncoming),
                        DoubleTap.doubleTapActionMap.get(NaConfig.INSTANCE.getDoubleTapAction().Int()), true);
            } else if (position == doubleTapOutgoingRow) {
                cell.setTextAndValue(getString(R.string.DoubleTapOutgoing),
                        DoubleTap.doubleTapActionMap.get(NaConfig.INSTANCE.getDoubleTapActionOut().Int()), doubleTapReactionRow != -1);
            } else if (position == bottomButtonRow) {
                CharSequence[] options = bottomButtonOptions();
                cell.setTextAndValue(getString(R.string.OEChatsBottomButton),
                        options[clampIndex(ChatsConfig.bottomButton(), options.length)], true);
            } else if (position == cameraTypeRow) {
                CharSequence[] options = cameraTypeOptions();
                cell.setTextAndValue(getString(R.string.OEChatsCameraType),
                        options[clampIndex(cameraTypeIndex(), options.length)], true);
            } else if (position == videoMessagesCameraRow) {
                CharSequence[] options = videoMessagesCameraOptions();
                cell.setTextAndValue(getString(R.string.CameraInVideoMessages),
                        options[clampIndex(NaConfig.INSTANCE.getCameraInVideoMessages().Int(), options.length)], true);
            } else if (position == doubleTapSeekDurationRow) {
                CharSequence[] options = seekDurationOptions();
                cell.setTextAndValue(getString(R.string.OEChatsDoubleTapSeekDuration),
                        options[clampIndex(ChatsConfig.doubleTapSeekDuration.Int(), options.length)], true);
            }
        }

        private void bindInfo(TextInfoPrivacyCell cell, int position) {
            boolean bottom = position == videosDividerRow;
            if (position == doubleTapDividerRow) {
                cell.setText(getString(R.string.OEChatsDoubleTapInfo));
            } else if (position == chatsDividerRow) {
                cell.setText(getString(R.string.OEChatsHideSendAsPeerInfo));
            } else if (position == stickersDividerRow) {
                cell.setText(getString(R.string.OEChatsHideReactionsInfo));
            } else if (position == messagesDividerRow) {
                cell.setText(getString(R.string.OEChatsGlassMessageMenuInfo));
            } else if (position == channelPostsDividerRow) {
                cell.setText(getString(R.string.OEChatsWideChannelPostsFooter));
            } else if (position == cameraDividerRow) {
                cell.setText(getString(R.string.OEChatsStaticZoomInfo));
            } else if (position == photoDividerRow) {
                cell.setText(getString(R.string.OEChatsHideCameraTileInfo));
            } else if (position == videosDividerRow) {
                cell.setText(getString(R.string.OEChatsPauseOnMinimizeInfo));
            } else {
                cell.setText(null);
            }
            cell.setBackground(Theme.getThemedDrawable(mContext,
                    bottom ? R.drawable.greydivider_bottom : R.drawable.greydivider,
                    Theme.key_windowBackgroundGrayShadow));
        }

        @Override
        public int getItemViewType(int position) {
            if (position == stickerSizeRow) return TYPE_STICKER_SIZE;
            if (position == stickerShapeRow) return TYPE_STICKER_SHAPE;
            if (position == doubleTapRow) return TYPE_DOUBLE_TAP;
            if (position == wideChannelPostsPreviewRow) return TYPE_WIDE_CHANNEL_PREVIEW;
            if (position == doubleTapReactionRow) return TYPE_SET_REACTION;
            if (isHeader(position)) return TYPE_HEADER;
            if (isDivider(position)) return TYPE_INFO_PRIVACY;
            if (position == aiChatRow || position == chatSettingsRow) return TYPE_TEXT;
            if (isGroupHeader(position)) return TYPE_EXPANDABLE_SWITCH;
            if (groupHeaderFor(position) != -1) return TYPE_ROUND_CHECK;
            if (isSettings(position)) return TYPE_SETTINGS;
            return TYPE_CHECK;
        }

        private boolean isHeader(int position) {
            return position == stickerShapeHeaderRow
                    || position == stickersHeaderRow || position == doubleTapHeaderRow
                    || position == chatsHeaderRow || position == messagesHeaderRow
                    || position == channelPostsHeaderRow
                    || position == cameraHeaderRow
                    || position == photoHeaderRow || position == videosHeaderRow;
        }

        private boolean isDivider(int position) {
            return position == stickerSizeDividerRow || position == stickerShapeDividerRow
                    || position == linksDividerRow || position == stickersDividerRow
                    || position == doubleTapDividerRow || position == chatsDividerRow
                    || position == messagesDividerRow
                    || position == channelPostsDividerRow
                    || position == cameraDividerRow || position == photoDividerRow
                    || position == videosDividerRow;
        }

        private boolean isGroupHeader(int position) {
            return position == repliesGroupRow || position == hideReactionsGroupRow
                    || position == quickTransitionGroupRow || position == messageMenuGroupRow
                    || position == mediaViewerMenuGroupRow || position == actionBarButtonsGroupRow
                    || position == extendedSettingsGroupRow || position == pauseGroupRow;
        }

        private boolean isSettings(int position) {
            return position == doubleTapIncomingRow || position == doubleTapOutgoingRow
                    || position == bottomButtonRow || position == cameraTypeRow
                    || position == videoMessagesCameraRow || position == doubleTapSeekDurationRow;
        }
    }
}
