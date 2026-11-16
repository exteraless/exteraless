import app.exteraless.accounts.*;

/** Real singleton table declarations/getters, with cheap constructors. */
public class AccountSlotsTest {
    static class UserConfig {
        // USER_CONFIG_CONSTANTS
        // USERCONFIG_TABLE
        // USERCONFIG_GET_INSTANCE
        static int constructed;
        final int account;
        UserConfig(int account) {
            this.account = account;
            constructed++;
        }
    }

    static class AccountInstance {
        // ACCOUNTINSTANCE_TABLE
        // ACCOUNTINSTANCE_GET_INSTANCE
        static int constructed;
        final int account;
        AccountInstance(int account) {
            this.account = account;
            constructed++;
        }
    }

    static class NotificationCenter {
        // NOTIFICATIONCENTER_TABLE
        // NOTIFICATIONCENTER_GET_INSTANCE
        static int constructed;
        final int account;
        NotificationCenter(int account) {
            this.account = account;
            constructed++;
        }
    }

    public static void main(String[] args) {
        check(UserConfig.MAX_ACCOUNT_COUNT == 100, "Java storage ceiling must be 100");
        check(UserConfig.MAX_ACCOUNT_DEFAULT_COUNT == 10, "Default must remain 10");
        check(UserConfig.MAX_INSTANCE_COUNT >= 100, "Instance capacity must include slot 99");
        check(UserConfig.Instance.length >= 100, "UserConfig table too small");
        check(AccountInstance.Instance.length >= 100, "AccountInstance table too small");
        check(NotificationCenter.Instance.length >= 100, "NotificationCenter table too small");
        check(UserConfig.constructed + AccountInstance.constructed + NotificationCenter.constructed == 0,
                "Table initialization must not instantiate all accounts");

        UserConfig user99 = UserConfig.getInstance(99);
        AccountInstance account99 = AccountInstance.getInstance(99);
        NotificationCenter notifications99 = NotificationCenter.getInstance(99);
        check(user99.account == 99 && account99.account == 99 && notifications99.account == 99,
                "High-index slot must keep its identity");
        check(user99 == UserConfig.getInstance(99) && account99 == AccountInstance.getInstance(99)
                        && notifications99 == NotificationCenter.getInstance(99), "Singleton identity changed");
        check(UserConfig.constructed == 1 && AccountInstance.constructed == 1 && NotificationCenter.constructed == 1,
                "Accessing slot 99 eagerly constructed other slots");

        for (int i = 0; i < 100; i++) {
            check(UserConfig.getInstance(i).account == i, "UserConfig slot aliases another account");
            check(AccountInstance.getInstance(i).account == i, "AccountInstance slot aliases another account");
            check(NotificationCenter.getInstance(i).account == i, "NotificationCenter slot aliases another account");
        }
        check(UserConfig.constructed == 100 && AccountInstance.constructed == 100 && NotificationCenter.constructed == 100,
                "Exactly one instance is required per used slot");
        System.out.println("AccountSlots: all 100 Java slots are distinct and lazy");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
