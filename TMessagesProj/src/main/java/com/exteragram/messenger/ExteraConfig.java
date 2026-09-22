package com.exteragram.messenger;

import android.content.SharedPreferences;
import android.util.Pair;

import com.exteragram.messenger.backup.PreferencesUtils;
import com.google.gson.Gson;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.ui.web.SearchEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.exteraless.OpenExteraConfig;
import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.chats.ChatsConfig;
import app.exteraless.drawer.MainMenuItem;
import app.exteraless.drawer.MainMenuLayout;
import app.exteraless.general.GeneralConfig;
import app.exteraless.icons.BaseIconPacks;
import app.exteraless.icons.IconPacksConfig;
import app.exteraless.plugins.Plugin;
import app.exteraless.utils.UtilsConfig;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

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
 *
 * Настройки, у которых есть аналог в форке, читаются и пишутся через него
 * (AppearanceConfig, ChatsConfig, NaConfig, NekoConfig, PluginsController).
 * Остальные лежат в собственном файле {@code exteraconfig} — плагин видит
 * полный API эталона, значение переживает перезапуск, но на приложение не влияет.
 */
public final class ExteraConfig {

    /** Значения совпадают с exteraGram: 0 — стоковые, 1 — Solar, 2 — Remix. */
    public static final int ICON_PACK_DEFAULT = BaseIconPacks.BASE_DEFAULT;
    public static final int ICON_PACK_SOLAR = BaseIconPacks.BASE_SOLAR;
    public static final int ICON_PACK_REMIX = BaseIconPacks.BASE_REMIX;

    private static final String PREFS_NAME = "exteraconfig";

    private static final Gson GSON = new Gson();

    private static volatile SharedPreferences preferences;
    private static final ArrayList<Integer> mainMenuLayout = new ArrayList<>();
    private static final ArrayList<Integer> mainMenuHiddenItems = new ArrayList<>();
    private static final ArrayList<String> iconPacksLayout = new ArrayList<>();
    private static final ArrayList<String> iconPacksHidden = new ArrayList<>();
    private static final ArrayList<String> doNotMarkAsNew = new ArrayList<>();
    private static final HashMap<String, Long> newFeaturesShowedAt = new HashMap<>();
    private static final SearchEngine yandexSearchEngine = new SearchEngine(
            "Yandex", "https://ya.ru/search/?text=",
            "https://suggestqueries.google.com/complete/search?client=chrome&q=",
            "https://yandex.ru/legal/confidential");
    private static volatile boolean menuLoaded;

    private ExteraConfig() {
    }

    public static SharedPreferences getPreferences() {
        SharedPreferences local = preferences;
        if (local == null) {
            synchronized (ExteraConfig.class) {
                local = preferences;
                if (local == null) {
                    local = ApplicationLoader.applicationContext
                            .getSharedPreferences(PREFS_NAME, 0);
                    preferences = local;
                }
            }
        }
        return local;
    }

    public static SharedPreferences.Editor getEditor() {
        return new MappedEditor(getPreferences().edit());
    }

    public static Gson getGSON() {
        return GSON;
    }

    private static app.exteraless.plugins.PluginsController plugins() {
        return app.exteraless.plugins.PluginsController.getInstance();
    }

    private static void ensureLoaded() {
        AppearanceConfig.ensureLoaded();
        ChatsConfig.ensureLoaded();
        UtilsConfig.ensureLoaded();
    }

