package app.exteraless.appearance;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

public final class LocalPremiumFolderIcons {
    private static final String PREFIX = "folder_icon_override_";
    private static final String HINT_SHOWN = "folder_icon_device_hint_shown";

    private LocalPremiumFolderIcons() {}

    public static boolean enabledFor(int account) {
        return AppearanceConfig.localPremiumFolderIcons() && !UserConfig.getInstance(account).isPremium();
    }

    public static long get(int account, int filterId) {
        return enabledFor(account) ? getStored(account, filterId) : 0;
    }

    public static long getStored(int account, int filterId) {
        if (filterId <= 0) {
            return 0;
        }
        return UserConfig.getInstance(account).getPreferences().getLong(PREFIX + filterId, 0);
    }

    public static void set(int account, int filterId, long documentId) {
        if (filterId <= 0 || (documentId != 0 && !enabledFor(account))) {
            return;
        }
        // UserConfig preferences are scoped to the account and cleared at logout.
        SharedPreferences prefs = UserConfig.getInstance(account).getPreferences();
        String key = PREFIX + filterId;
        if (prefs.getLong(key, 0) == documentId) {
            return;
        }
        if (documentId == 0) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putLong(key, documentId).apply();
        }
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account)
                .postNotificationName(NotificationCenter.localFolderIconsChanged));
    }

    public static boolean markHintShown(int account) {
        SharedPreferences prefs = UserConfig.getInstance(account).getPreferences();
        if (prefs.getBoolean(HINT_SHOWN, false)) {
            return false;
        }
        prefs.edit().putBoolean(HINT_SHOWN, true).apply();
        return true;
    }
}
