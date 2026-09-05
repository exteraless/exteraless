package app.exteraless.pillstack

import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.config.ConfigItem

/**
 * Настройки подсистемы Pill Stack, перенесённой из exteraGram.
 *
 * Схема повторяет [app.exteraless.OpenExteraConfig]: те же SharedPreferences, тот же [ConfigItem],
 * но отдельный список и собственный префикс ключей (OEPill), чтобы не пересекаться ни с
 * апстримом NagramX, ни с остальными настройками порта.
 */
object PillStackConfig {

    private val sync = Any()
    private val configs = ArrayList<ConfigItem>()

    @Volatile
    private var configLoaded = false

    /** По умолчанию показываем пилюли, которым не нужна сеть: кэш и прокси. */
    private const val DEFAULT_ACTIVE = "5,6"

    const val CURRENCY_AUTO = "AUTO"

    @JvmStatic
    fun getPreferences(): SharedPreferences = NekoConfig.getPreferences()

    // ---- Раскладка ----

    /** Сериализованный список id активных пилюль, через запятую. */
    @JvmField
    val activePillsRaw = addConfig("OEPillActivePills", ConfigItem.configTypeString, DEFAULT_ACTIVE)

    /** Сериализованный список id скрытых пилюль, через запятую. */
    @JvmField
    val hiddenPillsRaw = addConfig("OEPillHiddenPills", ConfigItem.configTypeString, "")

    /** Зацикливать ли пролистывание пилюль. */
    @JvmField
    val infiniteScrolling = addConfig("OEPillInfiniteScrolling", ConfigItem.configTypeBool, true)

    /** Какая пилюля была показана последней — чтобы восстановить её при следующем открытии. */
    @JvmField
    val lastActivePillId = addConfig("OEPillLastActiveId", ConfigItem.configTypeInt, -1)

    // ---- Курсы ----

    @JvmField
    val gramTargetCurrency = addConfig("OEPillGramCurrency", ConfigItem.configTypeString, CURRENCY_AUTO)

    @JvmField
    val btcTargetCurrency = addConfig("OEPillBtcCurrency", ConfigItem.configTypeString, CURRENCY_AUTO)

    @JvmField
    val usdTargetCurrency = addConfig("OEPillUsdCurrency", ConfigItem.configTypeString, CURRENCY_AUTO)

    @JvmField
    val ethTargetCurrency = addConfig("OEPillEthCurrency", ConfigItem.configTypeString, CURRENCY_AUTO)

    @JvmField
    val eurTargetCurrency = addConfig("OEPillEurCurrency", ConfigItem.configTypeString, CURRENCY_AUTO)

    @JvmField
    val goldTargetCurrency = addConfig("OEPillGoldCurrency", ConfigItem.configTypeString, CURRENCY_AUTO)

    /** Кэш цены золота (USD за унцию), чтобы переживать перезапуск приложения. */
    @JvmField
    val goldPriceCache = addConfig("OEPillGoldPriceCache", ConfigItem.configTypeString, "")

    @JvmField
    val goldPriceCacheTime = addConfig("OEPillGoldPriceCacheTime", ConfigItem.configTypeLong, 0L)

    /** Кэш ответа Coinbase: «CODE=rate,CODE=rate», курсы к USD. */
    @JvmField
    val ratesCache = addConfig("OEPillRatesCache", ConfigItem.configTypeString, "")

    @JvmField
    val ratesCacheTime = addConfig("OEPillRatesCacheTime", ConfigItem.configTypeLong, 0L)

    // ---- Погода ----

    /** true — брать текущую геопозицию, false — использовать точку, выбранную на карте. */
    @JvmField
    val weatherUseCurrentLocation = addConfig("OEPillWeatherCurrentLocation", ConfigItem.configTypeBool, true)

    /** Широта выбранной точки (строкой, чтобы не терять точность на float). */
    @JvmField
    val weatherLatitude = addConfig("OEPillWeatherLat", ConfigItem.configTypeString, "")

    @JvmField
    val weatherLongitude = addConfig("OEPillWeatherLon", ConfigItem.configTypeString, "")

    /** Человекочитаемый адрес выбранной точки — показывается в настройках. */
    @JvmField
    val weatherAddress = addConfig("OEPillWeatherAddress", ConfigItem.configTypeString, "")

    // ---- Статические геттеры для Java ----

    /** Прочитаны ли настройки из SharedPreferences. */
    @JvmStatic
    fun isConfigLoaded(): Boolean = configLoaded

    @JvmStatic
    fun infiniteScrolling(): Boolean = infiniteScrolling.Bool()

    @JvmStatic
    fun lastActivePillId(): Int = lastActivePillId.Int()

    @JvmStatic
    fun saveLastActivePillId(id: Int) {
        if (lastActivePillId.Int() != id) lastActivePillId.setConfigInt(id)
    }

    @JvmStatic
    fun useCurrentLocation(): Boolean = weatherUseCurrentLocation.Bool()

    @JvmStatic
    fun setUseCurrentLocation(value: Boolean) {
        if (weatherUseCurrentLocation.Bool() != value) weatherUseCurrentLocation.setConfigBool(value)
    }

