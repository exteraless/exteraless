package com.exteragram.messenger.plugins;

/**
 * Основание контроллера плагинов под именем exteraGram.
 *
 * dex-модули берут именно этот класс (`PluginsController.class`) и перебирают его
 * `getDeclaredMethods()`, поэтому всё, что они зовут, обязано быть объявлено здесь,
 * а не только у наследника: унаследованные и объявленные ниже по иерархии методы
 * такой перебор не видит.
 */
public abstract class PluginsController {

    public static PluginsController getInstance() {
        return app.exteraless.plugins.PluginsController.getInstance();
    }

    public static java.util.Map<String, ? extends PythonPluginsEngine> getEngines() {
        return app.exteraless.plugins.PluginsController.getEngines();
    }

    public static void openPluginSettings(String pluginId) {
        app.exteraless.plugins.PluginsController.openPluginSettings(pluginId);
    }

    public static void openPluginSettings(String pluginId, String targetSetting) {
        app.exteraless.plugins.PluginsController.openPluginSettings(pluginId, targetSetting);
    }

    public static boolean isPlugin(org.telegram.messenger.MessageObject message) {
        return app.exteraless.plugins.PluginsController.isPlugin(message);
    }

    public static boolean isPlugin(java.io.File file, org.telegram.messenger.MessageObject message) {
        return app.exteraless.plugins.PluginsController.isPlugin(file, message);
    }

    public void showInstallDialog(org.telegram.ui.ActionBar.BaseFragment fragment,
                                  String filePath, boolean trusted) {
        if (android.text.TextUtils.isEmpty(filePath)) {
            return;
        }
        app.exteraless.plugins.PythonPluginsEngine.getInstance().showInstallDialog(fragment,
                new com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet
                        .PluginInstallParams(filePath, trusted));
    }

    public void showInstallDialog(org.telegram.ui.ActionBar.BaseFragment fragment,
                                  org.telegram.messenger.MessageObject messageObject) {
        final com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet
                .PluginInstallParams params = com.exteragram.messenger.plugins.ui.components
                .InstallPluginBottomSheet.PluginInstallParams.of(messageObject);
        if (params != null) {
            showInstallDialog(fragment, params.getFilePath(), false);
        }
    }

    public abstract void loadPluginSettings(String pluginId);

    public abstract void init();

    public abstract void init(Runnable onDone);

    public abstract void init(boolean startWithSafeMode);

    public abstract void init(boolean startWithSafeMode, Runnable onDone);

    public abstract boolean getInitialized();

    public abstract void restart();

    public abstract void restart(boolean startWithSafeMode);

    public abstract void shutdown();

    public abstract void shutdown(Runnable onDone);

    public abstract void runOnPluginsQueue(Runnable runnable);

    public abstract String getPluginPath(String id);

    public abstract PythonPluginsEngine getPluginEngine(String pluginId);

    public static PythonPluginsEngine getPluginEngine(java.io.File file) {
        return app.exteraless.plugins.PluginsController.engineForFile(file);
    }

    public abstract boolean isPluginEngineAvailable();

    public abstract boolean isPluginEngineSupported();

    public abstract void notifyPluginsChanged();

    public abstract void deletePlugin(String pluginId,
                                      org.telegram.messenger.Utilities.Callback<String> callback);

    public abstract void loadPluginSettings();

    public abstract void invalidatePluginSettings(String pluginId);

    public abstract boolean hasPluginSettings(String pluginId);

    public abstract boolean hasPluginSettingsPreferences(String pluginId);

    public abstract java.util.Map<String, ?> getPluginSettingsPreferences(String pluginId);

    public abstract void clearPluginSettingsPreferences(String pluginId);

    public abstract void clearPluginSettingsPreferences(String pluginId, boolean reloadSettings);

    public abstract java.util.List<Object> getPluginSettingsList(String pluginId);

    public abstract java.util.Map<String, java.util.List<Object>> getSettings();

    public abstract boolean getPluginSettingBoolean(String pluginId, String key, boolean defaultValue);

    public abstract int getPluginSettingInt(String pluginId, String key, int defaultValue);

    public abstract String getPluginSettingString(String pluginId, String key, String defaultValue);

    public abstract void setPluginSetting(String pluginId, String key, Object value);

    public abstract void setPluginSettingAndTriggerOnChange(String pluginId, String key, Object value,
                                                            com.chaquo.python.PyObject onChangeCallback);

    public abstract void setPluginEnabled(String pluginId, boolean enabled,
                                          org.telegram.messenger.Utilities.Callback<String> callback);

    public abstract java.util.Map<String, ? extends Plugin> getPlugins();
}
