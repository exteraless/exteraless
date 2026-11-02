package com.exteragram.messenger.plugins.ui.components;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BottomSheet;

import java.io.File;

public abstract class InstallPluginBottomSheet extends BottomSheet {

    protected View button;
    protected LinearLayout bottomContainer;
    protected boolean isUpdate;
    protected PluginInstallParams params;

    protected InstallPluginBottomSheet(Context context, boolean needFocus) {
        super(context, needFocus);
    }

    public void applyButtonState(boolean animated) {
    }

    public void onInstallClick() {
        performInstall();
    }

    protected abstract void performInstall();

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