    /** Человекочитаемый адрес выбранной точки, «» если точка не выбрана. */
    @JvmStatic
    fun customWeatherAddress(): String = weatherAddress.String() ?: ""

    @JvmStatic
    fun customWeatherLatitude(): Double = weatherLatitude.String().toDoubleOrNull() ?: Double.NaN

    @JvmStatic
    fun customWeatherLongitude(): Double = weatherLongitude.String().toDoubleOrNull() ?: Double.NaN

    @JvmStatic
    fun hasCustomWeatherLocation(): Boolean =
        !customWeatherLatitude().isNaN() && !customWeatherLongitude().isNaN()

    @JvmStatic
    fun setCustomWeatherLocation(lat: Double, lon: Double, address: String?) {
        weatherLatitude.setConfigString(lat.toString())
        weatherLongitude.setConfigString(lon.toString())
        weatherAddress.setConfigString(address ?: "")
    }

    // ---- Раскладка: списки ----

    private val activePills = ArrayList<Int>()
    private val hiddenPills = ArrayList<Int>()

    /**
     * Живой список активных пилюль в порядке отображения.
     *
     * Возвращается сам список, а не копия: плагины каталога дописывают в него свой id
     * и следом зовут [savePillsLayout] — так устроен эталон
     * (`PillStackConfig.getActivePills()` в exteraGram 12.9.0 отдаёт поле).
     */
    @JvmStatic
    fun getActivePills(): ArrayList<Int> = activePills

    /** Живой список скрытых пилюль, см. [getActivePills]. */
    @JvmStatic
    fun getHiddenPills(): ArrayList<Int> = hiddenPills

    @JvmStatic
    fun hasActivePills(): Boolean = synchronized(sync) { activePills.isNotEmpty() }

    /** Сохраняет новую раскладку целиком (вызывается экраном настроек после drag&drop). */
    @JvmStatic
    fun saveLayout(active: List<Int>, hidden: List<Int>) {
        synchronized(sync) {
            activePills.clear()
            activePills.addAll(active)
            hiddenPills.clear()
            hiddenPills.addAll(hidden)
        }
        persistLayout()
    }

    /** Переносит пилюлю из скрытых в активные (или обратно). */
    @JvmStatic
    fun setPillActive(id: Int, active: Boolean) {
        synchronized(sync) {
            activePills.remove(id)
            hiddenPills.remove(id)
            if (active) activePills.add(id) else hiddenPills.add(id)
        }
        persistLayout()
    }

    /** Возвращает раскладку к значению по умолчанию. */
    @JvmStatic
    fun resetLayout() {
        synchronized(sync) {
            activePills.clear()
            activePills.addAll(parseList(DEFAULT_ACTIVE))
            hiddenPills.clear()
        }
        sanitizePills()
        persistLayout()
    }

    /** Сохраняет текущую раскладку и пересобирает полосу. Точка входа для плагинов. */
    @JvmStatic
    fun savePillsLayout() {
        persistLayout()
        PillStackEvents.notifyLayoutChanged()
    }

    /** Помечает пилюли изменившимися; пустой список — все. Точка входа для плагинов. */
    @JvmStatic
    fun notifySettingsChanged(vararg pillIds: Int) {
        PillStackEvents.notifySettingsChanged(*pillIds)
    }

    private fun persistLayout() {
        synchronized(sync) {
            activePillsRaw.setConfigString(serializeList(activePills))
            hiddenPillsRaw.setConfigString(serializeList(hiddenPills))
        }
    }

    /**
     * Выкидывает из раскладки id, которых нет в реестре, и добавляет в скрытые всё,
     * что зарегистрировалось после прошлого запуска.
     */
    @JvmStatic
    fun sanitizePills() {
        val known = PillRegistry.getRegisteredIds().map { it.toInt() }
        synchronized(sync) {
            activePills.retainAll { known.contains(it) }
            hiddenPills.retainAll { known.contains(it) && !activePills.contains(it) }
            for (id in known) {
                if (!activePills.contains(id) && !hiddenPills.contains(id)) {
                    hiddenPills.add(id)
                }
            }
        }
    }

    private fun parseList(data: String?): List<Int> {
        if (data.isNullOrBlank()) return emptyList()
        return data.split(",").mapNotNull { it.trim().toIntOrNull() }.distinct()
    }

    private fun serializeList(list: List<Int>): String = list.joinToString(",")

    private fun addConfig(key: String, type: Int, defaultValue: Any?): ConfigItem {
        val item = ConfigItem(key, type, defaultValue)
        configs.add(item)
        return item
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
            activePills.clear()
            activePills.addAll(parseList(activePillsRaw.String()))
            hiddenPills.clear()
            hiddenPills.addAll(parseList(hiddenPillsRaw.String()).filter { !activePills.contains(it) })
            configLoaded = true
        }
        sanitizePills()
    }

    /** Сбрасывает все настройки Pill Stack к значениям по умолчанию. */
    @JvmStatic
    fun reset() {
        synchronized(sync) {
            val editor = getPreferences().edit()
            for (item in configs) {
                editor.remove(item.key)
                item.value = item.defaultValue
            }
            editor.apply()
            configLoaded = false
        }
        loadConfig(true)
    }
}
