package app.exteraless.general

import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.config.ConfigItem

/**
 * Настройки экранов «General» и «Other» раздела openExtera.
 *
 * Схема повторяет [app.exteraless.OpenExteraConfig]: те же SharedPreferences, тот же [ConfigItem].
 * Здесь живут ТОЛЬКО те настройки, которых нет ни в NekoConfig, ни в NaConfig, ни в
 * OpenExteraConfig — всё остальное экраны берут из существующих ConfigItem, чтобы не плодить дубли.
 * Ключи с префиксом OEGeneral.
 */
object GeneralConfig {

    private val sync = Any()
    private val configs = ArrayList<ConfigItem>()

    @Volatile
    private var configLoaded = false

    @JvmStatic
    fun getPreferences(): SharedPreferences = NekoConfig.getPreferences()

    /**
     * «Download Speed Boost» — трёхпозиционный выбор (0: обычный, 1: быстрый,
     * 2: максимальный). Применяется в FileLoadOperation.updateParams: уровень 2
     * берёт куски по мегабайту и двенадцать параллельных запросов, уровень 1
     * совпадает со штатным enhancedFileLoader.
     */
    @JvmField
    val lastfmNick = addConfig("OEGeneralLastFmNick", ConfigItem.configTypeString, "")

    @JvmField
    val lastfmExplained = addConfig("OEGeneralLastFmExplained", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun lastfmNick(): String = lastfmNick.String() ?: ""

    @JvmField
    val downloadSpeedBoost = addConfig("OEGeneralDownloadSpeedBoost", ConfigItem.configTypeInt, 0)

    @JvmStatic
    fun downloadSpeedBoost(): Int = downloadSpeedBoost.Int()

    /**
     * Показывать ли вход в настройки NagramX («N-Settings») в общем списке настроек.
     *
     * По умолчанию выключено: форк ведёт свои экраны, а параллельный набор от
     * апстрима сбивает — те же настройки лежат в двух местах и расходятся.
     * Кому нужны редкие вещи, которых у нас нет (переводчик, экспериментальное),
     * включают строку здесь.
     */
    @JvmField
    val showNagramSettings = addConfig("OEGeneralShowNagramSettings", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun showNagramSettings(): Boolean {
        loadConfig(false)
        return showNagramSettings.Bool()
    }

    /**
     * Быстрый доступ к AyuMoments. Секция живёт внутри «Экспериментального» у NagramX,
     * а с этим флагом её выносит отдельной строкой туда же, где появляется вход
     * в настройки NagramX.
     */
    @JvmField
    val showAyuMoments = addConfig("OEGeneralShowAyuMoments", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun showAyuMoments(): Boolean {
        loadConfig(false)
        return showAyuMoments.Bool()
    }

    @JvmField
    val crashReports = addConfig("OEGeneralCrashReports", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun crashReports(): Boolean {
        loadConfig(false)
        return crashReports.Bool()
    }

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
            configLoaded = true
        }
    }

    /** Сбрасывает настройки этих экранов к значениям по умолчанию. */
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
    }
}
