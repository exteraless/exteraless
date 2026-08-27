package com.exteragram.messenger.plugins;

import org.telegram.ui.ActionBar.BaseFragment;

/**
 * Основание движка плагинов под именем exteraGram.
 *
 * {@link #openPluginSettings} объявлен здесь намеренно: dex-модули вешают на
 * него Xposed-хук, чтобы подменить свой экран настроек. Хук ловит вызовы,
 * только если приложение действительно идёт через этот метод, поэтому наш
 * движок его не переопределяет, а UI зовёт именно его.
 */
public abstract class PythonPluginsEngine {

    public void showInstallDialog(BaseFragment fragment,
                                  com.exteragram.messenger.plugins.ui.components
                                          .InstallPluginBottomSheet.PluginInstallParams params) {
        if (params != null) {
            PluginsController.getInstance()
                    .showInstallDialog(fragment, params.getFilePath(), params.getTrusted());
        }
    }

    public void openPluginSettings(Plugin plugin, BaseFragment fragment) {
        openPluginSettings(plugin, fragment, null);
    }

    public void openPluginSettings(Plugin plugin, BaseFragment fragment, String targetSetting) {
        if (plugin == null || fragment == null) {
            return;
        }
        fragment.presentFragment(app.exteraless.plugins.ui.PluginSettingsActivity
                .newInstance(plugin.getId(), targetSetting));
    }
}
