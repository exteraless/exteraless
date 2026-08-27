package app.exteraless.appearance

import android.content.SharedPreferences
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.ui.Components.blur3.GlassOutlineStyle
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.config.ConfigItem

/**
 * Настройки экрана «Appearance», перенесённые из exteraGram.
 *
 * Здесь живут ТОЛЬКО те настройки, аналогов которых нет в NagramX
 * ([xyz.nextalone.nagram.NaConfig] / [tw.nekomimi.nekogram.NekoConfig]).
 * Всё остальное экран берёт из существующих конфигов, чтобы не плодить дубли.
 *
 * Схема повторяет [app.exteraless.OpenExteraConfig]: те же SharedPreferences, тот же [ConfigItem],
 * собственный список. Ключи с префиксом OEAppearance.
 *
 * Загрузка ленивая: [ensureLoaded] вызывается из геттеров, потому что ApplicationLoader
 * трогать нельзя. После первой загрузки проверка стоит один volatile-read.
 */
object AppearanceConfig {

    private val sync = Any()
    private val configs = ArrayList<ConfigItem>()

    @Volatile
    private var configLoaded = false

    /** Максимум слайдера закругления аватарок: радиус = половина стороны, то есть круг. */
    const val AVATAR_CORNERS_MAX = 28

    /** Темы Monet на ролях Telemone — то, чем набор стал 19.08.2026. */
    const val MONET_STYLE_TELEMONE = 0

    /** Прежний набор Monet, пришедший вместе с базой форка (токены вида `a1_100`). */
    const val MONET_STYLE_CLASSIC = 1

    @JvmStatic
    fun getPreferences(): SharedPreferences = NekoConfig.getPreferences()

    // ---- Аватары ----

    /** Закругление аватарок: 0 — квадрат, [AVATAR_CORNERS_MAX] — круг. */
    @JvmField
    val avatarCorners =
        addConfig("OEAppearanceAvatarCorners", ConfigItem.configTypeInt, AVATAR_CORNERS_MAX)

    /** Единое закругление: форумы получают ту же форму аватарки, что и обычные чаты. */
    @JvmField
    val singleCornerRadius =
        addConfig("OEAppearanceSingleCornerRadius", ConfigItem.configTypeBool, false)

    // ---- Список чатов ----

    /** Мини-аватарки отправителей в списке чатов. */
    @JvmField
    val senderMiniAvatars =
        addConfig("OEAppearanceSenderMiniAvatars", ConfigItem.configTypeBool, true)

    /** Прятать эмодзи-статус рядом с заголовком шапки. Дефолт false, как в exteraGram. */
    @JvmField
    val hideActionBarStatus =
        addConfig("OEAppearanceHideActionBarStatus", ConfigItem.configTypeBool, false)

    /**
     * Текст заголовка списка чатов: 0 — имя приложения, 1 — username, 2 — имя,
     * 3 — «Чаты». Только UI.
     */
    @JvmField
    val titleText =
        addConfig("OEAppearanceTitleText", ConfigItem.configTypeInt, 0)

    /**
     * Какой набор темы Monet берут «Monet Light/Dark/AMOLED».
     *
     * Наборы отличаются словарём токенов, а не механикой: [MONET_STYLE_TELEMONE] собран
     * из ролей Material 3, [MONET_STYLE_CLASSIC] — из тональных палитр базы форка. На части
     * прошивок роли M3 система отдаёт со своими поправками, и второй набор выглядит ровнее.
     */
    @JvmField
    val monetStyle =
        addConfig("OEAppearanceMonetStyle", ConfigItem.configTypeInt, MONET_STYLE_TELEMONE)

    /** Суффикс файла темы для выбранного набора: пустой для Telemone. */
    @JvmStatic
    fun monetAssetSuffix(): String =
        if (monetStyle.Int() == MONET_STYLE_CLASSIC) "_gram" else ""

    // ---- Только UI: аналогов в NagramX нет, визуальный эффект пока не подключён ----

