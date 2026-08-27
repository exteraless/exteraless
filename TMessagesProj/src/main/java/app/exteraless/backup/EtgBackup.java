package app.exteraless.backup;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import app.exteraless.OpenExteraConfig;
import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.chats.ChatsConfig;
import app.exteraless.drawer.MainMenuLayout;
import app.exteraless.general.GeneralConfig;
import app.exteraless.icons.IconPacksConfig;
import app.exteraless.pillstack.PillStackConfig;
import app.exteraless.plugins.PluginsConstants;
import app.exteraless.utils.UtilsConfig;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;
import xyz.nextalone.nagram.NaConfig;

/**
 * Импорт и экспорт настроек в формате exteraGram («.extera»).
 *
 * Формат задан com/exteragram/messenger/backup/PreferencesUtils.java 12.9.0: JSON, ключи верхнего
 * уровня — имена файлов SharedPreferences (exteraconfig, pillstackconfig, mainconfig), внутри —
 * имена свойств ExteraConfig. Файл обёрнут {@link InvisibleEncryptor}.
 *
 * Наши ключи называются иначе и часть настроек живёт в NagramX, поэтому связь задаётся таблицей
 * ниже: для каждого ключа exteraGram — чтение нашего значения и запись обратно, с проверкой
 * диапазонов теми же правилами, что у exteraGram (чужой файл считается недоверенным).
 */
public final class EtgBackup {

    public static final String EXTENSION = ".extera";

    private static final String SECTION_EXTERA = "exteraconfig";
    private static final String SECTION_PILLS = "pillstackconfig";
    private static final String SECTION_MAIN = "mainconfig";

    private static final int KIND_BOOL = 0;
    private static final int KIND_INT = 1;
    private static final int KIND_FLOAT = 2;
    private static final int KIND_STRING = 3;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int RECENT_STICKERS_DEFAULT = 20;
    private static final int RECENT_STICKERS_MAX = 200;

    private static List<Entry> entries;

    private EtgBackup() {
    }

    private interface Reader {
        JsonElement read();
    }

    private interface Applier {
        void apply(JsonElement value);
    }

    private static final class Entry {
        final String section;
        final String key;
        final int kind;
        final double min;
        final double max;
        final Predicate<String> stringCheck;
        final Reader reader;
        final Applier applier;

        Entry(String section, String key, int kind, double min, double max,
              Predicate<String> stringCheck, Reader reader, Applier applier) {
            this.section = section;
            this.key = key;
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.stringCheck = stringCheck;
            this.reader = reader;
            this.applier = applier;
        }
    }

    // ---- Публичное API ----

    /** Собирает бэкап в формате exteraGram. */
    public static String buildBackup(boolean encrypt) {
        ensureConfigsLoaded();
        JsonObject root = new JsonObject();
        for (Entry entry : entries()) {
            JsonElement value;
            try {
                value = entry.reader.read();
            } catch (Exception e) {
                FileLog.e(e);
                continue;
            }
            if (value == null || !isExpected(entry, value)) {
                continue;
            }
            JsonObject section = root.getAsJsonObject(entry.section);
            if (section == null) {
                section = new JsonObject();
                root.add(entry.section, section);
            }
            section.add(entry.key, value);
        }
        String json = GSON.toJson(root);
        return encrypt ? InvisibleEncryptor.encode(json) : json;
    }

    public static String generateBackupName() {
        return "exteraless-" + Utilities.generateRandomString(4) + EXTENSION;
    }

    /** Файл похож на бэкап exteraGram: расширение на месте и внутри есть хоть один знакомый ключ. */
    public static boolean isBackup(File file) {
        if (file == null || !file.getName().toLowerCase().endsWith(EXTENSION)) {
            return false;
        }
        JsonObject root = readBackup(file);
        return root != null && countKnownKeys(root) > 0;
    }

