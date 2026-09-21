package app.exteraless.shortcuts;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import app.exteraless.plugins.PluginsController;
import tw.nekomimi.nekogram.NekoConfig;

public class QuickToggleActivity extends Activity {

    public static final String ACTION_GHOST_MODE = "app.exteraless.action.TOGGLE_GHOST_MODE";
    public static final String ACTION_SAFE_MODE = "app.exteraless.action.TOGGLE_SAFE_MODE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String action = getIntent() == null ? null : getIntent().getAction();
        CharSequence message = null;
        try {
            ApplicationLoader.postInitApplication();
            if (ACTION_GHOST_MODE.equals(action)) {
                message = toggleGhostMode();
            } else if (ACTION_SAFE_MODE.equals(action)) {
                message = toggleSafeMode();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (message != null) {
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private CharSequence toggleGhostMode() {
        try {
            NekoConfig.toggleGhostMode();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return LocaleController.getString(NekoConfig.isGhostModeActive()
                ? R.string.GhostModeEnabled
                : R.string.GhostModeDisabled);
    }

    private CharSequence toggleSafeMode() {
        final PluginsController controller = PluginsController.getInstance();
        final boolean enabled = !controller.isSafeMode();
        controller.setSafeMode(enabled);
        return LocaleController.getString(enabled
                ? R.string.PluginsSafeModeEnabled
                : R.string.PluginsSafeModeDisabled);
    }
}
