package app.exteraless.config;

import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

public abstract class LegacyDefaults {

    private static final String MARKER = "OELegacyDefaultsPinned";
    private static final String GENERATION = "OELegacyDefaultsGeneration";

    private static final int CURRENT_GENERATION = 2;

    private static final String[] BOOL_KEYS = {
            "showAddToSavedMessages",
            "showViewHistory",
            "showAdminActions",
            "showChangePermissions",
            "showMessageDetails",
            "showTranslate",
            "showRepeat",
            "UseChatAttachEnterMenu",
    };

    private static final int[] BOOL_GENERATIONS = {1, 1, 1, 1, 1, 1, 1, 2};

    private static final String[] INT_KEYS = {
            "DoubleTapAction",
            "DoubleTapActionOut",
    };

    private static final int[] INT_VALUES = {3, 8};

    private static final int[] INT_GENERATIONS = {1, 1};

    public static void pin(SharedPreferences preferences) {
        if (preferences == null) {
            return;
        }
        int pinned = preferences.getInt(GENERATION, preferences.getBoolean(MARKER, false) ? 1 : 0);
        if (pinned >= CURRENT_GENERATION) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(MARKER, true);
        editor.putInt(GENERATION, CURRENT_GENERATION);
        if (pinned > 0 || installedBeforeThisBuild()) {
            for (int a = 0; a < BOOL_KEYS.length; a++) {
                if (BOOL_GENERATIONS[a] > pinned && !preferences.contains(BOOL_KEYS[a])) {
                    editor.putBoolean(BOOL_KEYS[a], true);
                }
            }
            for (int a = 0; a < INT_KEYS.length; a++) {
                if (INT_GENERATIONS[a] > pinned && !preferences.contains(INT_KEYS[a])) {
                    editor.putInt(INT_KEYS[a], INT_VALUES[a]);
                }
            }
        }
        editor.apply();
    }

    public static void keepPinned(SharedPreferences.Editor editor) {
        editor.putBoolean(MARKER, true);
        editor.putInt(GENERATION, CURRENT_GENERATION);
    }

    private static boolean installedBeforeThisBuild() {
        try {
            PackageInfo info = ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            return info.lastUpdateTime > info.firstInstallTime;
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
    }
}