    public static JsonObject readBackup(File file) {
        try {
            String content = readAndDecrypt(file);
            if (TextUtils.isEmpty(content)) {
                return null;
            }
            JsonElement parsed = GSON.fromJson(content, JsonElement.class);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Сколько настроек в файле мы понимаем — заодно проверка, что файл вообще наш. */
    public static int countKnownKeys(JsonObject root) {
        int count = 0;
        for (Entry entry : entries()) {
            JsonElement value = valueOf(root, entry);
            if (value != null && isExpected(entry, value)) {
                count++;
            }
        }
        return count;
    }

    /** Применяет всё, что прошло проверку. Возвращает число применённых настроек. */
    public static int applyBackup(JsonObject root) {
        ensureConfigsLoaded();
        int applied = 0;
        for (Entry entry : entries()) {
            JsonElement value = valueOf(root, entry);
            if (value == null || !isExpected(entry, value)) {
                continue;
            }
            try {
                entry.applier.apply(value);
                applied++;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return applied;
    }

    // ---- Чтение файла ----

    private static String readAndDecrypt(File file) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        String content = builder.toString();
        return InvisibleEncryptor.isEncrypted(content) ? InvisibleEncryptor.decode(content) : content;
    }

    private static JsonElement valueOf(JsonObject root, Entry entry) {
        JsonObject section = sectionOf(root, entry.section);
        return section == null ? null : section.get(entry.key);
    }

    /**
     * Секция по имени. У mainconfig бывает суффикс аккаунта (mainconfig2) — exteraGram берёт
     * первую подходящую, повторяем.
     */
    private static JsonObject sectionOf(JsonObject root, String name) {
        if (root == null) {
            return null;
        }
        if (root.has(name) && root.get(name).isJsonObject()) {
            return root.getAsJsonObject(name);
        }
        if (!SECTION_MAIN.equals(name)) {
            return null;
        }
        for (String key : root.keySet()) {
            if (key.matches("^mainconfig\\d+$") && root.get(key).isJsonObject()) {
                return root.getAsJsonObject(key);
            }
        }
        return null;
    }

    // ---- Проверка значений ----

    private static boolean isExpected(Entry entry, JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        switch (entry.kind) {
            case KIND_BOOL:
                return primitive.isBoolean();
            case KIND_INT: {
                Integer value = exactInteger(primitive);
                return value != null && value >= entry.min && value <= entry.max;
            }
            case KIND_FLOAT: {
                if (!primitive.isNumber()) {
                    return false;
                }
                float value = primitive.getAsFloat();
                return !Float.isNaN(value) && !Float.isInfinite(value)
                        && value >= entry.min && value <= entry.max;
            }
            case KIND_STRING: {
                if (!primitive.isString()) {
                    return false;
                }
                String value = primitive.getAsString();
                if (value.length() > 1048576) {
                    return false;
                }
                return entry.stringCheck == null ? !TextUtils.isEmpty(value) : entry.stringCheck.test(value);
            }
            default:
                return false;
        }
    }

    private static Integer exactInteger(JsonPrimitive primitive) {
        if (!primitive.isNumber()) {
            return null;
        }
        try {
            return new BigDecimal(primitive.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    // ---- Загрузка наших конфигов ----

    private static void ensureConfigsLoaded() {
        try {
            OpenExteraConfig.init();
            AppearanceConfig.init();
            ChatsConfig.init();
            GeneralConfig.init();
            IconPacksConfig.init();
            UtilsConfig.init();
            PillStackConfig.init();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static SharedPreferences mainPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
    }

    private static SharedPreferences pluginPreferences() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PluginsConstants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---- Таблица соответствий ----

    private static synchronized List<Entry> entries() {
        if (entries != null) {
            return entries;
        }
        ArrayList<Entry> list = new ArrayList<>();

        bool(list, "disableNumberRounding", NekoConfig.disableNumberRounding);
        bool(list, "formatTimeWithSeconds", NekoConfig.showSeconds);
        bool(list, "relativeLastSeen", OpenExteraConfig.relativeLastSeen);
        bool(list, "filterZalgo", NaConfig.INSTANCE.getZalgoFilter());
        bool(list, "uploadSpeedBoost", NekoConfig.uploadBoost);
        bool(list, "hidePhoneNumber", NekoConfig.hidePhone);
        bool(list, "hideArchiveFolder", NaConfig.INSTANCE.getHideArchive());
        bool(list, "archiveOnPull", NekoConfig.openArchiveOnPull);
        bool(list, "disableUnarchiveSwipe", NaConfig.INSTANCE.getDoNotUnarchiveBySwipe());
        integer(list, "doNotUseProxy", OpenExteraConfig.proxyDisableConditions, 0, 7);
        bool(list, "singleCornerRadius", AppearanceConfig.singleCornerRadius);
        bool(list, "hideActionBarStatus", AppearanceConfig.hideActionBarStatus);
        bool(list, "centerTitle", AppearanceConfig.centerTitle);
        bool(list, "hideStories", NaConfig.INSTANCE.getDisableStories());
        bool(list, "hideFloatingButton", NaConfig.INSTANCE.getDisableDialogsFloatingButton());
        bool(list, "hideDialogsSearchBar", NaConfig.INSTANCE.getHideDialogsSearchField());
        bool(list, "senderMiniAvatars", AppearanceConfig.senderMiniAvatars);
        bool(list, "hideAllChats", NekoConfig.hideAllTab);
        bool(list, "squareFab", AppearanceConfig.squareFab);
        bool(list, "sectionsSeparatedHeaders", AppearanceConfig.separateHeaders);
        bool(list, "newLoadingStyle", AppearanceConfig.newLoadingStyle);
        bool(list, "newChatHeaderStyle", AppearanceConfig.newChatHeaderStyle);
        bool(list, "newNavigationBarStyle", AppearanceConfig.newNavigationBarStyle);
        bool(list, "iosNavigationBarStyle", AppearanceConfig.iosNavigationBarStyle);
        bool(list, "iosFirstFolderOnTabTap", AppearanceConfig.iosFirstFolderOnTabTap);
        bool(list, "iosBackCounter", AppearanceConfig.iosBackCounter);
        bool(list, "useSystemFonts", NekoConfig.typeface);
        bool(list, "gooeyAvatarAnimation", AppearanceConfig.gooeyAvatarAnimation);
        bool(list, "customThemes", AppearanceConfig.customThemes);
        bool(list, "glassMessageMenu", AppearanceConfig.glassMessageMenu);
        bool(list, "navigationDrawer", AppearanceConfig.navigationDrawer);
        bool(list, "immersiveDrawerAnimation", AppearanceConfig.immersiveDrawerAnimation);
        bool(list, "showFeedTab", AppearanceConfig.showFeedTab);
        bool(list, "hideStickerTime", NekoConfig.hideTimeForSticker);
        bool(list, "replyColors", ChatsConfig.replyColors);
        bool(list, "replyEmoji", ChatsConfig.replyEmoji);
        bool(list, "replyBackground", ChatsConfig.replyBackground);
        bool(list, "hideReactionsInPrivateChats", ChatsConfig.hideReactionsInPrivate);
        bool(list, "hideReactionsInChannels", ChatsConfig.hideReactionsInChannels);
        bool(list, "hideReactionsInGroups", ChatsConfig.hideReactionsInGroups);
        bool(list, "disableGreetingSticker", NekoConfig.dontSendGreetingSticker);
        bool(list, "hideKeyboardOnScroll", NekoConfig.hideKeyboardOnChatScroll);
        bool(list, "addCommaAfterMention", OpenExteraConfig.addCommaAfterMention);
        bool(list, "hideSendAsPeer", NekoConfig.hideSendAsChannel);
        bool(list, "removeMessageTail", ChatsConfig.removeMessageTail);
        bool(list, "replaceEditedWithIcon", NaConfig.INSTANCE.getUseEditedIcon());
        bool(list, "showOnlineStatus", NaConfig.INSTANCE.getShowOnlineStatus());
        bool(list, "hideShareButton", NaConfig.INSTANCE.getHideShareButtonInChannel());
        bool(list, "wideChannelPosts", ChatsConfig.wideChannelPosts);
        bool(list, "wideFeedPosts", ChatsConfig.wideFeedPosts);
        bool(list, "showResultsBeforeVoting", ChatsConfig.showResultsBeforeVoting);
        bool(list, "showCopyPhotoButton", NaConfig.INSTANCE.getShowCopyPhoto());
        bool(list, "showSaveMessageButton", NekoConfig.showAddToSavedMessages);
        bool(list, "showRepeatMessageButton", NekoConfig.showRepeat);
        bool(list, "showClearButton", NekoConfig.showDeleteDownloadedFile);
        bool(list, "showHistoryButton", NekoConfig.showViewHistory);
        bool(list, "showReportButton", NekoConfig.showReport);
        bool(list, "showDetailsButton", NekoConfig.showMessageDetails);
        bool(list, "groupMessageMenu", NaConfig.INSTANCE.getGroupedMessageMenu());
        bool(list, "extendedFramesPerSecond", ChatsConfig.extendedFramesPerSecond);
        bool(list, "cameraStabilization", ChatsConfig.cameraStabilization);
        bool(list, "cameraMirrorMode", ChatsConfig.cameraMirrorMode);
        bool(list, "rememberLastUsedCamera", ChatsConfig.rememberLastUsedCamera);
        bool(list, "startWithWideAngleCamera", ChatsConfig.startWithWideAngleCamera);
        bool(list, "staticZoom", ChatsConfig.staticZoom);
        bool(list, "alwaysSendInHD", ChatsConfig.alwaysSendInHD);
        bool(list, "hideCameraTile", ChatsConfig.hideCameraTile);
        bool(list, "preferOriginalQuality", ChatsConfig.preferOriginalQuality);
        bool(list, "swipeToPip", ChatsConfig.swipeToPip);
        bool(list, "unmuteWithVolumeButtons", ChatsConfig.unmuteWithVolumeButtons);
        bool(list, "pauseOnMinimizeVideo", NekoConfig.autoPauseVideo);
        bool(list, "pauseOnMinimizeVoice", ChatsConfig.pauseOnMinimizeVoice);
        bool(list, "pauseOnMinimizeRound", ChatsConfig.pauseOnMinimizeRound);
        bool(list, "useSystemIconShape", IconPacksConfig.useSystemIconShape);
        bool(list, "infiniteScrolling", PillStackConfig.infiniteScrolling);

        bool(list, "useGoogleCrashlytics", GeneralConfig.crashReports);

        // Инверсия: у exteraGram тумблер «включено», у NagramX — «выключено».
        boolInverted(list, "inAppVibration", NekoConfig.disableVibration);

        list.add(new Entry(SECTION_EXTERA, "tabCounter", KIND_BOOL, 0, 0, null,
                () -> new JsonPrimitive(NaConfig.INSTANCE.getIgnoreUnreadCount().Int()
                        != NekoConfig.DIALOG_FILTER_EXCLUDE_ALL),
                value -> NaConfig.INSTANCE.getIgnoreUnreadCount().setConfigInt(value.getAsBoolean()
                        ? NekoConfig.DIALOG_FILTER_EXCLUDE_NONE
                        : NekoConfig.DIALOG_FILTER_EXCLUDE_ALL)));

        integer(list, "titleText", AppearanceConfig.titleText, 0, 3);
        integer(list, "downloadSpeedBoost", GeneralConfig.downloadSpeedBoost, 0, 2);
        integer(list, "tabletMode", NekoConfig.tabletMode, 0, 2);
        integer(list, "glassOutlineStyle", AppearanceConfig.glassOutlineStyle, 0, 2);
        integer(list, "stickerShape", ChatsConfig.stickerShape, 0, 2);
        integer(list, "doubleTapSeekDuration", ChatsConfig.doubleTapSeekDuration, 0, 3);
        integer(list, "cameraType", ChatsConfig.cameraType, 0, 2);
        integer(list, "videoMessagesCamera", NaConfig.INSTANCE.getCameraInVideoMessages(), 0, 2);

        // Провайдеры перевода нумерованы по-разному: 0/1/2/3 у exteraGram против 8/1/3/7 у NagramX.
        remap(list, "translationProvider", NekoConfig.translationProvider, 0, 3,
                new int[]{8, 1, 3, 7}, new int[][]{{8, 0}, {1, 1}, {3, 2}, {7, 3}});
        // Вкладки: exteraGram 0 иконки+текст, 1 текст, 2 иконки; NagramX 0 текст, 1 иконки, 2 микс.
        remap(list, "tabIcons", NekoConfig.tabsTitleType, 0, 2,
                new int[]{2, 0, 1}, new int[][]{{0, 1}, {1, 2}, {2, 0}});
        // Действия по двойному тапу: наборы не совпадают, переносим только общие.
        remap(list, "doubleTapAction", NaConfig.INSTANCE.getDoubleTapAction(), 0, 8,
                new int[]{0, 2, 4, -1, -1, 5, 6, 10, 3},
                new int[][]{{0, 0}, {1, 1}, {2, 1}, {3, 8}, {4, 2}, {5, 5}, {6, 6}, {10, 7}});
        remap(list, "doubleTapActionOutOwner", NaConfig.INSTANCE.getDoubleTapActionOut(), 0, 9,
                new int[]{0, 2, 4, -1, -1, 8, 5, 6, 10, 3},
                new int[][]{{0, 0}, {1, 1}, {2, 1}, {3, 9}, {4, 2}, {5, 6}, {6, 7}, {8, 5}, {10, 8}});

        floatToInt(list, "avatarCorners", AppearanceConfig.avatarCorners, 0, 28, 1f);
        floatToInt(list, "sectionRadius", AppearanceConfig.sectionRadius, 0, 28, 1f);
        floatToInt(list, "predictiveBackIntensity", UtilsConfig.predictiveBackIntensity, 0, 5, 100f);
        floatToInt(list, "flashWarmth", ChatsConfig.flashWarmth, 0, 1, 100f);
        floatToInt(list, "flashIntensity", ChatsConfig.flashIntensity, 0, 1, 100f);

        decimal(list, "stickerSize", NekoConfig.stickerSize, 4, 20);

        text(list, SECTION_EXTERA, "customSavePath", NekoConfig.customSavePath,
                value -> value.matches("^(?!\\.{1,2}$)[A-Za-z0-9._ -]{1,255}$"));
        text(list, SECTION_EXTERA, "gramTargetCurrency", PillStackConfig.gramTargetCurrency, EtgBackup::isCurrency);
        text(list, SECTION_EXTERA, "btcTargetCurrency", PillStackConfig.btcTargetCurrency, EtgBackup::isCurrency);
        text(list, SECTION_EXTERA, "usdTargetCurrency", PillStackConfig.usdTargetCurrency, EtgBackup::isCurrency);
        text(list, SECTION_PILLS, "activePills", PillStackConfig.activePillsRaw, EtgBackup::isPillsLayout);
        text(list, SECTION_PILLS, "hiddenPills", PillStackConfig.hiddenPillsRaw, EtgBackup::isPillsLayout);

        // «app» у exteraGram и «» у NagramX означают одно и то же — язык приложения.
        list.add(new Entry(SECTION_EXTERA, "targetLang", KIND_STRING, 0, 0,
                value -> value.equalsIgnoreCase("app") || value.matches("^[a-zA-Z]{1,8}(-[a-zA-Z0-9]{1,8})*$"),
                () -> {
                    String value = NekoConfig.translateToLang.String();
                    return new JsonPrimitive(TextUtils.isEmpty(value) ? "app" : value);
                },
                value -> {
                    String text = value.getAsString();
                    NekoConfig.translateToLang.setConfigString("app".equalsIgnoreCase(text) ? "" : text);
                }));

        addMainMenuEntries(list);
        addCompositeEntries(list);
        addPluginEntries(list);
        addMainConfigEntries(list);

        entries = list;
        return entries;
    }

    private static void addCompositeEntries(List<Entry> list) {
        // showIdAndDc: 0 скрыть, 1 Telegram API, 2 Bot API. У нас только тумблер.
        list.add(new Entry(SECTION_EXTERA, "showIdAndDc", KIND_INT, 0, 2, null,
                () -> new JsonPrimitive(NekoConfig.showIdAndDc.Bool() ? 1 : 0),
                value -> NekoConfig.showIdAndDc.setConfigBool(value.getAsInt() != 0)));

        // springAnimations у нас стало стилем анимации «назад»: 1 — пружина.
        list.add(new Entry(SECTION_EXTERA, "springAnimations", KIND_BOOL, 0, 0, null,
                () -> new JsonPrimitive(NaConfig.INSTANCE.getBackAnimationStyle().Int() == 1),
                value -> NaConfig.INSTANCE.getBackAnimationStyle().setConfigInt(value.getAsBoolean() ? 1 : 0)));

        // Стили MD3 у NagramX трёхпозиционные: 0 стандарт, 1 modern, 2 MD3.
        list.add(new Entry(SECTION_EXTERA, "newSliderStyle", KIND_BOOL, 0, 0, null,
                () -> new JsonPrimitive(NaConfig.INSTANCE.getSliderStyle().Int() == 2),
                value -> NaConfig.INSTANCE.getSliderStyle().setConfigInt(value.getAsBoolean() ? 2 : 0)));
        list.add(new Entry(SECTION_EXTERA, "newSwitchStyle", KIND_BOOL, 0, 0, null,
                () -> new JsonPrimitive(NaConfig.INSTANCE.getSwitchStyle().Int() == 2),
                value -> NaConfig.INSTANCE.getSwitchStyle().setConfigInt(value.getAsBoolean() ? 2 : 0)));

        // Разделители: 0 скрыт, 1 линия, 2 сегменты. Ноль дублируется в рабочий ключ NagramX.
        list.add(new Entry(SECTION_EXTERA, "dividerStyle", KIND_INT, 0, 2, null,
                () -> new JsonPrimitive(AppearanceConfig.dividerStyle.Int()),
                value -> {
                    int style = value.getAsInt();
                    AppearanceConfig.dividerStyle.setConfigInt(style);
                    NaConfig.INSTANCE.getHideDividers().setConfigBool(style == 0);
                }));

        // Кнопка внизу канала: 0 скрыть, 1 «Без звука», 2 «Обсудить».
        list.add(new Entry(SECTION_EXTERA, "bottomButton", KIND_INT, 0, 2, null,
                () -> new JsonPrimitive(ChatsConfig.bottomButton.Int()),
                value -> {
                    int index = value.getAsInt();
                    ChatsConfig.bottomButton.setConfigInt(index);
                    NaConfig.INSTANCE.getDisableChannelMuteButton()
                            .setConfigBool(index == ChatsConfig.BOTTOM_BUTTON_HIDE);
                }));

        // Нижняя панель: 0 обычная, 1 скрыта, 2 плавающая. Плавающей у нас нет — она обычная.
        list.add(new Entry(SECTION_EXTERA, "bottomNavigationBarMode", KIND_INT, 0, 2, null,
                () -> new JsonPrimitive(NaConfig.INSTANCE.getHideBottomNavigationBar().Bool() ? 1 : 0),
                value -> NaConfig.INSTANCE.getHideBottomNavigationBar()
                        .setConfigBool(value.getAsInt() == 1)));

        // Безлимит недавних стикеров — один тумблер поверх двух ключей NagramX.
        list.add(new Entry(SECTION_EXTERA, "unlimitedRecentStickers", KIND_BOOL, 0, 0, null,
                () -> new JsonPrimitive(NekoConfig.maxRecentStickerCount.Int() > RECENT_STICKERS_DEFAULT),
                value -> NekoConfig.maxRecentStickerCount.setConfigInt(
                        value.getAsBoolean() ? RECENT_STICKERS_MAX : RECENT_STICKERS_DEFAULT)));

        // Быстрые действия администратора — поверх пяти пунктов меню чата и пункта меню сообщения.
        list.add(new Entry(SECTION_EXTERA, "quickAdminShortcuts", KIND_BOOL, 0, 0, null,
                () -> {
                    for (ConfigItem item : adminShortcutItems()) {
                        if (item.Bool()) {
                            return new JsonPrimitive(true);
                        }
                    }
                    return new JsonPrimitive(false);
                },
                value -> {
                    boolean enabled = value.getAsBoolean();
                    for (ConfigItem item : adminShortcutItems()) {
                        item.setConfigBool(enabled);
                    }
                }));
    }

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

    /**
     * Боковое меню. У exteraGram два JSON-массива, у нас одна строка «видимые;скрытые»,
     * поэтому обе половины пишутся и читаются согласованно.
     */
    private static void addMainMenuEntries(List<Entry> list) {
        list.add(new Entry(SECTION_EXTERA, "mainMenuLayout", KIND_STRING, 0, 0,
                value -> isIdArray(value, true),
                () -> new JsonPrimitive(GSON.toJson(MainMenuLayout.getLayout())),
                value -> MainMenuLayout.save(parseIds(value.getAsString()), MainMenuLayout.getHiddenItems())));
        list.add(new Entry(SECTION_EXTERA, "mainMenuHiddenItems", KIND_STRING, 0, 0,
                value -> isIdArray(value, false),
                () -> new JsonPrimitive(GSON.toJson(MainMenuLayout.getHiddenItems())),
                value -> MainMenuLayout.save(MainMenuLayout.getLayout(), parseIds(value.getAsString()))));
    }

    private static void addPluginEntries(List<Entry> list) {
        pluginFlag(list, "pluginsSafeMode", PluginsConstants.KEY_SAFE_MODE);
        pluginFlag(list, "pluginsDevMode", PluginsConstants.KEY_DEVELOPER_MODE);
        pluginFlag(list, "pluginsCompactView", PluginsConstants.KEY_COMPACT_VIEW);
        pluginFlag(list, "pluginsDisableArtOpts", PluginsConstants.KEY_COMPATIBILITY_MODE);
    }

    /**
     * mainconfig — настройки самого Telegram. Пишем в тот же файл, что и SharedConfig,
     * и обновляем поля в памяти: перечитывания у SharedConfig нет.
     */
    private static void addMainConfigEntries(List<Entry> list) {
        list.add(new Entry(SECTION_MAIN, "ChatSwipeAction", KIND_INT, 0, 5, null,
                () -> {
                    int value = mainPreferences().getInt("ChatSwipeAction", -1);
                    return value < 0 ? null : new JsonPrimitive(value);
                },
                value -> SharedConfig.updateChatListSwipeSetting(value.getAsInt())));
        list.add(new Entry(SECTION_MAIN, "mediaColumnsCount", KIND_INT, 2, 9, null,
                () -> new JsonPrimitive(SharedConfig.mediaColumnsCount),
                value -> SharedConfig.setMediaColumnsCount(value.getAsInt())));
        list.add(new Entry(SECTION_MAIN, "bubbleRadius", KIND_INT, 0, 17, null,
                () -> new JsonPrimitive(SharedConfig.bubbleRadius),
                value -> {
                    SharedConfig.bubbleRadius = value.getAsInt();
                    mainPreferences().edit().putInt("bubbleRadius", SharedConfig.bubbleRadius).apply();
                }));
        list.add(new Entry(SECTION_MAIN, "fons_size", KIND_INT, 12, 30, null,
                () -> new JsonPrimitive(SharedConfig.fontSize),
                value -> {
                    SharedConfig.fontSize = value.getAsInt();
                    SharedConfig.fontSizeIsDefault = false;
                    mainPreferences().edit().putInt("fons_size", SharedConfig.fontSize).apply();
                }));
    }

    // ---- Строители записей ----

    private static void bool(List<Entry> list, String key, ConfigItem item) {
        list.add(new Entry(SECTION_EXTERA, key, KIND_BOOL, 0, 0, null,
                () -> new JsonPrimitive(item.Bool()),
                value -> item.setConfigBool(value.getAsBoolean())));
    }

    private static void boolInverted(List<Entry> list, String key, ConfigItem item) {
        list.add(new Entry(SECTION_EXTERA, key, KIND_BOOL, 0, 0, null,
                () -> new JsonPrimitive(!item.Bool()),
                value -> item.setConfigBool(!value.getAsBoolean())));
    }

    private static void integer(List<Entry> list, String key, ConfigItem item, int min, int max) {
        list.add(new Entry(SECTION_EXTERA, key, KIND_INT, min, max, null,
                () -> new JsonPrimitive(item.Int()),
                value -> item.setConfigInt(value.getAsInt())));
    }

    /**
     * Значение с разной нумерацией. {@code toOurs} — таблица «значение exteraGram → наше»
     * ({@code -1} = переносить нечего), {@code toEtg} — пары «наше значение, значение exteraGram».
     */
    private static void remap(List<Entry> list, String key, ConfigItem item, int min, int max,
                              int[] toOurs, int[][] toEtg) {
        list.add(new Entry(SECTION_EXTERA, key, KIND_INT, min, max, null,
                () -> {
                    int current = item.Int();
                    for (int[] pair : toEtg) {
                        if (pair[0] == current) {
                            return new JsonPrimitive(pair[1]);
                        }
                    }
                    return null;
                },
                value -> {
                    int index = value.getAsInt();
                    if (index >= 0 && index < toOurs.length && toOurs[index] >= 0) {
                        item.setConfigInt(toOurs[index]);
                    }
                }));
    }

    /** Дробное значение exteraGram против целого у нас: {@code наше = etg * scale}. */
    private static void floatToInt(List<Entry> list, String key, ConfigItem item,
                                   float min, float max, float scale) {
        list.add(new Entry(SECTION_EXTERA, key, KIND_FLOAT, min, max, null,
                () -> new JsonPrimitive(item.Int() / scale),
                value -> item.setConfigInt(Math.round(value.getAsFloat() * scale))));
    }

    private static void decimal(List<Entry> list, String key, ConfigItem item, float min, float max) {
        list.add(new Entry(SECTION_EXTERA, key, KIND_FLOAT, min, max, null,
                () -> new JsonPrimitive(item.Float()),
                value -> item.setConfigFloat(value.getAsFloat())));
    }

    private static void text(List<Entry> list, String section, String key, ConfigItem item,
                             Predicate<String> check) {
        list.add(new Entry(section, key, KIND_STRING, 0, 0, check,
                () -> new JsonPrimitive(item.String()),
                value -> item.setConfigString(value.getAsString())));
    }

    private static void pluginFlag(List<Entry> list, String key, String prefsKey) {
        list.add(new Entry(SECTION_EXTERA, key, KIND_BOOL, 0, 0, null,
                () -> new JsonPrimitive(pluginPreferences().getBoolean(prefsKey, false)),
                value -> pluginPreferences().edit().putBoolean(prefsKey, value.getAsBoolean()).apply()));
    }

    // ---- Проверки строковых значений ----

    private static boolean isCurrency(String value) {
        return value.matches("^[A-Z]{3,5}$");
    }

    private static boolean isPillsLayout(String value) {
        if (TextUtils.isEmpty(value)) {
            return true;
        }
        if (value.length() > 4096) {
            return false;
        }
        ArrayList<Integer> seen = new ArrayList<>();
        for (String part : value.split(",")) {
            int id;
            try {
                id = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                return false;
            }
            if (id <= 0 || id > 100000 || seen.contains(id)) {
                return false;
            }
            seen.add(id);
        }
        return seen.size() <= 100;
    }

    private static boolean isIdArray(String value, boolean allowDivider) {
        try {
            JsonElement parsed = GSON.fromJson(value, JsonElement.class);
            if (parsed == null || !parsed.isJsonArray() || parsed.getAsJsonArray().size() > 100) {
                return false;
            }
            ArrayList<Integer> seen = new ArrayList<>();
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonPrimitive()) {
                    return false;
                }
                Integer id = exactInteger(element.getAsJsonPrimitive());
                if (id == null) {
                    return false;
                }
                if (id == -1) {
                    if (!allowDivider) {
                        return false;
                    }
                } else if (seen.contains(id)) {
                    return false;
                } else {
                    seen.add(id);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<Integer> parseIds(String value) {
        ArrayList<Integer> ids = new ArrayList<>();
        try {
            JsonElement parsed = GSON.fromJson(value, JsonElement.class);
            if (parsed != null && parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) {
                    Integer id = exactInteger(element.getAsJsonPrimitive());
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return ids;
    }
}
