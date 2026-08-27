package app.exteraless.config;

import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

public abstract class LegacyDefaults {

    private static final String MARKER = "OELegacyDefaultsPinned";

    private static final String[] BOOL_KEYS = {
            "showAddToSavedMessages",
            "showViewHistory",
            "showAdminActions",
            "showChangePermissions",
            "showMessageDetails",
            "showTranslate",
            "showRepeat",
    };

    private static final String[] INT_KEYS = {
            "DoubleTapAction",
            "DoubleTapActionOut",
    };

    private static final int[] INT_VALUES = {3, 8};

    public static void pin(SharedPreferences preferences) {
        if (preferences == null || preferences.getBoolean(MARKER, false)) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(MARKER, true);
        if (installedBeforeThisBuild()) {
            for (String key : BOOL_KEYS) {
                if (!preferences.contains(key)) {
                    editor.putBoolean(key, true);
                }
            }
            for (int a = 0; a < INT_KEYS.length; a++) {
                if (!preferences.contains(INT_KEYS[a])) {
                    editor.putInt(INT_KEYS[a], INT_VALUES[a]);
                }
            }
        }
        editor.apply();
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
