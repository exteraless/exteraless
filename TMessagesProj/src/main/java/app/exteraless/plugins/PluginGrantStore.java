package app.exteraless.plugins;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.util.Map;

final class PluginGrantStore {

    static final String PREFS_NAME = "exteraless_plugin_grants";
    private static final String KEY_MIGRATED = "migrated";

    private static volatile SharedPreferences preferences;

    private PluginGrantStore() {
    }

    static SharedPreferences get() {
        SharedPreferences store = preferences;
        if (store != null) {
            return store;
        }
        synchronized (PluginGrantStore.class) {
            if (preferences != null) {
                return preferences;
            }
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return null;
            }
            store = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (!store.getBoolean(KEY_MIGRATED, false)) {
                SharedPreferences legacy = context.getSharedPreferences(PluginsConstants.PREFS_NAME, Context.MODE_PRIVATE);
                if (!migrate(legacy, store)) {
                    // Do not substitute an unsigned authority on storage failure.
                    return null;
                }
            }
            store = SignedGrantPreferences.wrap(store, PluginGrantStore::isGrantKey);
            preferences = store;
            return store;
        }
    }

    static boolean isStore(Object target) {
        if (target instanceof String) {
            return PREFS_NAME.equals(target);
        }
        return target instanceof File && (PREFS_NAME + ".xml").equals(((File) target).getName());
    }

    static void warmUp() {
        SharedPreferences store = get();
        if (store != null) {
            SignedGrantPreferences.warmUp(store);
        }
    }

    static boolean isOwnCall() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (PluginGrantStore.class.getName().equals(element.getClassName())) {
                return true;
            }
        }
        return false;
    }

    static boolean isGrantKey(String key) {
        return key.startsWith(PluginsConstants.KEY_PLUGIN_PERMS_PREFIX)
                || key.startsWith(PluginsConstants.KEY_PLUGIN_LEVEL_PREFIX)
                || PluginsConstants.KEY_UNSAFE_MODE.equals(key);
    }

    private static boolean migrate(SharedPreferences from, SharedPreferences to) {
        SharedPreferences.Editor moved = to.edit();
        SharedPreferences.Editor cleared = from.edit();
        int count = 0;
        for (Map.Entry<String, ?> entry : from.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || !isGrantKey(key)) {
                continue;
            }
            if (value instanceof String) {
                moved.putString(key, (String) value);
            } else if (value instanceof Integer) {
                moved.putInt(key, (Integer) value);
            } else if (value instanceof Boolean) {
                moved.putBoolean(key, (Boolean) value);
            }
            cleared.remove(key);
            count++;
        }
        if (!moved.putBoolean(KEY_MIGRATED, true).commit()) {
            FileLog.e("PluginGrantStore: migration failed, keeping grants in place");
            return false;
        }
        cleared.commit();
        FileLog.d("PluginGrantStore: moved " + count + " grant records");
        return true;
    }
}
