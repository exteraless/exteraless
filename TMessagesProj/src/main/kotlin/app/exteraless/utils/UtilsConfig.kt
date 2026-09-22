package app.exteraless.utils

import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.config.ConfigItem
import xyz.nextalone.nagram.NaConfig

/**
 * Настройки перенесённых из exteraGram анимаций и жестов.
 *
 * Схема та же, что у [app.exteraless.OpenExteraConfig]. Ключи с префиксом OE.
 * Загрузка ленивая ([ensureLoaded]) — эти значения читаются из ActionBarLayout,
 * то есть раньше, чем экраны настроек успевают позвать init().
 */
object UtilsConfig {

    /** ActionBarLayout.BACK_ANIMATION_SPRING */
    private const val BACK_ANIMATION_SPRING = 1

    private val sync = Any()
    private val configs = ArrayList<ConfigItem>()

    @JvmStatic
    fun getConfigTypes(): Map<String, Int> = configs.associate { it.key to it.type }

    @Volatile
    private var configLoaded = false

    @JvmStatic
    fun getPreferences(): SharedPreferences = NekoConfig.getPreferences()

    /**
     * Чувствительность жеста «назад»: множитель прогресса. Хранится целым в процентах
     * (100 = 1.0), потому что ConfigItem не умеет float. Диапазон exteraGram — 0..2,
     * при нуле жест вообще не двигает экран (ExteraConfig.predictiveBackIntensity,
     * FloatPref(1.0f)).
     */
    @JvmField
    val predictiveBackIntensity =
        addConfig("OEPredictiveBackIntensity", ConfigItem.configTypeInt, 100)

    @JvmStatic
    fun predictiveBackIntensity(): Float {
        ensureLoaded()
        return (predictiveBackIntensity.Int() / 100f).coerceIn(0f, 2f)
    }

    /** Прогресс жеста с учётом чувствительности. */
    @JvmStatic
    fun adjustPredictiveBackProgress(progress: Float): Float =
        (progress * predictiveBackIntensity()).coerceIn(0f, 1f)

    private fun addConfig(key: String, type: Int, defaultValue: Any?): ConfigItem {
        val item = ConfigItem(key, type, defaultValue)
        configs.add(item)
        return item
    }

    @JvmStatic
    fun init() {
        loadConfig(false)
        applyMotionDefaults()
    }

    /**
     * В exteraGram пружинные переходы включены по умолчанию (springAnimations = true).
     * У NagramX же BackAnimationStyle по умолчанию 0 (Classic), а весь пружинный путь
     * в ActionBarLayout — включая перенесённый масштаб карточки — живёт под
     * USE_SPRING_ANIMATION == SPRING. Без этого перенос анимаций не виден вообще.
     *
     * Трогаем только если пользователь стиль явно не выбирал: его выбор важнее нашего.
     * Читается один раз при старте, до загрузки класса ActionBarLayout.
     */
    @JvmStatic
    fun applyMotionDefaults() {
        try {
            val prefs = getPreferences()
            val key = NaConfig.backAnimationStyle.key
            if (!prefs.contains(key)) {
                NaConfig.backAnimationStyle.setConfigInt(BACK_ANIMATION_SPRING)
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun ensureLoaded() {
        if (!configLoaded) loadConfig(false)
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

    /** Сбрасывает настройки к значениям по умолчанию. */
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
