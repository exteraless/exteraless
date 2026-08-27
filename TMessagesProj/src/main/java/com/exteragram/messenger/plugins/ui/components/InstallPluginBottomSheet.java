package com.exteragram.messenger.plugins.ui.components;

import android.text.TextUtils;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;

import java.io.File;

public final class InstallPluginBottomSheet {

    private InstallPluginBottomSheet() {
    }

    public static final class PluginInstallParams {

        private final String filePath;
        private boolean trusted;

        public PluginInstallParams(String filePath, boolean trusted) {
            this.filePath = filePath;
            this.trusted = trusted;
        }

        public static PluginInstallParams of(MessageObject messageObject) {
            if (messageObject == null || messageObject.messageOwner == null) {
                return null;
            }
            final File file = FileLoader.getInstance(UserConfig.selectedAccount)
                    .getPathToMessage(messageObject.messageOwner);
            return file == null ? null : new PluginInstallParams(file.getAbsolutePath(), false);
        }

        public String getFilePath() {
            return filePath;
        }

        public boolean getTrusted() {
            return trusted;
        }

        public void setTrusted(boolean value) {
            trusted = value;
        }

        public File toFile() {
            return TextUtils.isEmpty(filePath) ? null : new File(filePath);
        }
    }
}