    private static <E extends Enum<E>> E enumAt(E[] values, int ordinal, E fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    public static int getTranslationProvider() {
        ensureLoaded();
        return NekoConfig.translationProvider.Int();
    }

    public static void setTranslationProvider(int value) {
        ensureLoaded();
        NekoConfig.translationProvider.setConfigInt(value);
    }

    public static TranslationFormality getTranslationFormality() {
        return enumAt(TranslationFormality.values(),
                getPreferences().getInt("translationFormality", TranslationFormality.NONE.ordinal()), TranslationFormality.NONE);
    }

    public static void setTranslationFormality(TranslationFormality value) {
        getPreferences().edit().putInt("translationFormality", value.ordinal()).apply();
    }

    public static boolean getDisableNumberRounding() {
        ensureLoaded();
        return NekoConfig.disableNumberRounding.Bool();
    }

    public static void setDisableNumberRounding(boolean value) {
        ensureLoaded();
        NekoConfig.disableNumberRounding.setConfigBool(value);
    }

    public static boolean getFormatTimeWithSeconds() {
        ensureLoaded();
        return NekoConfig.showSeconds.Bool();
    }

    public static void setFormatTimeWithSeconds(boolean value) {
        ensureLoaded();
        NekoConfig.showSeconds.setConfigBool(value);
    }

    public static boolean getRelativeLastSeen() {
        ensureLoaded();
        return OpenExteraConfig.relativeLastSeen.Bool();
    }

    public static void setRelativeLastSeen(boolean value) {
        ensureLoaded();
        OpenExteraConfig.relativeLastSeen.setConfigBool(value);
    }

    public static boolean getInAppVibration() {
        ensureLoaded();
        return !NekoConfig.disableVibration.Bool();
    }

    public static void setInAppVibration(boolean value) {
        ensureLoaded();
        NekoConfig.disableVibration.setConfigBool(!value);
    }

    public static boolean getFilterZalgo() {
        ensureLoaded();
        return NaConfig.INSTANCE.getZalgoFilter().Bool();
    }

    public static void setFilterZalgo(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getZalgoFilter().setConfigBool(value);
    }

    public static boolean getUseYandexMaps() {
        return getPreferences().getBoolean("useYandexMaps", false);
    }

    public static void setUseYandexMaps(boolean value) {
        getPreferences().edit().putBoolean("useYandexMaps", value).apply();
    }

    public static int getDownloadSpeedBoost() {
        ensureLoaded();
        return GeneralConfig.downloadSpeedBoost.Int();
    }

    public static void setDownloadSpeedBoost(int value) {
        ensureLoaded();
        GeneralConfig.downloadSpeedBoost.setConfigInt(value);
    }

    public static boolean getUploadSpeedBoost() {
        ensureLoaded();
        return NekoConfig.uploadBoost.Bool();
    }

    public static void setUploadSpeedBoost(boolean value) {
        ensureLoaded();
        NekoConfig.uploadBoost.setConfigBool(value);
    }

    public static boolean getHidePhoneNumber() {
        ensureLoaded();
        return NekoConfig.hidePhone.Bool();
    }

    public static void setHidePhoneNumber(boolean value) {
        ensureLoaded();
        NekoConfig.hidePhone.setConfigBool(value);
    }

    public static int getShowIdAndDc() {
        ensureLoaded();
        return NaConfig.INSTANCE.getIdDcType().Int();
    }

    public static void setShowIdAndDc(int value) {
        ensureLoaded();
        NaConfig.INSTANCE.getIdDcType().setConfigInt(value);
    }

    public static boolean getHideArchiveFolder() {
        ensureLoaded();
        return NaConfig.INSTANCE.getHideArchive().Bool();
    }

    public static void setHideArchiveFolder(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getHideArchive().setConfigBool(value);
    }

    public static boolean getArchiveOnPull() {
        ensureLoaded();
        return NekoConfig.openArchiveOnPull.Bool();
    }

    public static void setArchiveOnPull(boolean value) {
        ensureLoaded();
        NekoConfig.openArchiveOnPull.setConfigBool(value);
    }

    public static boolean getDisableUnarchiveSwipe() {
        ensureLoaded();
        return NaConfig.INSTANCE.getDoNotUnarchiveBySwipe().Bool();
    }

    public static void setDisableUnarchiveSwipe(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getDoNotUnarchiveBySwipe().setConfigBool(value);
    }

    public static int getDoNotUseProxy() {
        ensureLoaded();
        return OpenExteraConfig.proxyDisableConditions.Int();
    }

    public static void setDoNotUseProxy(int value) {
        ensureLoaded();
        OpenExteraConfig.proxyDisableConditions.setConfigInt(value);
    }

    public static String getCustomSavePath() {
        ensureLoaded();
        return NekoConfig.customSavePath.String();
    }

    public static void setCustomSavePath(String value) {
        ensureLoaded();
        NekoConfig.customSavePath.setConfigString(value);
    }

    public static IconPackType getIconPack() {
        ensureLoaded();
        return enumAt(IconPackType.values(), BaseIconPacks.getSelected(), IconPackType.DEFAULT);
    }

    public static void setIconPack(IconPackType value) {
        ensureLoaded();
        BaseIconPacks.setSelected(value.ordinal());
    }

    public static String getEditingIconPackId() {
        ensureLoaded();
        return IconPacksConfig.editingPackId.String();
    }

    public static void setEditingIconPackId(String value) {
        ensureLoaded();
        IconPacksConfig.editingPackId.setConfigString(value);
    }

    public static float getAvatarCorners() {
        ensureLoaded();
        return (float) (AppearanceConfig.avatarCorners.Int());
    }

    public static void setAvatarCorners(float value) {
        ensureLoaded();
        AppearanceConfig.avatarCorners.setConfigInt(Math.round(value));
    }

    public static boolean getSingleCornerRadius() {
        ensureLoaded();
        return AppearanceConfig.singleCornerRadius.Bool();
    }

    public static void setSingleCornerRadius(boolean value) {
        ensureLoaded();
        AppearanceConfig.singleCornerRadius.setConfigBool(value);
    }

    public static DividerStyle getDividerStyle() {
        ensureLoaded();
        return enumAt(DividerStyle.values(), AppearanceConfig.dividerStyle.Int(), DividerStyle.LINE);
    }

    public static void setDividerStyle(DividerStyle value) {
        ensureLoaded();
        AppearanceConfig.dividerStyle.setConfigInt(value.ordinal());
    }

    public static boolean getForceSnow() {
        ensureLoaded();
        return NekoConfig.actionBarDecoration.Int() == 1;
    }

    public static void setForceSnow(boolean value) {
        ensureLoaded();
        applyForceSnow(value);
    }

    public static boolean getHideActionBarStatus() {
        ensureLoaded();
        return AppearanceConfig.hideActionBarStatus.Bool();
    }

    public static void setHideActionBarStatus(boolean value) {
        ensureLoaded();
        AppearanceConfig.hideActionBarStatus.setConfigBool(value);
    }

    public static boolean getCenterTitle() {
        ensureLoaded();
        return AppearanceConfig.centerTitle();
    }

    public static void setCenterTitle(boolean value) {
        ensureLoaded();
        AppearanceConfig.setCenterTitle(value);
    }

    public static boolean getHideStories() {
        ensureLoaded();
        return NaConfig.INSTANCE.getHideStoriesFromHeader().Bool();
    }

    public static void setHideStories(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getHideStoriesFromHeader().setConfigBool(value);
    }

    public static boolean getHideFloatingButton() {
        ensureLoaded();
        return NaConfig.INSTANCE.getDisableDialogsFloatingButton().Bool();
    }

    public static void setHideFloatingButton(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getDisableDialogsFloatingButton().setConfigBool(value);
    }

    public static boolean getHideDialogsSearchBar() {
        ensureLoaded();
        return NaConfig.INSTANCE.getHideDialogsSearchField().Bool();
    }

    public static void setHideDialogsSearchBar(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getHideDialogsSearchField().setConfigBool(value);
    }

    public static boolean getSenderMiniAvatars() {
        ensureLoaded();
        return AppearanceConfig.senderMiniAvatars.Bool();
    }

    public static void setSenderMiniAvatars(boolean value) {
        ensureLoaded();
        AppearanceConfig.senderMiniAvatars.setConfigBool(value);
    }

    public static int getTitleText() {
        ensureLoaded();
        return AppearanceConfig.titleText.Int();
    }

    public static void setTitleText(int value) {
        ensureLoaded();
        AppearanceConfig.titleText.setConfigInt(value);
    }

    public static TabIconsMode getTabIcons() {
        return enumAt(TabIconsMode.values(),
                getPreferences().getInt("tabIcons", TabIconsMode.TITLES_ONLY.ordinal()), TabIconsMode.TITLES_ONLY);
    }

    public static void setTabIcons(TabIconsMode value) {
        getPreferences().edit().putInt("tabIcons", value.ordinal()).apply();
    }

    public static boolean getTabCounter() {
        return getPreferences().getBoolean("tabCounter", true);
    }

    public static void setTabCounter(boolean value) {
        getPreferences().edit().putBoolean("tabCounter", value).apply();
    }

    public static boolean getHideAllChats() {
        ensureLoaded();
        return NekoConfig.hideAllTab.Bool();
    }

    public static void setHideAllChats(boolean value) {
        ensureLoaded();
        NekoConfig.hideAllTab.setConfigBool(value);
    }

    public static boolean getSquareFab() {
        ensureLoaded();
        return AppearanceConfig.squareFab.Bool();
    }

    public static void setSquareFab(boolean value) {
        ensureLoaded();
        AppearanceConfig.squareFab.setConfigBool(value);
    }

    public static float getSectionRadius() {
        ensureLoaded();
        return (float) (AppearanceConfig.sectionRadius.Int());
    }

    public static void setSectionRadius(float value) {
        ensureLoaded();
        AppearanceConfig.sectionRadius.setConfigInt(Math.round(value));
    }

    public static boolean getSectionsSeparatedHeadersPreference() {
        ensureLoaded();
        return AppearanceConfig.separateHeaders.Bool();
    }

    public static void setSectionsSeparatedHeadersPreference(boolean value) {
        ensureLoaded();
        AppearanceConfig.separateHeaders.setConfigBool(value);
    }

    public static boolean getNewLoadingStyle() {
        ensureLoaded();
        return AppearanceConfig.newLoadingStyle.Bool();
    }

    public static void setNewLoadingStyle(boolean value) {
        ensureLoaded();
        AppearanceConfig.newLoadingStyle.setConfigBool(value);
    }

    public static boolean getNewSliderStyle() {
        return getPreferences().getBoolean("newSliderStyle", true);
    }

    public static void setNewSliderStyle(boolean value) {
        getPreferences().edit().putBoolean("newSliderStyle", value).apply();
    }

    public static boolean getNewSwitchStyle() {
        return getPreferences().getBoolean("newSwitchStyle", true);
    }

    public static void setNewSwitchStyle(boolean value) {
        getPreferences().edit().putBoolean("newSwitchStyle", value).apply();
    }

    public static boolean getNewChatHeaderStyle() {
        ensureLoaded();
        return AppearanceConfig.newChatHeaderStyle.Bool();
    }

    public static void setNewChatHeaderStyle(boolean value) {
        ensureLoaded();
        AppearanceConfig.newChatHeaderStyle.setConfigBool(value);
    }

    public static boolean getNewNavigationBarStyle() {
        ensureLoaded();
        return AppearanceConfig.newNavigationBarStyle.Bool();
    }

    public static void setNewNavigationBarStyle(boolean value) {
        ensureLoaded();
        AppearanceConfig.newNavigationBarStyle.setConfigBool(value);
    }

    public static int getTabletMode() {
        ensureLoaded();
        return NekoConfig.tabletMode.Int();
    }

    public static void setTabletMode(int value) {
        ensureLoaded();
        NekoConfig.tabletMode.setConfigInt(value);
    }

    public static boolean getUseSystemFonts() {
        ensureLoaded();
        return NekoConfig.typeface.Bool();
    }

    public static void setUseSystemFonts(boolean value) {
        ensureLoaded();
        NekoConfig.typeface.setConfigBool(value);
    }

    public static boolean getGooeyAvatarAnimation() {
        ensureLoaded();
        return AppearanceConfig.gooeyAvatarAnimation.Bool();
    }

    public static void setGooeyAvatarAnimation(boolean value) {
        ensureLoaded();
        AppearanceConfig.gooeyAvatarAnimation.setConfigBool(value);
    }

    public static boolean getCustomThemes() {
        ensureLoaded();
        return AppearanceConfig.customThemes.Bool();
    }

    public static void setCustomThemes(boolean value) {
        ensureLoaded();
        AppearanceConfig.customThemes.setConfigBool(value);
    }

    public static float getPredictiveBackIntensity() {
        ensureLoaded();
        return UtilsConfig.predictiveBackIntensity.Int() / 100.0f;
    }

    public static void setPredictiveBackIntensity(float value) {
        ensureLoaded();
        UtilsConfig.predictiveBackIntensity.setConfigInt(Math.round(value * 100));
    }

    public static boolean getSpringAnimations() {
        return getPreferences().getBoolean("springAnimations", true);
    }

    public static void setSpringAnimations(boolean value) {
        getPreferences().edit().putBoolean("springAnimations", value).apply();
    }

    public static GlassOutlineStyle getGlassOutlineStyle() {
        ensureLoaded();
        return enumAt(GlassOutlineStyle.values(), AppearanceConfig.glassOutlineStyle.Int(), GlassOutlineStyle.GLARE);
    }

    public static void setGlassOutlineStyle(GlassOutlineStyle value) {
        ensureLoaded();
        AppearanceConfig.glassOutlineStyle.setConfigInt(value.ordinal());
    }

    public static boolean getGlassMessageMenu() {
        ensureLoaded();
        return AppearanceConfig.glassMessageMenu.Bool();
    }

    public static void setGlassMessageMenu(boolean value) {
        ensureLoaded();
        AppearanceConfig.glassMessageMenu.setConfigBool(value);
    }

    public static boolean getForceBlur() {
        return getPreferences().getBoolean("forceBlur", false);
    }

    public static void setForceBlur(boolean value) {
        getPreferences().edit().putBoolean("forceBlur", value).apply();
    }

    public static int getEventType() {
        return getPreferences().getInt("eventType", 0);
    }

    public static void setEventType(int value) {
        getPreferences().edit().putInt("eventType", value).apply();
    }

    public static boolean getNavigationDrawer() {
        ensureLoaded();
        return AppearanceConfig.navigationDrawer.Bool();
    }

    public static void setNavigationDrawer(boolean value) {
        ensureLoaded();
        AppearanceConfig.navigationDrawer.setConfigBool(value);
    }

    public static boolean getImmersiveDrawerAnimation() {
        ensureLoaded();
        return AppearanceConfig.immersiveDrawerAnimation.Bool();
    }

    public static void setImmersiveDrawerAnimation(boolean value) {
        ensureLoaded();
        AppearanceConfig.immersiveDrawerAnimation.setConfigBool(value);
    }

    public static boolean getShowFeedTab() {
        ensureLoaded();
        return AppearanceConfig.showFeedTab.Bool();
    }

    public static void setShowFeedTab(boolean value) {
        ensureLoaded();
        AppearanceConfig.showFeedTab.setConfigBool(value);
    }

    public static boolean getShowFeedUnreadCounter() {
        ensureLoaded();
        return AppearanceConfig.showFeedUnreadCounter.Bool();
    }

    public static void setShowFeedUnreadCounter(boolean value) {
        ensureLoaded();
        AppearanceConfig.showFeedUnreadCounter.setConfigBool(value);
    }

    public static float getStickerSize() {
        ensureLoaded();
        return (float) (NekoConfig.stickerSize.Float());
    }

    public static void setStickerSize(float value) {
        ensureLoaded();
        NekoConfig.stickerSize.setConfigFloat(value);
    }

    public static boolean getHideStickerTime() {
        ensureLoaded();
        return NekoConfig.hideTimeForSticker.Bool();
    }

    public static void setHideStickerTime(boolean value) {
        ensureLoaded();
        NekoConfig.hideTimeForSticker.setConfigBool(value);
    }

    public static boolean getReplyColors() {
        ensureLoaded();
        return ChatsConfig.replyColors.Bool();
    }

    public static void setReplyColors(boolean value) {
        ensureLoaded();
        ChatsConfig.replyColors.setConfigBool(value);
    }

    public static boolean getReplyEmoji() {
        ensureLoaded();
        return ChatsConfig.replyEmoji.Bool();
    }

    public static void setReplyEmoji(boolean value) {
        ensureLoaded();
        ChatsConfig.replyEmoji.setConfigBool(value);
    }

    public static boolean getReplyBackground() {
        ensureLoaded();
        return ChatsConfig.replyBackground.Bool();
    }

    public static void setReplyBackground(boolean value) {
        ensureLoaded();
        ChatsConfig.replyBackground.setConfigBool(value);
    }

    public static int getStickerShape() {
        ensureLoaded();
        return ChatsConfig.stickerShape.Int();
    }

    public static void setStickerShape(int value) {
        ensureLoaded();
        ChatsConfig.stickerShape.setConfigInt(value);
    }

    public static boolean getUnlimitedRecentStickers() {
        return getPreferences().getBoolean("unlimitedRecentStickers", false);
    }

    public static void setUnlimitedRecentStickers(boolean value) {
        getPreferences().edit().putBoolean("unlimitedRecentStickers", value).apply();
    }

    public static boolean getHideReactionsInPrivateChats() {
        ensureLoaded();
        return ChatsConfig.hideReactionsInPrivate.Bool();
    }

    public static void setHideReactionsInPrivateChats(boolean value) {
        ensureLoaded();
        ChatsConfig.hideReactionsInPrivate.setConfigBool(value);
    }

    public static boolean getHideReactionsInChannels() {
        ensureLoaded();
        return ChatsConfig.hideReactionsInChannels.Bool();
    }

    public static void setHideReactionsInChannels(boolean value) {
        ensureLoaded();
        ChatsConfig.hideReactionsInChannels.setConfigBool(value);
    }

    public static boolean getHideReactionsInGroups() {
        ensureLoaded();
        return ChatsConfig.hideReactionsInGroups.Bool();
    }

    public static void setHideReactionsInGroups(boolean value) {
        ensureLoaded();
        ChatsConfig.hideReactionsInGroups.setConfigBool(value);
    }

    public static int getDoubleTapAction() {
        ensureLoaded();
        return NaConfig.INSTANCE.getDoubleTapAction().Int();
    }

    public static void setDoubleTapAction(int value) {
        ensureLoaded();
        NaConfig.INSTANCE.getDoubleTapAction().setConfigInt(value);
    }

    public static int getDoubleTapActionOutOwner() {
        ensureLoaded();
        return NaConfig.INSTANCE.getDoubleTapActionOut().Int();
    }

    public static void setDoubleTapActionOutOwner(int value) {
        ensureLoaded();
        NaConfig.INSTANCE.getDoubleTapActionOut().setConfigInt(value);
    }

    public static int getBottomButton() {
        ensureLoaded();
        return ChatsConfig.bottomButton.Int();
    }

    public static void setBottomButton(int value) {
        ensureLoaded();
        ChatsConfig.bottomButton.setConfigInt(value);
    }

    public static boolean getWidePostsInFeed() {
        ensureLoaded();
        return ChatsConfig.wideFeedPosts.Bool();
    }

    public static void setWidePostsInFeed(boolean value) {
        ensureLoaded();
        ChatsConfig.wideFeedPosts.setConfigBool(value);
    }

    public static boolean getWidePostsInChannels() {
        ensureLoaded();
        return ChatsConfig.wideChannelPosts.Bool();
    }

    public static void setWidePostsInChannels(boolean value) {
        ensureLoaded();
        ChatsConfig.wideChannelPosts.setConfigBool(value);
    }

    public static boolean getTelegramAiEditor() {
        ensureLoaded();
        return !AppearanceConfig.hideAiEditor.Bool();
    }

    public static void setTelegramAiEditor(boolean value) {
        ensureLoaded();
        AppearanceConfig.hideAiEditor.setConfigBool(!value);
    }

    public static boolean getTelegramAiSummaries() {
        ensureLoaded();
        return !AppearanceConfig.hideMessageSummary.Bool();
    }

    public static void setTelegramAiSummaries(boolean value) {
        ensureLoaded();
        AppearanceConfig.hideMessageSummary.setConfigBool(!value);
    }

    public static boolean getQuickAdminShortcuts() {
        ensureLoaded();
        return NaConfig.INSTANCE.getShortcutsAdministrators().Bool();
    }

    public static void setQuickAdminShortcuts(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getShortcutsAdministrators().setConfigBool(value);
    }

    public static boolean getQuickTransitionForChannels() {
        return getPreferences().getBoolean("quickTransitionForChannels", true);
    }

    public static void setQuickTransitionForChannels(boolean value) {
        getPreferences().edit().putBoolean("quickTransitionForChannels", value).apply();
    }

    public static boolean getQuickTransitionForTopics() {
        return getPreferences().getBoolean("quickTransitionForTopics", true);
    }

    public static void setQuickTransitionForTopics(boolean value) {
        getPreferences().edit().putBoolean("quickTransitionForTopics", value).apply();
    }

    public static boolean getDisableGreetingSticker() {
        ensureLoaded();
        return NekoConfig.dontSendGreetingSticker.Bool();
    }

    public static void setDisableGreetingSticker(boolean value) {
        ensureLoaded();
        NekoConfig.dontSendGreetingSticker.setConfigBool(value);
    }

    public static boolean getHideKeyboardOnScroll() {
        ensureLoaded();
        return NekoConfig.hideKeyboardOnChatScroll.Bool();
    }

    public static void setHideKeyboardOnScroll(boolean value) {
        ensureLoaded();
        NekoConfig.hideKeyboardOnChatScroll.setConfigBool(value);
    }

    public static boolean getAddCommaAfterMention() {
        ensureLoaded();
        return OpenExteraConfig.addCommaAfterMention.Bool();
    }

    public static void setAddCommaAfterMention(boolean value) {
        ensureLoaded();
        OpenExteraConfig.addCommaAfterMention.setConfigBool(value);
    }

    public static boolean getDisableMarkdown() {
        ensureLoaded();
        return NaConfig.INSTANCE.getDisableMarkdown().Bool();
    }

    public static void setDisableMarkdown(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getDisableMarkdown().setConfigBool(value);
    }

    public static boolean getHideSendAsPeer() {
        ensureLoaded();
        return NekoConfig.hideSendAsChannel.Bool();
    }

    public static void setHideSendAsPeer(boolean value) {
        ensureLoaded();
        NekoConfig.hideSendAsChannel.setConfigBool(value);
    }

    public static boolean getRemoveMessageTail() {
        ensureLoaded();
        return ChatsConfig.removeMessageTail.Bool();
    }

    public static void setRemoveMessageTail(boolean value) {
        ensureLoaded();
        ChatsConfig.removeMessageTail.setConfigBool(value);
    }

    public static boolean getReplaceEditedWithIcon() {
        ensureLoaded();
        return NaConfig.INSTANCE.getUseEditedIcon().Bool();
    }

    public static void setReplaceEditedWithIcon(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getUseEditedIcon().setConfigBool(value);
    }

    public static boolean getShowOnlineStatus() {
        ensureLoaded();
        return NaConfig.INSTANCE.getShowOnlineStatus().Bool();
    }

    public static void setShowOnlineStatus(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getShowOnlineStatus().setConfigBool(value);
    }

    public static boolean getHideShareButton() {
        ensureLoaded();
        return NaConfig.INSTANCE.getHideShareButtonInChannel().Bool();
    }

    public static void setHideShareButton(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getHideShareButtonInChannel().setConfigBool(value);
    }

    public static boolean getShowResultsBeforeVoting() {
        ensureLoaded();
        return ChatsConfig.showResultsBeforeVoting.Bool();
    }

    public static void setShowResultsBeforeVoting(boolean value) {
        ensureLoaded();
        ChatsConfig.showResultsBeforeVoting.setConfigBool(value);
    }

    public static boolean getShowCopyPhotoButton() {
        ensureLoaded();
        return NaConfig.INSTANCE.getShowCopyPhoto().Bool();
    }

    public static void setShowCopyPhotoButton(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getShowCopyPhoto().setConfigBool(value);
    }

    public static boolean getShowSaveMessageButton() {
        ensureLoaded();
        return NekoConfig.showAddToSavedMessages.Bool();
    }

    public static void setShowSaveMessageButton(boolean value) {
        ensureLoaded();
        NekoConfig.showAddToSavedMessages.setConfigBool(value);
    }

    public static boolean getShowRepeatMessageButton() {
        ensureLoaded();
        return NekoConfig.showRepeat.Bool();
    }

    public static void setShowRepeatMessageButton(boolean value) {
        ensureLoaded();
        NekoConfig.showRepeat.setConfigBool(value);
    }

    public static boolean getShowClearButton() {
        return getPreferences().getBoolean("showClearButton", true);
    }

    public static void setShowClearButton(boolean value) {
        getPreferences().edit().putBoolean("showClearButton", value).apply();
    }

    public static boolean getShowHistoryButton() {
        ensureLoaded();
        return NekoConfig.showViewHistory.Bool();
    }

    public static void setShowHistoryButton(boolean value) {
        ensureLoaded();
        NekoConfig.showViewHistory.setConfigBool(value);
    }

    public static boolean getShowReportButton() {
        ensureLoaded();
        return NekoConfig.showReport.Bool();
    }

    public static void setShowReportButton(boolean value) {
        ensureLoaded();
        NekoConfig.showReport.setConfigBool(value);
    }

    public static boolean getShowGenerateButton() {
        return getPreferences().getBoolean("showGenerateButton", true);
    }

    public static void setShowGenerateButton(boolean value) {
        getPreferences().edit().putBoolean("showGenerateButton", value).apply();
    }

    public static boolean getShowDetailsButton() {
        ensureLoaded();
        return NekoConfig.showMessageDetails.Bool();
    }

    public static void setShowDetailsButton(boolean value) {
        ensureLoaded();
        NekoConfig.showMessageDetails.setConfigBool(value);
    }

    public static boolean getGroupMessageMenu() {
        ensureLoaded();
        return NaConfig.INSTANCE.getGroupedMessageMenu().Bool();
    }

    public static void setGroupMessageMenu(boolean value) {
        ensureLoaded();
        NaConfig.INSTANCE.getGroupedMessageMenu().setConfigBool(value);
    }

    public static String getRecognitionLanguage() {
        return getPreferences().getString("recognitionLanguage", "none");
    }

    public static void setRecognitionLanguage(String value) {
        getPreferences().edit().putString("recognitionLanguage", value).apply();
    }

    public static boolean getPostprocessingWithAi() {
        return getPreferences().getBoolean("postprocessingWithAi", false);
    }

    public static void setPostprocessingWithAi(boolean value) {
        getPreferences().edit().putBoolean("postprocessingWithAi", value).apply();
    }

    public static CameraType getCameraType() {
        ensureLoaded();
        return enumAt(CameraType.values(), ChatsConfig.cameraType.Int(), CameraType.CAMERA_1);
    }

    public static void setCameraType(CameraType value) {
        ensureLoaded();
        ChatsConfig.cameraType.setConfigInt(value.ordinal());
    }

    public static boolean getExtendedFramesPerSecond() {
        ensureLoaded();
        return ChatsConfig.extendedFramesPerSecond.Bool();
    }

    public static void setExtendedFramesPerSecond(boolean value) {
        ensureLoaded();
        ChatsConfig.extendedFramesPerSecond.setConfigBool(value);
    }

    public static boolean getCameraStabilization() {
        ensureLoaded();
        return ChatsConfig.cameraStabilization.Bool();
    }

    public static void setCameraStabilization(boolean value) {
        ensureLoaded();
        ChatsConfig.cameraStabilization.setConfigBool(value);
    }

    public static boolean getCameraMirrorMode() {
        ensureLoaded();
        return ChatsConfig.cameraMirrorMode.Bool();
    }

    public static void setCameraMirrorMode(boolean value) {
        ensureLoaded();
        ChatsConfig.cameraMirrorMode.setConfigBool(value);
    }

    public static VideoMessagesCamera getVideoMessagesCamera() {
        ensureLoaded();
        return NekoConfig.rearVideoMessages.Bool() ? VideoMessagesCamera.REAR : VideoMessagesCamera.FRONT;
    }

    public static void setVideoMessagesCamera(VideoMessagesCamera value) {
        ensureLoaded();
        NekoConfig.rearVideoMessages.setConfigBool(value == VideoMessagesCamera.REAR);
    }

    public static boolean getRememberLastUsedCamera() {
        ensureLoaded();
        return ChatsConfig.rememberLastUsedCamera.Bool();
    }

    public static void setRememberLastUsedCamera(boolean value) {
        ensureLoaded();
        ChatsConfig.rememberLastUsedCamera.setConfigBool(value);
    }

    public static boolean getStartWithWideAngleCamera() {
        ensureLoaded();
        return ChatsConfig.startWithWideAngleCamera.Bool();
    }

    public static void setStartWithWideAngleCamera(boolean value) {
        ensureLoaded();
        ChatsConfig.startWithWideAngleCamera.setConfigBool(value);
    }

    public static boolean getZoomSlider() {
        ensureLoaded();
        return ChatsConfig.zoomSlider.Bool();
    }

    public static void setZoomSlider(boolean value) {
        ensureLoaded();
        ChatsConfig.zoomSlider.setConfigBool(value);
    }

    public static boolean getStaticZoom() {
        ensureLoaded();
        return ChatsConfig.staticZoom.Bool();
    }

    public static void setStaticZoom(boolean value) {
        ensureLoaded();
        ChatsConfig.staticZoom.setConfigBool(value);
    }

    public static boolean getAlwaysSendInHD() {
        ensureLoaded();
        return ChatsConfig.alwaysSendInHD.Bool();
    }

    public static void setAlwaysSendInHD(boolean value) {
        ensureLoaded();
        ChatsConfig.alwaysSendInHD.setConfigBool(value);
    }

    public static boolean getHideCameraTile() {
        ensureLoaded();
        return ChatsConfig.hideCameraTile.Bool();
    }

    public static void setHideCameraTile(boolean value) {
        ensureLoaded();
        ChatsConfig.hideCameraTile.setConfigBool(value);
    }

    public static int getDoubleTapSeekDuration() {
        ensureLoaded();
        return ChatsConfig.doubleTapSeekDuration.Int();
    }

    public static void setDoubleTapSeekDuration(int value) {
        ensureLoaded();
        ChatsConfig.doubleTapSeekDuration.setConfigInt(value);
    }

    public static boolean getPreferOriginalQuality() {
        ensureLoaded();
        return ChatsConfig.preferOriginalQuality.Bool();
    }

    public static void setPreferOriginalQuality(boolean value) {
        ensureLoaded();
        ChatsConfig.preferOriginalQuality.setConfigBool(value);
    }

    public static boolean getSwipeToPip() {
        ensureLoaded();
        return ChatsConfig.swipeToPip.Bool();
    }

    public static void setSwipeToPip(boolean value) {
        ensureLoaded();
        ChatsConfig.swipeToPip.setConfigBool(value);
    }

    public static boolean getUnmuteWithVolumeButtons() {
        ensureLoaded();
        return ChatsConfig.unmuteWithVolumeButtons.Bool();
    }

    public static void setUnmuteWithVolumeButtons(boolean value) {
        ensureLoaded();
        ChatsConfig.unmuteWithVolumeButtons.setConfigBool(value);
    }

    public static boolean getPauseOnMinimizeVideo() {
        ensureLoaded();
        return NekoConfig.autoPauseVideo.Bool();
    }

    public static void setPauseOnMinimizeVideo(boolean value) {
        ensureLoaded();
        NekoConfig.autoPauseVideo.setConfigBool(value);
    }

    public static boolean getPauseOnMinimizeVoice() {
        ensureLoaded();
        return ChatsConfig.pauseOnMinimizeVoice.Bool();
    }

    public static void setPauseOnMinimizeVoice(boolean value) {
        ensureLoaded();
        ChatsConfig.pauseOnMinimizeVoice.setConfigBool(value);
    }

    public static boolean getPauseOnMinimizeRound() {
        ensureLoaded();
        return ChatsConfig.pauseOnMinimizeRound.Bool();
    }

    public static void setPauseOnMinimizeRound(boolean value) {
        ensureLoaded();
        ChatsConfig.pauseOnMinimizeRound.setConfigBool(value);
    }

    public static boolean getUseGoogleCrashlytics() {
        ensureLoaded();
        return GeneralConfig.crashReports.Bool();
    }

    public static void setUseGoogleCrashlytics(boolean value) {
        ensureLoaded();
        GeneralConfig.crashReports.setConfigBool(value);
    }

    public static boolean getUseGoogleAnalytics() {
        return getPreferences().getBoolean("useGoogleAnalytics", false);
    }

    public static void setUseGoogleAnalytics(boolean value) {
        getPreferences().edit().putBoolean("useGoogleAnalytics", value).apply();
    }

    public static boolean getEnableAdBlock() {
        return getPreferences().getBoolean("enableAdBlock", true);
    }

    public static void setEnableAdBlock(boolean value) {
        getPreferences().edit().putBoolean("enableAdBlock", value).apply();
    }

    public static long getUpdateScheduleTimestamp() {
        return getPreferences().getLong("updateScheduleTimestamp", 0L);
    }

    public static void setUpdateScheduleTimestamp(long value) {
        getPreferences().edit().putLong("updateScheduleTimestamp", value).apply();
    }

    public static long getSdkUpdateScheduleTimestamp() {
        return getPreferences().getLong("sdkUpdateScheduleTimestamp", 0L);
    }

    public static void setSdkUpdateScheduleTimestamp(long value) {
        getPreferences().edit().putLong("sdkUpdateScheduleTimestamp", value).apply();
    }

    public static String getTargetLang() {
        ensureLoaded();
        return NaConfig.INSTANCE.getPreferredTranslateTargetLang().String();
    }

    public static void setTargetLang(String value) {
        ensureLoaded();
        NaConfig.INSTANCE.getPreferredTranslateTargetLang().setConfigString(value);
    }

    public static float getFlashWarmth() {
        ensureLoaded();
        return ChatsConfig.flashWarmth.Int() / 100.0f;
    }

    public static void setFlashWarmth(float value) {
        ensureLoaded();
        ChatsConfig.flashWarmth.setConfigInt(Math.round(value * 100));
    }

    public static float getFlashIntensity() {
        ensureLoaded();
        return ChatsConfig.flashIntensity.Int() / 100.0f;
    }

    public static void setFlashIntensity(float value) {
        ensureLoaded();
        ChatsConfig.flashIntensity.setConfigInt(Math.round(value * 100));
    }

    public static boolean getPluginsDevMode() {
        ensureLoaded();
        return plugins().isDeveloperMode();
    }

    public static void setPluginsDevMode(boolean value) {
        ensureLoaded();
        plugins().setDeveloperMode(value);
    }

    public static boolean getPluginsSafeMode() {
        ensureLoaded();
        return plugins().isSafeMode();
    }

    public static void setPluginsSafeMode(boolean value) {
        ensureLoaded();
        plugins().setSafeMode(value);
    }

    public static boolean getPluginsCompactView() {
        ensureLoaded();
        return plugins().isCompactView();
    }

    public static void setPluginsCompactView(boolean value) {
        ensureLoaded();
        plugins().setCompactView(value);
    }

    public static boolean getPluginsPySdkAutoUpdate() {
        return getPreferences().getBoolean("pluginsPySdkAutoUpdate", true);
    }

    public static void setPluginsPySdkAutoUpdate(boolean value) {
        getPreferences().edit().putBoolean("pluginsPySdkAutoUpdate", value).apply();
    }

    public static boolean getPluginsPySdkBetaVersions() {
        return getPreferences().getBoolean("pluginsPySdkBetaVersions", false);
    }

    public static void setPluginsPySdkBetaVersions(boolean value) {
        getPreferences().edit().putBoolean("pluginsPySdkBetaVersions", value).apply();
    }

    public static boolean getPluginsDisableArtOpts() {
        ensureLoaded();
        return plugins().isCompatibilityMode();
    }

    public static void setPluginsDisableArtOpts(boolean value) {
        ensureLoaded();
        plugins().setCompatibilityMode(value);
    }

    public static java.util.Set<String> getPinnedPlugins() {
        ensureLoaded();
        return readPinnedPlugins();
    }

    public static void setPinnedPlugins(java.util.Set<String> value) {
        ensureLoaded();
        writePinnedPlugins(value);
    }

    public static boolean getUseSystemIconShape() {
        ensureLoaded();
        return IconPacksConfig.useSystemIconShape.Bool();
    }

    public static void setUseSystemIconShape(boolean value) {
        ensureLoaded();
        IconPacksConfig.useSystemIconShape.setConfigBool(value);
    }

    private static boolean applyBoolean(String key, boolean value) {
        switch (key) {
            case "disableNumberRounding":
                setDisableNumberRounding(value);
                return true;
            case "formatTimeWithSeconds":
                setFormatTimeWithSeconds(value);
                return true;
            case "relativeLastSeen":
                setRelativeLastSeen(value);
                return true;
            case "inAppVibration":
                setInAppVibration(value);
                return true;
            case "filterZalgo":
                setFilterZalgo(value);
                return true;
            case "uploadSpeedBoost":
                setUploadSpeedBoost(value);
                return true;
            case "hidePhoneNumber":
                setHidePhoneNumber(value);
                return true;
            case "hideArchiveFolder":
                setHideArchiveFolder(value);
                return true;
            case "archiveOnPull":
                setArchiveOnPull(value);
                return true;
            case "disableUnarchiveSwipe":
                setDisableUnarchiveSwipe(value);
                return true;
            case "singleCornerRadius":
                setSingleCornerRadius(value);
                return true;
            case "forceSnow":
                setForceSnow(value);
                return true;
            case "hideActionBarStatus":
                setHideActionBarStatus(value);
                return true;
            case "centerTitle":
                setCenterTitle(value);
                return true;
            case "hideStories":
                setHideStories(value);
                return true;
            case "hideFloatingButton":
                setHideFloatingButton(value);
                return true;
            case "hideDialogsSearchBar":
                setHideDialogsSearchBar(value);
                return true;
            case "senderMiniAvatars":
                setSenderMiniAvatars(value);
                return true;
            case "hideAllChats":
                setHideAllChats(value);
                return true;
            case "squareFab":
                setSquareFab(value);
                return true;
            case "sectionsSeparatedHeadersPreference":
                setSectionsSeparatedHeadersPreference(value);
                return true;
            case "newLoadingStyle":
                setNewLoadingStyle(value);
                return true;
            case "newChatHeaderStyle":
                setNewChatHeaderStyle(value);
                return true;
            case "newNavigationBarStyle":
                setNewNavigationBarStyle(value);
                return true;
            case "useSystemFonts":
                setUseSystemFonts(value);
                return true;
            case "gooeyAvatarAnimation":
                setGooeyAvatarAnimation(value);
                return true;
            case "customThemes":
                setCustomThemes(value);
                return true;
            case "glassMessageMenu":
                setGlassMessageMenu(value);
                return true;
            case "navigationDrawer":
                setNavigationDrawer(value);
                return true;
            case "immersiveDrawerAnimation":
                setImmersiveDrawerAnimation(value);
                return true;
            case "showFeedTab":
                setShowFeedTab(value);
                return true;
            case "showFeedUnreadCounter":
                setShowFeedUnreadCounter(value);
                return true;
            case "hideStickerTime":
                setHideStickerTime(value);
                return true;
            case "replyColors":
                setReplyColors(value);
                return true;
            case "replyEmoji":
                setReplyEmoji(value);
                return true;
            case "replyBackground":
                setReplyBackground(value);
                return true;
            case "hideReactionsInPrivateChats":
                setHideReactionsInPrivateChats(value);
                return true;
            case "hideReactionsInChannels":
                setHideReactionsInChannels(value);
                return true;
            case "hideReactionsInGroups":
                setHideReactionsInGroups(value);
                return true;
            case "widePostsInFeed":
                setWidePostsInFeed(value);
                return true;
            case "widePostsInChannels":
                setWidePostsInChannels(value);
                return true;
            case "telegramAiEditor":
                setTelegramAiEditor(value);
                return true;
            case "telegramAiSummaries":
                setTelegramAiSummaries(value);
                return true;
            case "quickAdminShortcuts":
                setQuickAdminShortcuts(value);
                return true;
            case "disableGreetingSticker":
                setDisableGreetingSticker(value);
                return true;
            case "hideKeyboardOnScroll":
                setHideKeyboardOnScroll(value);
                return true;
            case "addCommaAfterMention":
                setAddCommaAfterMention(value);
                return true;
            case "disableMarkdown":
                setDisableMarkdown(value);
                return true;
            case "hideSendAsPeer":
                setHideSendAsPeer(value);
                return true;
            case "removeMessageTail":
                setRemoveMessageTail(value);
                return true;
            case "replaceEditedWithIcon":
                setReplaceEditedWithIcon(value);
                return true;
            case "showOnlineStatus":
                setShowOnlineStatus(value);
                return true;
            case "hideShareButton":
                setHideShareButton(value);
                return true;
            case "showResultsBeforeVoting":
                setShowResultsBeforeVoting(value);
                return true;
            case "showCopyPhotoButton":
                setShowCopyPhotoButton(value);
                return true;
            case "showSaveMessageButton":
                setShowSaveMessageButton(value);
                return true;
            case "showRepeatMessageButton":
                setShowRepeatMessageButton(value);
                return true;
            case "showHistoryButton":
                setShowHistoryButton(value);
                return true;
            case "showReportButton":
                setShowReportButton(value);
                return true;
            case "showDetailsButton":
                setShowDetailsButton(value);
                return true;
            case "groupMessageMenu":
                setGroupMessageMenu(value);
                return true;
            case "extendedFramesPerSecond":
                setExtendedFramesPerSecond(value);
                return true;
            case "cameraStabilization":
                setCameraStabilization(value);
                return true;
            case "cameraMirrorMode":
                setCameraMirrorMode(value);
                return true;
            case "rememberLastUsedCamera":
                setRememberLastUsedCamera(value);
                return true;
            case "startWithWideAngleCamera":
                setStartWithWideAngleCamera(value);
                return true;
            case "zoomSlider":
                setZoomSlider(value);
                return true;
            case "staticZoom":
                setStaticZoom(value);
                return true;
            case "alwaysSendInHD":
                setAlwaysSendInHD(value);
                return true;
            case "hideCameraTile":
                setHideCameraTile(value);
                return true;
            case "preferOriginalQuality":
                setPreferOriginalQuality(value);
                return true;
            case "swipeToPip":
                setSwipeToPip(value);
                return true;
            case "unmuteWithVolumeButtons":
                setUnmuteWithVolumeButtons(value);
                return true;
            case "pauseOnMinimizeVideo":
                setPauseOnMinimizeVideo(value);
                return true;
            case "pauseOnMinimizeVoice":
                setPauseOnMinimizeVoice(value);
                return true;
            case "pauseOnMinimizeRound":
                setPauseOnMinimizeRound(value);
                return true;
            case "useGoogleCrashlytics":
                setUseGoogleCrashlytics(value);
                return true;
            case "pluginsDevMode":
                setPluginsDevMode(value);
                return true;
            case "pluginsSafeMode":
                setPluginsSafeMode(value);
                return true;
            case "pluginsCompactView":
                setPluginsCompactView(value);
                return true;
            case "pluginsDisableArtOpts":
                setPluginsDisableArtOpts(value);
                return true;
            case "useSystemIconShape":
                setUseSystemIconShape(value);
                return true;
            default:
                return false;
        }
    }

    private static boolean applyInt(String key, int value) {
        switch (key) {
            case "translationProvider":
                setTranslationProvider(value);
                return true;
            case "downloadSpeedBoost":
                setDownloadSpeedBoost(value);
                return true;
            case "showIdAndDc":
                setShowIdAndDc(value);
                return true;
            case "doNotUseProxy":
                setDoNotUseProxy(value);
                return true;
            case "titleText":
                setTitleText(value);
                return true;
            case "tabletMode":
                setTabletMode(value);
                return true;
            case "stickerShape":
                setStickerShape(value);
                return true;
            case "doubleTapAction":
                setDoubleTapAction(value);
                return true;
            case "doubleTapActionOutOwner":
                setDoubleTapActionOutOwner(value);
                return true;
            case "bottomButton":
                setBottomButton(value);
                return true;
            case "doubleTapSeekDuration":
                setDoubleTapSeekDuration(value);
                return true;
            default:
                return false;
        }
    }

    private static boolean applyFloat(String key, float value) {
        switch (key) {
            case "avatarCorners":
                setAvatarCorners(value);
                return true;
            case "sectionRadius":
                setSectionRadius(value);
                return true;
            case "predictiveBackIntensity":
                setPredictiveBackIntensity(value);
                return true;
            case "stickerSize":
                setStickerSize(value);
                return true;
            case "flashWarmth":
                setFlashWarmth(value);
                return true;
            case "flashIntensity":
                setFlashIntensity(value);
                return true;
            default:
                return false;
        }
    }

    private static boolean applyLong(String key, long value) {
        return false;
    }

    private static boolean applyString(String key, String value) {
        switch (key) {
            case "customSavePath":
                setCustomSavePath(value);
                return true;
            case "editingIconPackId":
                setEditingIconPackId(value);
                return true;
            case "targetLang":
                setTargetLang(value);
                return true;
            default:
                return false;
        }
    }

    private static boolean applyEnumOrdinal(String key, int value) {
        switch (key) {
            case "iconPack":
                setIconPack(enumAt(IconPackType.values(), value, IconPackType.DEFAULT));
                return true;
            case "dividerStyle":
                setDividerStyle(enumAt(DividerStyle.values(), value, DividerStyle.LINE));
                return true;
            case "glassOutlineStyle":
                setGlassOutlineStyle(enumAt(GlassOutlineStyle.values(), value, GlassOutlineStyle.GLARE));
                return true;
            case "cameraType":
                setCameraType(enumAt(CameraType.values(), value, CameraType.CAMERA_1));
                return true;
            case "videoMessagesCamera":
                setVideoMessagesCamera(enumAt(VideoMessagesCamera.values(), value, VideoMessagesCamera.FRONT));
                return true;
            default:
                return false;
        }
    }


    public static boolean pluginsSafeMode() {
        return plugins().isSafeMode();
    }

    public static int iconPack() {
        return BaseIconPacks.getSelected();
    }

    public static boolean inAppVibration() {
        return getInAppVibration();
    }

    public static boolean forceSnow() {
        return getForceSnow();
    }

    private static void applyForceSnow(boolean enabled) {
        NekoConfig.actionBarDecoration.setConfigInt(enabled ? 1 : 0);
        NaConfig.INSTANCE.getChatDecoration().setConfigInt(enabled ? 1 : 0);
    }

    public static boolean getPluginsEngine() {
        return plugins().isEngineEnabled();
    }

    public static void setPluginsEngine(boolean enabled) {
        plugins().setEngineEnabled(enabled);
    }

    private static Set<String> readPinnedPlugins() {
        LinkedHashSet<String> pinned = new LinkedHashSet<>();
        try {
            for (Map.Entry<String, Plugin> entry : plugins().getPlugins().entrySet()) {
                if (plugins().isPluginPinned(entry.getKey())) {
                    pinned.add(entry.getKey());
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return pinned;
    }

    private static void writePinnedPlugins(Set<String> value) {
        Set<String> wanted = value == null ? Collections.<String>emptySet() : value;
        try {
            for (String id : plugins().getPlugins().keySet()) {
                plugins().setPluginPinned(id, wanted.contains(id));
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /**
     * Радиус скругления аватарки для стороны {@code size} в dp — как у exteraGram,
     * где эта перегрузка сама переводит результат в пиксели. Наш AppearanceConfig
     * считает от пикселей, поэтому перевод делается здесь.
     */
    public static int getAvatarCorners(float size) {
        return getAvatarCorners(size, false, AvatarCornerType.DEFAULT, false);
    }

    public static int getAvatarCorners(float size, boolean inPixels) {
        return getAvatarCorners(size, inPixels, AvatarCornerType.DEFAULT, false);
    }

    public static int getAvatarCorners(float size, boolean inPixels, boolean forum) {
        return getAvatarCorners(size, inPixels,
                forum ? AvatarCornerType.FORUM : AvatarCornerType.DEFAULT, false);
    }

    public static int getAvatarCorners(float size, boolean inPixels, boolean forum, boolean hasStories) {
        return getAvatarCorners(size, inPixels,
                forum ? AvatarCornerType.FORUM : AvatarCornerType.DEFAULT, hasStories);
    }

    public static int getAvatarCorners(float size, boolean inPixels, AvatarCornerType type) {
        return getAvatarCorners(size, inPixels, type, false);
    }

    public static int getAvatarCorners(float size, boolean inPixels, AvatarCornerType type, boolean hasStories) {
        float value = inPixels ? size : AndroidUtilities.dp(size);
        int cornerType;
        if (type == AvatarCornerType.FORUM) {
            cornerType = AppearanceConfig.CORNER_TYPE_FORUM;
        } else if (type == AvatarCornerType.COMMUNITY) {
            cornerType = AppearanceConfig.CORNER_TYPE_COMMUNITY;
        } else {
            cornerType = AppearanceConfig.CORNER_TYPE_DEFAULT;
        }
        return AppearanceConfig.INSTANCE.getAvatarCorners(value, cornerType, hasStories);
    }

    public static float getAvatarSquareness() {
        float squareness = 1.0f - getAvatarCorners() / 28.0f;
        return Math.max(0.0f, Math.min(1.0f, squareness));
    }

    public static float getPillRadius(float radiusDp) {
        float scaled = radiusDp * (1.0f - getAvatarSquareness());
        return Math.max(Math.min(radiusDp, 6.0f), scaled);
    }

    public static int getOnlineDotInnerRadius() {
        return AndroidUtilities.dp(getAvatarSquareness() + 5.0f);
    }

    public static int getOnlineDotOuterRadius() {
        return AndroidUtilities.dp(getAvatarSquareness() * 2.0f + 7.0f);
    }

    public static float getOnlineDotOffset(float base, float size) {
        return base + (((float) (size / Math.sqrt(2.0))) - base) * getAvatarSquareness();
    }

    public static int getSectionRadiusDp() {
        return Math.round(getSectionRadius());
    }

    public static boolean getSectionsSeparatedHeaders() {
        return getDividerStyle() == DividerStyle.SEGMENTS || getSectionsSeparatedHeadersPreference();
    }

    public static void setSectionsSeparatedHeaders(boolean value) {
        setSectionsSeparatedHeadersPreference(getDividerStyle() == DividerStyle.SEGMENTS || value);
    }

    public static boolean canUseYandexMaps() {
        return getUseYandexMaps();
    }

    public static SearchEngine getYandexSearchEngine() {
        return yandexSearchEngine;
    }

    public static String getCurrentLangName() {
        String lang = getTargetLang();
        return lang == null || lang.isEmpty() ? "app" : lang;
    }

    public static Pair<Long, String> getApiBotInfo() {
        return null;
    }

    public static boolean isProxyDisabledOn(ProxyDisableCondition condition) {
        return condition != null && (flagOf(condition) & getDoNotUseProxy()) != 0;
    }

    public static void setProxyDisabledOn(ProxyDisableCondition condition, boolean disabled) {
        if (condition == null) {
            return;
        }
        int flag = flagOf(condition);
        setDoNotUseProxy(disabled ? getDoNotUseProxy() | flag : getDoNotUseProxy() & ~flag);
    }

    private static int flagOf(ProxyDisableCondition condition) {
        return condition.getFlag();
    }

    public static boolean getLogging() {
        return BuildVars.LOGS_ENABLED;
    }

    public static void setLogging(boolean enabled) {
        BuildVars.LOGS_ENABLED = enabled;
        systemConfigPrefs().edit().putBoolean("logsEnabled", enabled).apply();
        if (!enabled) {
            FileLog.cleanupLogs();
        }
    }

    public static void toggleLogging() {
        setLogging(!BuildVars.LOGS_ENABLED);
    }

    private static SharedPreferences systemConfigPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", 0);
    }

    public static ArrayList<String> getDoNotMarkAsNew() {
        return doNotMarkAsNew;
    }

    public static HashMap<String, Long> getNewFeaturesShowedAt() {
        return newFeaturesShowedAt;
    }

    public static ArrayList<Integer> getDefaultMainMenuLayout() {
        return new ArrayList<>(MainMenuLayout.getDefaultLayout());
    }

    public static ArrayList<Integer> getMainMenuLayout() {
        loadMenu();
        return mainMenuLayout;
    }

    public static ArrayList<Integer> getMainMenuHiddenItems() {
        loadMenu();
        return mainMenuHiddenItems;
    }

    private static void loadMenu() {
        if (menuLoaded) {
            return;
        }
        synchronized (ExteraConfig.class) {
            if (menuLoaded) {
                return;
            }
            mainMenuLayout.clear();
            mainMenuLayout.addAll(MainMenuLayout.getLayout());
            mainMenuHiddenItems.clear();
            mainMenuHiddenItems.addAll(MainMenuLayout.getHiddenItems());
            iconPacksLayout.clear();
            iconPacksLayout.addAll(IconPacksConfig.getActivePackIds());
            menuLoaded = true;
        }
    }

    public static void saveMainMenuLayout() {
        loadMenu();
        MainMenuLayout.save(new ArrayList<>(mainMenuLayout), new ArrayList<>(mainMenuHiddenItems));
    }

    public static void sanitizeMenu() {
        loadMenu();
        List<Integer> known = MainMenuLayout.getAllItemIds();
        boolean changed = mainMenuLayout.retainAll(known);
        changed |= mainMenuHiddenItems.retainAll(known);
        for (Integer id : known) {
            if (id == MainMenuItem.DIVIDER.getId()) {
                continue;
            }
            if (!mainMenuLayout.contains(id) && !mainMenuHiddenItems.contains(id)) {
                mainMenuHiddenItems.add(id);
                changed = true;
            }
        }
        if (changed) {
            saveMainMenuLayout();
        }
    }

    public static void ensureSettingsVisibility() {
        loadMenu();
        Integer id = MainMenuItem.SETTINGS.getId();
        if (mainMenuLayout.contains(id)) {
            return;
        }
        mainMenuHiddenItems.remove(id);
        mainMenuLayout.add(id);
        saveMainMenuLayout();
    }

    public static ArrayList<String> getIconPacksLayout() {
        loadMenu();
        return iconPacksLayout;
    }

    public static ArrayList<String> getIconPacksHidden() {
        loadMenu();
        return iconPacksHidden;
    }

    public static void saveIconPacksLayout() {
        loadMenu();
        IconPacksConfig.setActivePackIds(new ArrayList<>(iconPacksLayout));
    }

    public static PreferencesUtils.BackupItem[] getBackupKeys() {
        return BACKUP_KEYS.clone();
    }

    public static int getDoubleTapSeekDurationMillis() {
        int duration = getDoubleTapSeekDuration();
        return duration >= 0 && duration <= 2 ? (duration + 1) * 5000 : 30000;
    }

    private static void migrateProxyConditions() {
        SharedPreferences prefs = getPreferences();
        if (!prefs.contains("doNotUseProxyWithVpn")) {
            return;
        }
        if (prefs.getBoolean("doNotUseProxyWithVpn", false)) {
            setProxyDisabledOn(ProxyDisableCondition.VPN, true);
        }
        prefs.edit().remove("doNotUseProxyWithVpn").apply();
    }

    public static void loadConfig() {
        ensureLoaded();
        loadMenu();
        migrateProxyConditions();
    }

    public static void reloadConfig() {
        synchronized (ExteraConfig.class) {
            menuLoaded = false;
        }
        loadConfig();
    }

    public static void init() {
        loadConfig();
    }

    private static final PreferencesUtils.BackupItem[] BACKUP_KEYS = {
            new PreferencesUtils.BackupItem("translationProvider", Integer.class),
            new PreferencesUtils.BackupItem("translationFormality", Integer.class),
            new PreferencesUtils.BackupItem("disableNumberRounding", Boolean.class),
            new PreferencesUtils.BackupItem("formatTimeWithSeconds", Boolean.class),
            new PreferencesUtils.BackupItem("relativeLastSeen", Boolean.class),
            new PreferencesUtils.BackupItem("inAppVibration", Boolean.class),
            new PreferencesUtils.BackupItem("filterZalgo", Boolean.class),
            new PreferencesUtils.BackupItem("useYandexMaps", Boolean.class),
            new PreferencesUtils.BackupItem("downloadSpeedBoost", Integer.class),
            new PreferencesUtils.BackupItem("uploadSpeedBoost", Boolean.class),
            new PreferencesUtils.BackupItem("hidePhoneNumber", Boolean.class),
            new PreferencesUtils.BackupItem("showIdAndDc", Integer.class),
            new PreferencesUtils.BackupItem("hideArchiveFolder", Boolean.class),
            new PreferencesUtils.BackupItem("archiveOnPull", Boolean.class),
            new PreferencesUtils.BackupItem("disableUnarchiveSwipe", Boolean.class),
            new PreferencesUtils.BackupItem("doNotUseProxy", Integer.class),
            new PreferencesUtils.BackupItem("customSavePath", String.class),
            new PreferencesUtils.BackupItem("iconPack", Integer.class),
            new PreferencesUtils.BackupItem("editingIconPackId", String.class),
            new PreferencesUtils.BackupItem("avatarCorners", Float.class),
            new PreferencesUtils.BackupItem("singleCornerRadius", Boolean.class),
            new PreferencesUtils.BackupItem("dividerStyle", Integer.class),
            new PreferencesUtils.BackupItem("forceSnow", Boolean.class),
            new PreferencesUtils.BackupItem("hideActionBarStatus", Boolean.class),
            new PreferencesUtils.BackupItem("centerTitle", Boolean.class),
            new PreferencesUtils.BackupItem("hideStories", Boolean.class),
            new PreferencesUtils.BackupItem("hideFloatingButton", Boolean.class),
            new PreferencesUtils.BackupItem("hideDialogsSearchBar", Boolean.class),
            new PreferencesUtils.BackupItem("senderMiniAvatars", Boolean.class),
            new PreferencesUtils.BackupItem("titleText", Integer.class),
            new PreferencesUtils.BackupItem("tabIcons", Integer.class),
            new PreferencesUtils.BackupItem("tabCounter", Boolean.class),
            new PreferencesUtils.BackupItem("hideAllChats", Boolean.class),
            new PreferencesUtils.BackupItem("squareFab", Boolean.class),
            new PreferencesUtils.BackupItem("sectionRadius", Float.class),
            new PreferencesUtils.BackupItem("sectionsSeparatedHeadersPreference", Boolean.class),
            new PreferencesUtils.BackupItem("newLoadingStyle", Boolean.class),
            new PreferencesUtils.BackupItem("newSliderStyle", Boolean.class),
            new PreferencesUtils.BackupItem("newSwitchStyle", Boolean.class),
            new PreferencesUtils.BackupItem("newChatHeaderStyle", Boolean.class),
            new PreferencesUtils.BackupItem("newNavigationBarStyle", Boolean.class),
            new PreferencesUtils.BackupItem("tabletMode", Integer.class),
            new PreferencesUtils.BackupItem("useSystemFonts", Boolean.class),
            new PreferencesUtils.BackupItem("gooeyAvatarAnimation", Boolean.class),
            new PreferencesUtils.BackupItem("customThemes", Boolean.class),
            new PreferencesUtils.BackupItem("predictiveBackIntensity", Float.class),
            new PreferencesUtils.BackupItem("springAnimations", Boolean.class),
            new PreferencesUtils.BackupItem("glassOutlineStyle", Integer.class),
            new PreferencesUtils.BackupItem("glassMessageMenu", Boolean.class),
            new PreferencesUtils.BackupItem("forceBlur", Boolean.class),
            new PreferencesUtils.BackupItem("eventType", Integer.class),
            new PreferencesUtils.BackupItem("navigationDrawer", Boolean.class),
            new PreferencesUtils.BackupItem("immersiveDrawerAnimation", Boolean.class),
            new PreferencesUtils.BackupItem("showFeedTab", Boolean.class),
            new PreferencesUtils.BackupItem("showFeedUnreadCounter", Boolean.class),
            new PreferencesUtils.BackupItem("stickerSize", Float.class),
            new PreferencesUtils.BackupItem("hideStickerTime", Boolean.class),
            new PreferencesUtils.BackupItem("replyColors", Boolean.class),
            new PreferencesUtils.BackupItem("replyEmoji", Boolean.class),
            new PreferencesUtils.BackupItem("replyBackground", Boolean.class),
            new PreferencesUtils.BackupItem("stickerShape", Integer.class),
            new PreferencesUtils.BackupItem("unlimitedRecentStickers", Boolean.class),
            new PreferencesUtils.BackupItem("hideReactionsInPrivateChats", Boolean.class),
            new PreferencesUtils.BackupItem("hideReactionsInChannels", Boolean.class),
            new PreferencesUtils.BackupItem("hideReactionsInGroups", Boolean.class),
            new PreferencesUtils.BackupItem("doubleTapAction", Integer.class),
            new PreferencesUtils.BackupItem("doubleTapActionOutOwner", Integer.class),
            new PreferencesUtils.BackupItem("bottomButton", Integer.class),
            new PreferencesUtils.BackupItem("widePostsInFeed", Boolean.class),
            new PreferencesUtils.BackupItem("widePostsInChannels", Boolean.class),
            new PreferencesUtils.BackupItem("telegramAiEditor", Boolean.class),
            new PreferencesUtils.BackupItem("telegramAiSummaries", Boolean.class),
            new PreferencesUtils.BackupItem("quickAdminShortcuts", Boolean.class),
            new PreferencesUtils.BackupItem("quickTransitionForChannels", Boolean.class),
            new PreferencesUtils.BackupItem("quickTransitionForTopics", Boolean.class),
            new PreferencesUtils.BackupItem("disableGreetingSticker", Boolean.class),
            new PreferencesUtils.BackupItem("hideKeyboardOnScroll", Boolean.class),
            new PreferencesUtils.BackupItem("addCommaAfterMention", Boolean.class),
            new PreferencesUtils.BackupItem("disableMarkdown", Boolean.class),
            new PreferencesUtils.BackupItem("hideSendAsPeer", Boolean.class),
            new PreferencesUtils.BackupItem("removeMessageTail", Boolean.class),
            new PreferencesUtils.BackupItem("replaceEditedWithIcon", Boolean.class),
            new PreferencesUtils.BackupItem("showOnlineStatus", Boolean.class),
            new PreferencesUtils.BackupItem("hideShareButton", Boolean.class),
            new PreferencesUtils.BackupItem("showResultsBeforeVoting", Boolean.class),
            new PreferencesUtils.BackupItem("showCopyPhotoButton", Boolean.class),
            new PreferencesUtils.BackupItem("showSaveMessageButton", Boolean.class),
            new PreferencesUtils.BackupItem("showRepeatMessageButton", Boolean.class),
            new PreferencesUtils.BackupItem("showClearButton", Boolean.class),
            new PreferencesUtils.BackupItem("showHistoryButton", Boolean.class),
            new PreferencesUtils.BackupItem("showReportButton", Boolean.class),
            new PreferencesUtils.BackupItem("showGenerateButton", Boolean.class),
            new PreferencesUtils.BackupItem("showDetailsButton", Boolean.class),
            new PreferencesUtils.BackupItem("groupMessageMenu", Boolean.class),
            new PreferencesUtils.BackupItem("recognitionLanguage", String.class),
            new PreferencesUtils.BackupItem("postprocessingWithAi", Boolean.class),
            new PreferencesUtils.BackupItem("cameraType", Integer.class),
            new PreferencesUtils.BackupItem("extendedFramesPerSecond", Boolean.class),
            new PreferencesUtils.BackupItem("cameraStabilization", Boolean.class),
            new PreferencesUtils.BackupItem("cameraMirrorMode", Boolean.class),
            new PreferencesUtils.BackupItem("videoMessagesCamera", Integer.class),
            new PreferencesUtils.BackupItem("rememberLastUsedCamera", Boolean.class),
            new PreferencesUtils.BackupItem("startWithWideAngleCamera", Boolean.class),
            new PreferencesUtils.BackupItem("zoomSlider", Boolean.class),
            new PreferencesUtils.BackupItem("staticZoom", Boolean.class),
            new PreferencesUtils.BackupItem("alwaysSendInHD", Boolean.class),
            new PreferencesUtils.BackupItem("hideCameraTile", Boolean.class),
            new PreferencesUtils.BackupItem("doubleTapSeekDuration", Integer.class),
            new PreferencesUtils.BackupItem("preferOriginalQuality", Boolean.class),
            new PreferencesUtils.BackupItem("swipeToPip", Boolean.class),
            new PreferencesUtils.BackupItem("unmuteWithVolumeButtons", Boolean.class),
            new PreferencesUtils.BackupItem("pauseOnMinimizeVideo", Boolean.class),
            new PreferencesUtils.BackupItem("pauseOnMinimizeVoice", Boolean.class),
            new PreferencesUtils.BackupItem("pauseOnMinimizeRound", Boolean.class),
            new PreferencesUtils.BackupItem("useGoogleCrashlytics", Boolean.class),
            new PreferencesUtils.BackupItem("useGoogleAnalytics", Boolean.class),
            new PreferencesUtils.BackupItem("enableAdBlock", Boolean.class),
            new PreferencesUtils.BackupItem("updateScheduleTimestamp", Long.class),
            new PreferencesUtils.BackupItem("sdkUpdateScheduleTimestamp", Long.class),
            new PreferencesUtils.BackupItem("targetLang", String.class),
            new PreferencesUtils.BackupItem("flashWarmth", Float.class),
            new PreferencesUtils.BackupItem("flashIntensity", Float.class),
            new PreferencesUtils.BackupItem("pluginsDevMode", Boolean.class),
            new PreferencesUtils.BackupItem("pluginsSafeMode", Boolean.class),
            new PreferencesUtils.BackupItem("pluginsCompactView", Boolean.class),
            new PreferencesUtils.BackupItem("pluginsPySdkAutoUpdate", Boolean.class),
            new PreferencesUtils.BackupItem("pluginsPySdkBetaVersions", Boolean.class),
            new PreferencesUtils.BackupItem("pluginsDisableArtOpts", Boolean.class),
            new PreferencesUtils.BackupItem("pinnedPlugins", java.util.Set.class),
            new PreferencesUtils.BackupItem("useSystemIconShape", Boolean.class)
    };

    private static final class MappedEditor implements SharedPreferences.Editor {

        private final SharedPreferences.Editor delegate;

        private MappedEditor(SharedPreferences.Editor delegate) {
            this.delegate = delegate;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            if (!applyBoolean(key, value)) {
                delegate.putBoolean(key, value);
            }
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            if (!applyInt(key, value) && !applyEnumOrdinal(key, value)) {
                delegate.putInt(key, value);
            }
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            if (!applyFloat(key, value)) {
                delegate.putFloat(key, value);
            }
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            if (!applyLong(key, value)) {
                delegate.putLong(key, value);
            }
            return this;
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            if (!applyString(key, value)) {
                delegate.putString(key, value);
            }
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            if ("pinnedPlugins".equals(key)) {
                setPinnedPlugins(values);
            } else {
                delegate.putStringSet(key, values);
            }
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            delegate.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            delegate.clear();
            return this;
        }

        @Override
        public boolean commit() {
            return delegate.commit();
        }

        @Override
        public void apply() {
            delegate.apply();
        }
    }
}
