import app.exteraless.accounts.AccountLimits;

/** Executes the real settings dialog against the real UserConfig limit methods. */
public class SettingsAccountLimitTest extends UserConfigLimitsTest {
    static class R {
        static class string {
            static final int OEGeneralMaxAccountCount = 1, OEGeneralMaxAccountCountInfo = 2, Done = 3, Cancel = 4, OEGeneralMaxAccountCountValue = 5;
        }
    }

    static class NumberPicker {
        int min, max, value;
        boolean wrap = true;
        NumberPicker(Context context, Object resources) {}
        void setMinValue(int value) { min = value; }
        void setMaxValue(int value) { max = value; }
        void setValue(int value) { this.value = value; }
        int getValue() { return value; }
        void setWrapSelectorWheel(boolean value) { wrap = value; }
        void setFocusable(boolean value) {}
        void setFocusableInTouchMode(boolean value) {}
        void setContentDescriptionCallback(DescriptionCallback callback) {}
        void finishScroll() {}
    }

    interface DescriptionCallback {
        CharSequence apply(int value);
    }

    static class LocaleController {
        static String formatString(int res, int value) { return res + ":" + value; }
    }

    interface ClickListener { void onClick(AlertDialog dialog, int which); }
    static class AlertDialog {
        NumberPicker picker;
        ClickListener positive, negative;
        static class Builder {
            final AlertDialog dialog = new AlertDialog();
            Builder(Context context, Object resources) {}
            void setTitle(String title) {}
            void setMessage(String message) {}
            void setView(NumberPicker picker) { dialog.picker = picker; }
            void setPositiveButton(String text, ClickListener listener) { dialog.positive = listener; }
            void setNegativeButton(String text, ClickListener listener) { dialog.negative = listener; }
            AlertDialog create() { return dialog; }
        }
    }

    static class Adapter {
        int refreshes;
        void notifyItemChanged(int row) { equal(42, row, "wrong settings row refreshed"); refreshes++; }
    }
    static class NotificationCenter {
        static final int mainUserInfoChanged = 1;
        int notifications;
        void postNotificationName(int event) { equal(mainUserInfoChanged, event, "wrong refresh event"); notifications++; }
    }

    static class Screen {
        final Object resourcesProvider = new Object();
        final int maxAccountCountRow = 42;
        Adapter listAdapter = new Adapter();
        final NotificationCenter notifications = new NotificationCenter();
        AlertDialog shown;
        Context getParentActivity() { return ApplicationLoader.applicationContext; }
        String getString(int id) { return Integer.toString(id); }
        NotificationCenter getNotificationCenter() { return notifications; }
        void showDialog(AlertDialog dialog) { shown = dialog; }
        // SETTINGS_DIALOG
    }

    public static void main(String[] args) {
        Screen detached = new Screen();
        detached.showMaxAccountCountDialog();
        check(detached.shown == null, "Detached settings screen opened a dialog");
        Context context = new Context();
        ApplicationLoader.applicationContext = context;
        Screen screen = new Screen();
        screen.showMaxAccountCountDialog();
        equal(3, screen.shown.picker.min, "picker minimum");
        equal(100, screen.shown.picker.max, "picker maximum");
        equal(10, screen.shown.picker.value, "picker uses configured default");
        check(!screen.shown.picker.wrap, "boundary should not wrap from 100 to 3");
        screen.shown.picker.setValue(100);
        if (screen.shown.negative != null) screen.shown.negative.onClick(screen.shown, 0);
        equal(10, UserConfig.getMaxAccountCount(), "cancelling must preserve the setting");
        screen.shown.positive.onClick(screen.shown, 0);
        equal(100, UserConfig.getMaxAccountCount(), "upper limit selection persisted");
        equal(1, screen.listAdapter.refreshes, "settings value refreshed after apply");
        equal(1, screen.notifications.notifications, "account picker notified after apply");
        int writes = context.preferences.writes;
        screen.showMaxAccountCountDialog();
        equal(100, screen.shown.picker.value, "reopening shows persisted value");
        screen.shown.positive.onClick(screen.shown, 0);
        equal(writes, context.preferences.writes, "unchanged selection should not write preferences");
        equal(1, screen.notifications.notifications, "unchanged selection should not refresh account picker");
        UserConfig.accounts[99].identity = 9999L;
        UserConfig.selectedAccount = 99;
        screen.listAdapter = null;
        screen.shown.picker.setValue(3);
        screen.shown.positive.onClick(screen.shown, 0);
        equal(3, UserConfig.getMaxAccountCount(), "lower limit selection persisted without an attached list");
        check(UserConfig.isValidAccount(99), "settings reduction logged out a high-index session");
        equal(99, UserConfig.selectedAccount, "settings reduction switched the selected account");
        System.out.println("SettingsAccountLimit: range, apply/cancel, persistence and refresh passed");
    }
}
