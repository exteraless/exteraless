package app.exteraless.privacy;

public class BotAccountPolicyTest {
    public static void main(String[] args) {
        check(0, BotAccountPolicy.choose(false, false, 0, -1, false, new int[]{}, true));
        check(1, BotAccountPolicy.choose(true, false, 0, 1, false, new int[]{}, true));
        check(0, BotAccountPolicy.choose(true, false, 0, 1, false, new int[]{0}, true));
        check(0, BotAccountPolicy.choose(true, false, 0, 2, true, new int[]{0, 1}, true));
        check(BotAccountPolicy.AMBIGUOUS, BotAccountPolicy.choose(true, false, 0, 2, false, new int[]{0, 1}, true));
        check(1, BotAccountPolicy.choose(true, false, 0, 2, true, new int[]{1}, true));
        check(BotAccountPolicy.UNAVAILABLE, BotAccountPolicy.choose(true, false, 0, -1, false, new int[]{}, true));
        check(BotAccountPolicy.UNKNOWN, BotAccountPolicy.choose(true, false, 0, 1, false, new int[]{}, false));
        check(0, BotAccountPolicy.choose(true, false, 0, -1, true, new int[]{0}, true));
        check(0, BotAccountPolicy.choose(true, false, 0, 1, true, new int[]{0}, false));
        check(BotAccountPolicy.UNKNOWN, BotAccountPolicy.choose(true, false, 0, 1, false, new int[]{0}, false));
        // Changing the configured or active account cannot migrate established dialogs.
        for (int active = 0; active < 3; active++) {
            for (int configured = 0; configured < 3; configured++) {
                check(0, BotAccountPolicy.choose(true, false, active, configured, false, new int[]{0}, true));
                check(configured, BotAccountPolicy.choose(true, false, active, configured, false, new int[]{}, true));
            }
        }
        // Always ask applies only after all accounts prove this is a new private bot chat.
        check(BotAccountPolicy.ASK, BotAccountPolicy.choose(true, true, 0, 1, false, new int[]{}, true));
        check(BotAccountPolicy.ASK, BotAccountPolicy.choose(true, true, 0, -1, false, new int[]{}, true));
        check(BotAccountPolicy.ASK, BotAccountPolicy.choose(false, true, 0, -1, false, new int[]{}, true));
        check(0, BotAccountPolicy.choose(false, true, 0, -1, true, new int[]{0}, true));
        if (BotAccountPolicy.isEnabled(false, false)) throw new AssertionError("both modes off");
        if (!BotAccountPolicy.isEnabled(true, false)) throw new AssertionError("dedicated mode");
        if (!BotAccountPolicy.isEnabled(false, true)) throw new AssertionError("ask mode independent of dedicated switch");
        if (!BotAccountPolicy.isEnabled(true, true)) throw new AssertionError("legacy settings remain protected");
        check(0, BotAccountPolicy.choose(true, true, 0, 1, false, new int[]{0}, true));
        check(0, BotAccountPolicy.choose(true, true, 0, 2, true, new int[]{0, 1}, true));
        check(BotAccountPolicy.AMBIGUOUS, BotAccountPolicy.choose(true, true, 0, 2, false, new int[]{0, 1}, true));
        check(BotAccountPolicy.UNKNOWN, BotAccountPolicy.choose(true, true, 0, 1, false, new int[]{}, false));
        long[] ids = {111L, 222L, 0L};
        if (!BotAccountPolicy.isCurrent(ids, ids.clone(), 222L, 222L, 0, 0, true, false, false)) throw new AssertionError();
        if (BotAccountPolicy.isCurrent(ids, new long[]{111L, 333L, 0L}, 222L, 222L, 0, 0, true, false, false)) throw new AssertionError("slot reused after logout");
        if (BotAccountPolicy.isCurrent(ids, ids, 222L, 111L, 0, 0, true, false, false)) throw new AssertionError("setting changed");
        if (BotAccountPolicy.isCurrent(ids, ids, 222L, 222L, 0, 1, true, false, false)) throw new AssertionError("active account changed");
        if (BotAccountPolicy.isCurrent(ids, ids, 222L, 222L, 0, 0, false, false, false)) throw new AssertionError("feature disabled");
        if (BotAccountPolicy.isCurrent(ids, ids, 222L, 222L, 0, 0, true, false, true)) throw new AssertionError("ask mode changed");
        System.out.println("BotAccountPolicy: all cases passed");
    }
    private static void check(int expected, int actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
}
