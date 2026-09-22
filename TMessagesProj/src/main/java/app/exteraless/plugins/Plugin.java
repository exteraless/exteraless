package app.exteraless.plugins;

import java.util.ArrayList;
import java.util.List;

/**
 * Модель плагина: метаданные, прочитанные из .py-файла (AST-парсинг на Python-стороне,
 * сюда приезжают уже готовыми полями), состояние и путь к файлу.
 *
 * Аналог com.exteragram.messenger.plugins.Plugin.
 */
public class Plugin extends com.exteragram.messenger.plugins.Plugin {

    public Plugin() {
    }

    public Plugin(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String getId() {
        return id;
    }


    public String id;
    public String name;
    public String description;
    public String author;
    public String version = "1.0";
    /** StickerPackShortName/index, например "exteraPlugins/1". */
    public String icon;
    public String engine = PluginsConstants.PYTHON;
    public String appVersion;
    public String sdkVersion;
    public boolean beta;
    public List<String> requirements = new ArrayList<>();

    /**
     * Elyx {@code requires}: id требуемого плагина → {минимальная версия, ссылка}.
     * Обе части могут быть null: версия не обязательна, ссылку автор указывает
     * не всегда.
     */
    public java.util.Map<String, String[]> requires = new java.util.LinkedHashMap<>();

    /**
     * Объявленные в {@code __permissions__} разрешения (уже проверенные ключи,
     * см. {@link PluginPermissions}). Пустой список при {@code __permissions__ = []}.
     */
    public List<String> permissions = new ArrayList<>();
    /**
     * Было ли объявление {@code __permissions__} в файле вообще. Отличать «объявил пусто»
     * от «не объявлял»: плагин без объявления, установленный до появления модели,
     * работает в режиме совместимости (см. PluginPermissions.getEffective).
     */
    public boolean permissionsDeclared;

    /** Абсолютный путь к файлу плагина в filesDir/plugins. */
    public String path;

    /** Включён ли пользователем (персистентно). */
    public boolean enabled = true;
    /** Загружен ли сейчас в Python-рантайм. */
    public transient boolean loaded;
    /** Есть ли у плагина create_settings (показывать ли экран настроек). */
    public transient boolean hasSettings;
    /** Текст последней ошибки загрузки/валидации; null если всё хорошо. */
    public String loadError;
    /** Полный отчёт о той же ошибке: traceback и окружение, для кнопки «копировать». */
    public transient String loadDebug;
    /** Отметка сторожа: плагин завис в текущем вызове. */
    public transient boolean notResponding;
    public transient volatile boolean handlesAppEvent = true;
    public transient volatile boolean handlesPreRequest = true;
    public transient volatile boolean handlesPostRequest = true;
    public transient volatile boolean handlesUpdate = true;
    public transient volatile boolean handlesUpdates = true;
    public transient volatile boolean handlesSendMessage = true;

    /**
     * Геттеры в форме exteraGram. У эталона Plugin — Kotlin-класс с приватными
     * полями, и плагины каталога зовут именно методы; наши публичные поля
     * Chaquopy отдаёт как атрибуты, но {@code plugin.getEngine()} без этого
     * блока падает с AttributeError.
     */
    public String getEngine() {
        return engine;
    }

    public void setEngine(String value) {
        engine = value;
    }

    public void setName(String value) {
        name = value;
    }

    public void setDescription(String value) {
        description = value;
    }

    public void setAuthor(String value) {
        author = value;
    }

    public void setVersion(String value) {
        version = value;
    }

    public void setIcon(String value) {
        icon = value;
    }

    public void setAppVersion(String value) {
        appVersion = value;
    }

    public void setSdkVersion(String value) {
        sdkVersion = value;
    }

    public void setRequirements(List<String> value) {
        requirements = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getAuthor() {
        return author;
    }

    public String getVersion() {
        return version;
    }

    public String getIcon() {
        return icon;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public String getSdkVersion() {
        return sdkVersion;
    }

    public List<String> getRequirements() {
        return requirements;
    }

    public boolean isEnabled() {
        return enabled && loadError == null;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public String getError() {
        return loadError;
    }

    public void setError(String value) {
        loadError = value;
    }

    public boolean hasError() {
        return loadError != null;
    }

    public boolean isNotResponding() {
        return notResponding;
    }

    public void setNotResponding(boolean value) {
        notResponding = value;
    }

    /** Стикерпак иконки: часть {@link #icon} до слэша. */
    public String getPack() {
        if (icon == null) {
            return null;
        }
        int slash = icon.indexOf('/');
        return slash < 0 ? icon : icon.substring(0, slash);
    }

    /** Номер стикера в паке: часть {@link #icon} после слэша. */
    public int getIndex() {
        if (icon == null) {
            return 0;
        }
        int slash = icon.indexOf('/');
        if (slash < 0 || slash + 1 >= icon.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(icon.substring(slash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String getDisplayName() {
        return name != null ? name : id;
    }

    public String getSubtitle() {
        StringBuilder sb = new StringBuilder();
        sb.append("v").append(version != null ? version : "1.0");
        if (author != null && !author.isEmpty()) {
            sb.append(" • ").append(author);
        }
        if (loadError != null) {
            sb.append(" • error");
        }
        return sb.toString();
    }
}