    /**
     * Квадратная («squircle») плавающая кнопка вместо круглой. Дефолт true, как в exteraGram —
     * одно из самых заметных отличий экстеры из коробки.
     * Радиус считается как ceil(size * 16 / 56) dp, то есть 16 dp при кнопке 56 dp.
     */
    @JvmField
    val squareFab = addConfig("OEAppearanceSquareFab", ConfigItem.configTypeBool, true)

    @JvmStatic
    fun squareFab(): Boolean {
        ensureLoaded()
        return squareFab.Bool()
    }

    /** Радиус скругления кнопки со стороной [size] dp. */
    @JvmStatic
    fun fabCornerRadius(size: Int): Int =
        if (squareFab()) Math.ceil((size * 16) / 56.0).toInt() else size / 2

    /** Заголовок ActionBar по центру. Дефолт false, как в exteraGram (BooleanPref(0)). */
    @JvmField
    val centerTitle = addConfig("OEAppearanceCenterTitle", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun centerTitle(): Boolean {
        ensureLoaded()
        return centerTitle.Bool()
    }

    /** «Gooey»-анимация аватарки при оттягивании шапки профиля. Дефолт true, как в exteraGram. */
    @JvmField
    val gooeyAvatarAnimation =
        addConfig("OEAppearanceGooeyAvatarAnimation", ConfigItem.configTypeBool, true)

    @JvmStatic
    fun gooeyAvatarAnimation(): Boolean {
        ensureLoaded()
        return gooeyAvatarAnimation.Bool()
    }

    /**
     * Индивидуальные темы и обои в чатах.
     *
     * Выключено — приложение не применяет тему и обои, выставленные для
     * конкретного диалога, и везде остаётся общая тема. exteraGram гейтит этим
     * ключом две точки: ChatActivity.setupChatTheme (12.9.0:12501) и
     * ChatThemeController.getDialogWallpaper (12.9.0:950).
     *
     * По умолчанию включено — как в exteraGram: иначе обновление приложения
     * молча погасило бы у всех уже настроенные темы чатов.
     */
    @JvmField
    val customThemes =
        addConfig("OEAppearanceCustomThemes", ConfigItem.configTypeBool, true)

    @JvmStatic
    fun customThemes(): Boolean {
        ensureLoaded()
        return customThemes.Bool()
    }

    /** Радиус карточек-секций, dp. Применяется ко всем спискам через RecyclerListView.setSections(). */
    @JvmField
    val sectionRadius =
        addConfig("OEAppearanceSectionRadius", ConfigItem.configTypeInt, 20)

    /** Отдельные заголовки секций. Только UI. */
    @JvmField
    val separateHeaders =
        addConfig("OEAppearanceSeparateHeaders", ConfigItem.configTypeBool, true)

    /** Стиль разделителя: 0 — скрыт, 1 — линия, 2 — сегменты. 0/1 привязаны к NaConfig.hideDividers, 2 — только UI. */
    @JvmField
    val dividerStyle =
        addConfig("OEAppearanceDividerStyle", ConfigItem.configTypeInt, 1)

    /** Стиль стеклянного контура: 0 — блик, 1 — сплошной, 2 — скрыт. Только UI. */
    @JvmField
    val glassOutlineStyle =
        addConfig(object : ConfigItem("OEAppearanceGlassOutlineStyle", ConfigItem.configTypeInt, 0) {
            override fun setConfigInt(v: Int) {
                val changed = Int() != v
                super.setConfigInt(v)
                if (changed) GlassOutlineStyle.dispatchChange()
            }
        })

    /** Стеклянное меню сообщения. Дефолт true, как в exteraGram (BooleanPref(1)). */
    @JvmField
    val glassMessageMenu =
        addConfig("OEAppearanceGlassMessageMenu", ConfigItem.configTypeBool, true)

    @JvmStatic
    fun glassMessageMenu(): Boolean {
        ensureLoaded()
        return glassMessageMenu.Bool()
    }

    @JvmStatic
    fun glassOutlineStyle(): Int {
        ensureLoaded()
        return glassOutlineStyle.Int()
    }

    // ---- Material Design 3 ----
    // switchStyle и sliderStyle уже есть у NagramX (NaConfig, дефолт 2 = MD3) — не дублируем.

    /** M3-индикаторы загрузки. Дефолт true, как в exteraGram. */
    @JvmField
    val newLoadingStyle =
        addConfig("OEAppearanceNewLoadingStyle", ConfigItem.configTypeBool, true)

    /** M3-шапка чата. Дефолт false, как в exteraGram. */
    @JvmField
    val newChatHeaderStyle =
        addConfig("OEAppearanceNewChatHeaderStyle", ConfigItem.configTypeBool, false)

    /** M3-нижняя панель вкладок. Дефолт false, как в exteraGram. */
    @JvmField
    val newNavigationBarStyle =
        addConfig("OEAppearanceNewNavigationBarStyle", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun newLoadingStyle(): Boolean {
        ensureLoaded()
        return newLoadingStyle.Bool()
    }

    @JvmStatic
    fun newChatHeaderStyle(): Boolean {
        ensureLoaded()
        return newChatHeaderStyle.Bool()
    }

    @JvmStatic
    fun newNavigationBarStyle(): Boolean {
        ensureLoaded()
        return newNavigationBarStyle.Bool()
    }

    /** Широкая нижняя панель как в Telegram iOS. Уступает M3-панели, если включены обе. */
    @JvmField
    val iosNavigationBarStyle =
        addConfig("OEAppearanceIosNavigationBarStyle", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun iosNavigationBarStyle(): Boolean {
        ensureLoaded()
        return iosNavigationBarStyle.Bool()
    }

    @JvmField
    val iosFirstFolderOnTabTap =
        addConfig("OEAppearanceIosFirstFolderOnTabTap", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun iosFirstFolderOnTabTap(): Boolean {
        ensureLoaded()
        return iosFirstFolderOnTabTap.Bool()
    }

    @JvmField
    val iosBackCounter =
        addConfig("OEAppearanceIosBackCounter", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun iosBackCounter(): Boolean {
        ensureLoaded()
        return iosBackCounter.Bool()
    }

    // ---- AI-функции Telegram ----

    /** Прячет кнопку AI-редактора в поле ввода, вложениях и подписи к медиа. */
    @JvmField
    val hideAiEditor =
        addConfig("OEAppearanceHideAiEditor", ConfigItem.configTypeBool, false)

    /** Прячет кнопку «саммари» на сообщении. */
    @JvmField
    val hideMessageSummary =
        addConfig("OEAppearanceHideMessageSummary", ConfigItem.configTypeBool, false)

    /** Прячет блок «Cocoon AI Summary» в Instant View. */
    @JvmField
    val hideIvSummary =
        addConfig("OEAppearanceHideIvSummary", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun hideAiEditor(): Boolean {
        ensureLoaded()
        return hideAiEditor.Bool()
    }

    @JvmStatic
    fun hideMessageSummary(): Boolean {
        ensureLoaded()
        return hideMessageSummary.Bool()
    }

    @JvmStatic
    fun hideIvSummary(): Boolean {
        ensureLoaded()
        return hideIvSummary.Bool()
    }

    // ---- Боковое меню ----

    /** Своя шторка бокового меню вместо стоковой. Дефолт false, как в exteraGram. */
    @JvmField
    val navigationDrawer =
        addConfig("OEAppearanceNavigationDrawer", ConfigItem.configTypeBool, false)

    /** Иммерсивная анимация открытия шторки. Дефолт false, как в exteraGram. */
    @JvmField
    val immersiveDrawerAnimation =
        addConfig("OEAppearanceImmersiveDrawer", ConfigItem.configTypeBool, false)

    /** Порядок и видимость пунктов бокового меню, сериализованный список id. */
    @JvmField
    val mainMenuLayout =
        addConfig("OEAppearanceMainMenuLayout", ConfigItem.configTypeString, "")

    // ---- Лента ----

    /**
     * Показывать ленту нижней вкладкой — на месте вкладки «Контакты».
     * Дефолт false, как в exteraGram (BooleanPref(0)).
     */
    @JvmField
    val showFeedTab = addConfig("OEAppearanceShowFeedTab", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun showFeedTab(): Boolean {
        ensureLoaded()
        return showFeedTab.Bool()
    }

    /** Счётчик непрочитанных постов на вкладке ленты. */
    @JvmField
    val showFeedUnreadCounter =
        addConfig("OEAppearanceFeedUnreadCounter", ConfigItem.configTypeBool, true)

    @JvmStatic
    fun showFeedUnreadCounter(): Boolean {
        ensureLoaded()
        return showFeedUnreadCounter.Bool()
    }

    @JvmStatic
    fun navigationDrawer(): Boolean {
        ensureLoaded()
        return navigationDrawer.Bool()
    }

    @JvmStatic
    fun immersiveDrawerAnimation(): Boolean {
        ensureLoaded()
        return immersiveDrawerAnimation.Bool()
    }

    // ---- Геттеры для Java (в том числе для горячих мест отрисовки) ----

    @JvmStatic
    fun avatarCorners(): Int {
        ensureLoaded()
        return avatarCorners.Int()
    }

    @JvmStatic
    fun singleCornerRadius(): Boolean {
        ensureLoaded()
        return singleCornerRadius.Bool()
    }

    @JvmStatic
    fun senderMiniAvatars(): Boolean {
        ensureLoaded()
        return senderMiniAvatars.Bool()
    }

    /** Текст заголовка списка чатов: 0 — имя приложения, 1 — username, 2 — имя, 3 — «Чаты». */
    @JvmStatic
    fun titleText(): Int {
        ensureLoaded()
        return titleText.Int()
    }

    /** Прятать ли эмодзи-статус в шапке списка чатов. */
    @JvmStatic
    fun hideActionBarStatus(): Boolean {
        ensureLoaded()
        return hideActionBarStatus.Bool()
    }

    /** true, если аватарки должны остаться обычными кругами — быстрый выход из хот-пути. */
    @JvmStatic
    fun avatarCornersDefault(): Boolean = avatarCorners() >= AVATAR_CORNERS_MAX

    /**
     * Квадратность аватарки: 0 — круг, 1 — квадрат.
     *
     * Обратная величина к [avatarCorners] и ровно то, что exteraGram зовёт
     * `getAvatarSquareness()`. Нужна там, где геометрия зависит от формы —
     * например, онлайн-точку на квадратной аватарке надо уводить в угол,
     * иначе она наползает на картинку.
     */
    @JvmStatic
    fun avatarSquareness(): Float =
        1f - (avatarCorners().coerceIn(0, AVATAR_CORNERS_MAX).toFloat() / AVATAR_CORNERS_MAX)

    /**
     * Смещение онлайн-точки от края аватарки.
     * У круга — заданное значение, у квадрата — по диагонали от угла.
     */
    @JvmStatic
    fun onlineDotOffset(base: Float, radius: Float): Float =
        base + ((radius / Math.sqrt(2.0)).toFloat() - base) * avatarSquareness()

    // ---- Секции настроек ----

    /** Радиус скругления карточек-секций, dp. 0 — острые углы (как сток). */
    @JvmStatic
    fun sectionRadius(): Int {
        ensureLoaded()
        return sectionRadius.Int()
    }

    /** Отдельные заголовки секций (заголовок — своя карточка). */
    @JvmStatic
    fun separateHeaders(): Boolean {
        ensureLoaded()
        return separateHeaders.Bool()
    }

    /**
     * Выносить ли заголовки секций из карточки — с учётом стиля разделителя.
     *
     * В режиме «Сегменты» каждая строка сама по себе карточка, и заголовок
     * внутри неё выглядел бы чужеродно, поэтому exteraGram включает вынос
     * принудительно (ExteraConfig.getSectionsSeparatedHeaders: SEGMENTS || pref).
     */
    @JvmStatic
    fun sectionsSeparatedHeaders(): Boolean {
        return separateHeaders() || dividerStyle() == DIVIDER_SEGMENTS
    }

    /** Стиль разделителя внутри карточки: 0 — скрыт, 1 — линия, 2 — сегменты. */
    @JvmStatic
    fun dividerStyle(): Int {
        ensureLoaded()
        return dividerStyle.Int()
    }

    const val DIVIDER_HIDDEN = 0
    const val DIVIDER_LINE = 1
    const val DIVIDER_SEGMENTS = 2

    /**
     * Кэш для [dividerHidden]. `Theme.getColor` — самый горячий путь отрисовки,
     * ходить туда через [ensureLoaded] на каждый вызов нельзя.
     * Обновляется из [invalidateDividerStyle] при смене настройки.
     */
    @Volatile
    private var dividerHiddenCache: Boolean? = null

    /**
     * Гасить ли `key_divider` целиком. У exteraGram прозрачный цвет отдаётся во всех режимах,
     * кроме [DIVIDER_LINE] — поэтому стоковые `drawLine(..., Theme.dividerPaint)`
     * перестают рисовать без правки самих ячеек
     */
    @JvmStatic
    fun dividerHidden(): Boolean {
        val cached = dividerHiddenCache
        if (cached != null) return cached
        val value = try {
            dividerStyle() != DIVIDER_LINE
        } catch (e: Exception) {
            false
        }
        dividerHiddenCache = value
        return value
    }

    /** Звать при смене стиля разделителя. */
    @JvmStatic
    fun invalidateDividerStyle() {
        dividerHiddenCache = null
    }

    /**
     * Радиус закругления для аватарки со стороной [size] пикселей.
     * При максимуме слайдера возвращает size / 2, то есть круг.
     */
    @JvmStatic
    @JvmOverloads
    fun getAvatarCorners(size: Float, cornerType: Int = CORNER_TYPE_DEFAULT, hasStories: Boolean = false): Int {
        val corners = avatarCorners()
        if (corners <= 0) return 0
        var value = size * corners / (AVATAR_CORNERS_MAX * 2.0)
        if (hasStories) {
            value -= AndroidUtilities.dpf2(2.5f)
        }
        if (!singleCornerRadius()) {
            when (cornerType) {
                CORNER_TYPE_FORUM -> value = value.toInt() * 42.0 / 64.0
                CORNER_TYPE_COMMUNITY -> value = value * 40.0 / 72.0
            }
        }
        if (value <= 0) return 0
        return Math.ceil(value).toInt()
    }

    const val CORNER_TYPE_DEFAULT = 0
    const val CORNER_TYPE_FORUM = 1
    const val CORNER_TYPE_COMMUNITY = 2

    private fun addConfig(key: String, type: Int, defaultValue: Any?): ConfigItem {
        val item = ConfigItem(key, type, defaultValue)
        configs.add(item)
        return item
    }

    private fun addConfig(item: ConfigItem): ConfigItem {
        configs.add(item)
        return item
    }

    @JvmStatic
    fun ensureLoaded() {
        if (!configLoaded) loadConfig(false)
    }

    @JvmStatic
    fun init() {
        loadConfig(false)
    }

    @JvmStatic
    fun loadConfig(force: Boolean) {
        synchronized(sync) {
            if (configLoaded && !force) return
            if (ApplicationLoader.applicationContext == null) return
            val preferences = getPreferences()
            for (item in configs) {
                try {
                    when (item.type) {
                        ConfigItem.configTypeBool ->
                            item.value = preferences.getBoolean(item.key, item.defaultValue as Boolean)

                        ConfigItem.configTypeInt ->
                            item.value = preferences.getInt(item.key, item.defaultValue as Int)

                        ConfigItem.configTypeLong ->
                            item.value = preferences.getLong(item.key, item.defaultValue as Long)

                        ConfigItem.configTypeFloat ->
                            item.value = preferences.getFloat(item.key, item.defaultValue as Float)

                        ConfigItem.configTypeString ->
                            item.value = preferences.getString(item.key, item.defaultValue as String?)
                    }
                } catch (e: Exception) {
                    FileLog.e(e)
                }
            }
            configLoaded = true
        }
    }

    /** Сбрасывает настройки экрана Appearance к значениям по умолчанию. */
    @JvmStatic
    fun reset() {
        synchronized(sync) {
            val editor = getPreferences().edit()
            for (item in configs) {
                editor.remove(item.key)
                item.value = item.defaultValue
            }
            editor.apply()
        }
        GlassOutlineStyle.dispatchChange()
    }
}
