package app.exteraless.glyph

import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.config.ConfigItem

/**
 * Настройки интеграции с Nothing Glyph (подсветка задней панели Nothing Phone).
 *
 * Схема повторяет [app.exteraless.OpenExteraConfig]: те же SharedPreferences,
 * тот же [ConfigItem]. Ключи с префиксом OEGlyph.
 */
object GlyphConfig {

    private val sync = Any()
    private val configs = ArrayList<ConfigItem>()

    @Volatile
    private var configLoaded = false

    @JvmStatic
    fun getPreferences(): SharedPreferences = NekoConfig.getPreferences()

    /** Мастер-переключатель: без него контроллер не биндится к системному сервису глифов. */
    @JvmField
    val enabled = addConfig("OEGlyphEnabled", ConfigItem.configTypeBool, false)

    /** Вспышка глифов на входящее сообщение. */
    @JvmField
    val onNewMessage = addConfig("OEGlyphNewMessage", ConfigItem.configTypeBool, true)

    /** «Дыхание» глифов, пока идёт запись голосового. */
    @JvmField
    val onRecording = addConfig("OEGlyphRecording", ConfigItem.configTypeBool, true)

    /** «Дыхание» глифов, пока звонит входящий VoIP-звонок. */
    @JvmField
    val onCall = addConfig("OEGlyphCalls", ConfigItem.configTypeBool, true)

    /** Реагировать на сообщения только при выключенном экране. */
    @JvmField
    val screenOffOnly = addConfig("OEGlyphScreenOff", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun enabled(): Boolean = enabled.Bool()

    @JvmStatic
    fun onNewMessage(): Boolean = onNewMessage.Bool()

    @JvmStatic
    fun onRecording(): Boolean = onRecording.Bool()

    @JvmStatic
    fun onCall(): Boolean = onCall.Bool()

    @JvmStatic
    fun screenOffOnly(): Boolean = screenOffOnly.Bool()

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
                    }
                } catch (e: Exception) {
                    FileLog.e(e)
                }
            }
            configLoaded = true
        }
    }
}
