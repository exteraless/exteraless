import app.exteraless.accounts.AccountLimits;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Production UserConfig entry points with an Android-style preference fake. */
public class UserConfigLimitsTest {
    static class SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        int writes;
        int getInt(String key, int fallback) {
            Object value = values.get(key);
            if (value == null) return fallback;
            if (!(value instanceof Integer)) throw new ClassCastException(key);
            return (Integer) value;
        }
        Map<String, ?> getAll() { return new HashMap<>(values); }
        Editor edit() { return new Editor(); }
        class Editor {
            final Map<String, Object> pending = new HashMap<>();
            Editor putInt(String key, int value) { pending.put(key, value); return this; }
            void apply() { values.putAll(pending); writes++; }
        }
    }

    static class Context {
        static final int MODE_PRIVATE = 0;
        final SharedPreferences preferences = new SharedPreferences();
        SharedPreferences getSharedPreferences(String name, int mode) {
            check("nkmrcfg".equals(name), "Preference file changed; existing settings would be lost");
            check(mode == MODE_PRIVATE, "Unexpected preference access mode");
            return preferences;
        }
    }

    static class ApplicationLoader { static Context applicationContext; }

    static class UserConfig {
        // USER_CONFIG_CONSTANTS
        // accountLimitSync
        // pendingMaxAccountCount
        static int selectedAccount;
        static boolean premium;
        static int premiumReads;
        static final UserConfig[] accounts = new UserConfig[100];
        long identity;
        static {
            for (int i = 0; i < accounts.length; i++) accounts[i] = new UserConfig();
        }
        static UserConfig getInstance(int account) { return accounts[account]; }
        boolean isClientActivated() { return identity != 0; }
        long getClientUserId() { return identity; }
        static boolean hasPremiumOnAccounts() { premiumReads++; return premium; }
        void clearConfig() { throw new AssertionError("Changing a limit cleared a session"); }
        // USER_CONFIG_METHODS
    }

    public static void main(String[] args) {
        switch (args[0]) {
            case "preferences": preferences(); break;
            case "early-context": earlyContext(); break;
            case "premium": premium(); break;
            case "sessions": sessions(); break;
            default: throw new AssertionError("Unknown scenario: " + args[0]);
        }
        System.out.println("UserConfigLimits: " + args[0] + " passed");
    }

    private static void preferences() {
        Context context = new Context();
        ApplicationLoader.applicationContext = context;
        equal(10, UserConfig.getMaxAccountCount(), "missing preference");
        equal(0, context.preferences.writes, "normal reads should not rewrite settings");
        check("OEGeneralMaxAccountCount".equals(AccountLimits.PREFERENCE_KEY), "Persisted account limit key changed");
        context.preferences.values.put("unrelatedSetting", "keep");
        Object[][] cases = {
            {Integer.MIN_VALUE, 3}, {-1, 3}, {2, 3}, {3, 3}, {10, 10}, {99, 99},
            {100, 100}, {101, 100}, {Integer.MAX_VALUE, 100},
            {"42", 42}, {"bad", 10}, {true, 10}, {42L, 42}, {Long.MAX_VALUE, 100},
            {Double.NaN, 10}, {Double.POSITIVE_INFINITY, 10}, {42.5d, 10},
        };
        for (Object[] test : cases) {
            context.preferences.values.put(AccountLimits.PREFERENCE_KEY, test[0]);
            int expected = (Integer) test[1];
            equal(expected, UserConfig.getMaxAccountCount(), "read/repair imported preference " + test[0]);
            equal(expected, context.preferences.getInt(AccountLimits.PREFERENCE_KEY, -1), "repaired value is persisted as int");
            equal(expected, UserConfig.getMaxAccountCount(), "repaired preference survives another read");
        }
        for (int[] test : new int[][]{{Integer.MIN_VALUE, 3}, {42, 42}, {Integer.MAX_VALUE, 100}}) {
            UserConfig.setMaxAccountCount(test[0]);
            equal(test[1], context.preferences.getInt(AccountLimits.PREFERENCE_KEY, -1), "setter persistence");
            equal(test[1], UserConfig.getMaxAccountCount(), "setter/getter agreement");
        }
        check("keep".equals(context.preferences.values.get("unrelatedSetting")), "Limit repair cleared unrelated settings");
    }

    private static void earlyContext() {
        equal(10, UserConfig.getMaxAccountCount(), "read before Android context exists");
        UserConfig.setMaxAccountCount(1000);
        equal(100, UserConfig.getMaxAccountCount(), "pre-context setter clamps and retains pending value");
        Context context = new Context();
        context.preferences.values.put(AccountLimits.PREFERENCE_KEY, 3);
        ApplicationLoader.applicationContext = context;
        equal(100, UserConfig.getMaxAccountCount(), "pending value flushed when context appears");
        equal(100, context.preferences.getInt(AccountLimits.PREFERENCE_KEY, -1), "pending value persisted");
        context.preferences.values.put(AccountLimits.PREFERENCE_KEY, 42);
        equal(42, UserConfig.getMaxAccountCount(), "subsequent persisted changes are not shadowed by pending state");
        ApplicationLoader.applicationContext = null;
        UserConfig.setMaxAccountCount(-100);
        equal(3, UserConfig.getMaxAccountCount(), "pending lower clamp");
        ApplicationLoader.applicationContext = context;
        UserConfig.setMaxAccountCount(17);
        equal(17, UserConfig.getMaxAccountCount(), "new explicit write supersedes a pending write");
    }

    private static void premium() {
        ApplicationLoader.applicationContext = new Context();
        for (boolean premium : new boolean[]{false, true, false}) {
            UserConfig.premium = premium;
            for (int limit : new int[]{3, 10, 100}) {
                UserConfig.setMaxAccountCount(limit);
                for (int i = 0; i < 100; i++) UserConfig.accounts[i].identity = i < limit ? i + 1 : 0;
                equal(limit, UserConfig.getMaxAccountCount(), "Premium must not change configured limit");
                equal(0, UserConfig.getAvailableAccountCount(), "Premium must not admit extra accounts");
                equal(-1, UserConfig.findFreeAccount(), "full configured limit with premium=" + premium);
                UserConfig.accounts[limit - 1].identity = 0;
                equal(1, UserConfig.getAvailableAccountCount(), "non-Premium accounts have the same availability");
                equal(limit - 1, UserConfig.findFreeAccount(), "hole allocation with premium=" + premium);
            }
        }
        equal(0, UserConfig.premiumReads, "Admission should not scan Premium/runtime state");
    }

    private static void sessions() {
        ApplicationLoader.applicationContext = new Context();
        UserConfig.selectedAccount = 99;
        for (int i = 0; i < 100; i++) UserConfig.accounts[i].identity = 10000L + i;
        long[] before = identities();
        UserConfig.setMaxAccountCount(3);
        equal(100, UserConfig.getActivatedAccountsCount(), "cap reduction lost high-index sessions");
        equal(0, UserConfig.getAvailableAccountCount(), "over-limit availability");
        equal(-1, UserConfig.findFreeAccount(), "over-limit allocation");
        check(Arrays.equals(before, identities()), "setMaxAccountCount rewrote session identities");
        equal(99, UserConfig.selectedAccount, "cap reduction changed selected account");
        check(UserConfig.isValidAccount(99), "high-index session became invalid after cap reduction");
        check(!UserConfig.isValidAccount(-1) && !UserConfig.isValidAccount(100), "invalid slot accepted");
        equal(100, UserConfig.getLoggedInAccountIds().size(), "enumeration stops at configured admission limit");

        for (int i = 0; i < 98; i++) UserConfig.accounts[i].identity = 0;
        equal(2, UserConfig.getActivatedAccountsCount(), "high-only active sessions");
        equal(1, UserConfig.getAvailableAccountCount(), "high-index sessions consume admission capacity");
        equal(0, UserConfig.findFreeAccount(), "low hole with high-index sessions");
        check(UserConfig.getLoggedInAccountIds().equals(Arrays.asList(10098L, 10099L)), "high-only session enumeration");
        UserConfig.accounts[70].identity = 10070L;
        equal(-1, UserConfig.findFreeAccount(), "low holes must not bypass full reduced cap");
        UserConfig.setMaxAccountCount(100);
        equal(97, UserConfig.getAvailableAccountCount(), "raising cap restores available count");
        equal(0, UserConfig.findFreeAccount(), "raising cap permits a low hole");
    }

    private static long[] identities() {
        long[] ids = new long[100];
        for (int i = 0; i < ids.length; i++) ids[i] = UserConfig.accounts[i].identity;
        return ids;
    }

    static void equal(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
