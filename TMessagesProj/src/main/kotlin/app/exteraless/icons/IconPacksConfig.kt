package app.exteraless.icons

import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.config.ConfigItem

/**
 * Настройки подсистемы Icon Packs (порт из exteraGram).
 *
 * Схема повторяет [app.exteraless.OpenExteraConfig]: те же SharedPreferences, тот же [ConfigItem],
 * собственный список конфигов. Ключи с префиксом OEIcon.
 */
object IconPacksConfig {

    private val sync = Any()
    private val configs = ArrayList<ConfigItem>()

    @JvmStatic
    fun getConfigTypes(): Map<String, Int> = configs.associate { it.key to it.type }

    @Volatile
    private var configLoaded = false

    @JvmStatic
    fun getPreferences(): SharedPreferences = NekoConfig.getPreferences()

    /** Главный выключатель подсистемы: пока выключен — подмена иконок не выполняется вообще. */
    @JvmField
    val enabled = addConfig("OEIconPacksEnabled", ConfigItem.configTypeBool, false)

    /**
     * Упорядоченный список id включённых паков через запятую.
     * Первый пак в списке имеет наивысший приоритет при конфликте имён иконок.
     */
    @JvmField
    val activePacks = addConfig("OEIconPacksActive", ConfigItem.configTypeString, "")

    /**
     * Id пака, который сейчас редактируется точечной заменой иконок.
     * Пустая строка — режим редактирования выключен.
     */
    @JvmField
    val editingPackId = addConfig("OEIconPacksEditing", ConfigItem.configTypeString, "")

    /** Подменять ли иконку уведомлений картинкой из активного пака. */
    @JvmField
    val customNotificationIcon = addConfig("OEIconPacksNotificationIcon", ConfigItem.configTypeBool, false)

    /**
     * Обрезать логотип в шапке настроек по форме иконок лаунчера.
     *
     * Перенос ключа `useSystemIconShape` exteraGram (12.9.0). Переключается долгим
     * нажатием на самом логотипе — своей строки в настройках у него нет и там
     * (MainPreferencesActivity:211).
     */
    @JvmField
    val useSystemIconShape = addConfig("OEIconPacksSystemShape", ConfigItem.configTypeBool, false)

    @JvmStatic
    fun useSystemIconShape(): Boolean = useSystemIconShape.Bool()

    @JvmStatic
    fun toggleSystemIconShape(): Boolean {
        val value = !useSystemIconShape.Bool()
        useSystemIconShape.setConfigBool(value)
        return value
    }

    @JvmStatic
    fun enabled(): Boolean = enabled.Bool()

    /** Id редактируемого пака или null. */
    @JvmStatic
    fun currentEditingPackId(): String? {
        val value = editingPackId.String()
        return if (value.isNullOrBlank()) null else value
    }

    @JvmStatic
    fun setEditingPackId(packId: String?) {
        editingPackId.setConfigString(packId ?: "")
    }

    @JvmStatic
    fun isEditing(): Boolean = currentEditingPackId() != null

    @JvmStatic
    fun customNotificationIcon(): Boolean = customNotificationIcon.Bool()

    /** Разбирает [activePacks] в список id. */
    @JvmStatic
    fun getActivePackIds(): MutableList<String> {
        val raw = activePacks.String()
        if (raw.isBlank()) return ArrayList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
    }

    @JvmStatic
    fun setActivePackIds(ids: List<String>) {
        activePacks.setConfigString(ids.filter { it.isNotBlank() }.joinToString(","))
    }

    @JvmStatic
    fun isPackActive(packId: String): Boolean = getActivePackIds().contains(packId)

    /** Включает/выключает пак, возвращает новое состояние. */
    @JvmStatic
    fun togglePack(packId: String): Boolean {
        val ids = getActivePackIds()
        val nowActive: Boolean
        if (ids.remove(packId)) {
            nowActive = false
        } else {
            ids.add(0, packId)
            nowActive = true
        }
        setActivePackIds(ids)
        return nowActive
    }

    /** Убирает пак из активных (например, после удаления с диска). */
    @JvmStatic
    fun forgetPack(packId: String) {
        val ids = getActivePackIds()
        if (ids.remove(packId)) {
            setActivePackIds(ids)
        }
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
