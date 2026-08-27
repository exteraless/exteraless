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

    public void showInstallDialog(org.telegram.ui.ActionBar.BaseFragment fragment,
                                  String filePath, boolean trusted) {
        if (android.text.TextUtils.isEmpty(filePath)) {
            return;
        }
        android.app.Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null) {
            activity = org.telegram.messenger.AndroidUtilities.findActivity(
                    org.telegram.messenger.ApplicationLoader.applicationContext);
        }
        if (activity == null) {
            return;
        }
        final android.app.Activity target = activity;
        final java.io.File file = new java.io.File(filePath);
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                app.exteraless.plugins.PluginInstallHelper.confirmAndInstall(target, file));
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

    public abstract java.util.Map<String, ? extends Plugin> getPlugins();
}
