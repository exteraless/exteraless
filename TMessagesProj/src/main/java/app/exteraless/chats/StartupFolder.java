package app.exteraless.chats;

import android.content.SharedPreferences;

import org.telegram.messenger.MessagesController;

import java.util.List;

public final class StartupFolder {

    public static final int MODE_FIRST = -1;
    public static final int MODE_LAST = -2;

    private static final String KEY_MODE = "oe_startup_folder";
    private static final String KEY_LAST = "oe_startup_last_folder";

    private StartupFolder() {
    }

    private static SharedPreferences preferences(int account) {
        return MessagesController.getMainSettings(account);
    }

    public static int getMode(int account) {
        return preferences(account).getInt(KEY_MODE, MODE_FIRST);
    }

    public static void setMode(int account, int mode) {
        preferences(account).edit().putInt(KEY_MODE, mode).apply();
    }

    public static void rememberLast(int account, int filterId) {
        SharedPreferences preferences = preferences(account);
        if (preferences.getInt(KEY_MODE, MODE_FIRST) == MODE_LAST && preferences.getInt(KEY_LAST, MODE_FIRST) != filterId) {
            preferences.edit().putInt(KEY_LAST, filterId).apply();
        }
    }

    public static int targetIndex(int account, List<MessagesController.DialogFilter> filters) {
        int mode = getMode(account);
        int filterId = mode == MODE_LAST ? preferences(account).getInt(KEY_LAST, MODE_FIRST) : mode;
        if (filterId < 0) {
            return -1;
        }
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i).id == filterId) {
                return i;
            }
        }
        return -1;
    }
}
