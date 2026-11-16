import app.exteraless.accounts.AccountLimits;
import java.util.Arrays;
import java.util.Random;

/** Executes the complete Android-independent production policy. */
public class AccountLimitsTest {
    public static void main(String[] args) {
        equal(3, AccountLimits.MIN_LIMIT, "minimum");
        equal(10, AccountLimits.DEFAULT_LIMIT, "default");
        equal(100, AccountLimits.MAX_LIMIT, "physical capacity");
        switch (args[0]) {
            case "preferences": preferences(); break;
            case "allocation": allocation(); break;
            case "reduction": reduction(); break;
            case "matrix": matrix(); break;
            default: throw new AssertionError("Unknown scenario: " + args[0]);
        }
        System.out.println("AccountLimits: " + args[0] + " passed");
    }

    private static void preferences() {
        for (int value : new int[]{Integer.MIN_VALUE, -1, 0, 1, 2}) {
            equal(3, AccountLimits.clamp(value), "lower clamp: " + value);
        }
        for (int value = 3; value <= 100; value++) {
            equal(value, AccountLimits.clamp(value), "supported limit");
            equal(value, AccountLimits.fromPreference(value), "integer preference");
        }
        for (int value : new int[]{101, 1000, Integer.MAX_VALUE}) {
            equal(100, AccountLimits.clamp(value), "upper clamp: " + value);
        }
        Object[][] cases = {
            {null, 10}, {true, 10}, {false, 10}, {new Object(), 10},
            {"", 10}, {"garbage", 10}, {"NaN", 10}, {"100.5", 10},
            {"3", 3}, {" 42 ", 42}, {"100", 100}, {"-500", 3},
            {"2147483648", 100}, {"-2147483649", 3},
            {Long.MIN_VALUE, 3}, {Long.MAX_VALUE, 100}, {42L, 42},
            {3.0f, 3}, {100.0d, 100}, {42.5d, 10},
            {Double.NaN, 10}, {Double.POSITIVE_INFINITY, 10}, {Double.NEGATIVE_INFINITY, 10},
            {Float.NaN, 10}, {1e100, 100}, {-1e100, 3},
        };
        for (Object[] test : cases) {
            equal((Integer) test[1], AccountLimits.fromPreference(test[0]), "imported preference: " + test[0]);
        }
    }

    private static void allocation() {
        for (int limit : new int[]{3, 10, 11, 20, 99, 100}) {
            boolean[] active = new boolean[100];
            for (int account = 0; account < limit; account++) {
                equal(limit - account, AccountLimits.getAvailable(limit, account), "remaining admission");
                equal(account, find(limit, active), "lowest unused slot at limit " + limit);
                active[account] = true;
            }
            equal(0, AccountLimits.getAvailable(limit, limit), "full admission");
            equal(-1, find(limit, active), "exhausted limit " + limit);
        }
        boolean[] active = new boolean[100];
        Arrays.fill(active, true);
        equal(-1, find(100, active), "all 100 occupied");
        active[99] = false;
        equal(99, find(100, active), "slot 99 must be allocatable");
        active[99] = true;
        active[73] = false;
        equal(73, find(100, active), "reuse a hole rather than overlap an existing session");
    }

    private static void reduction() {
        boolean[] active = new boolean[100];
        active[98] = active[99] = true;
        boolean[] before = active.clone();
        equal(1, AccountLimits.getAvailable(3, 2), "high-index sessions count toward limit");
        equal(0, find(3, active), "small limit is a session count, not a slot-index bound");
        if (!Arrays.equals(active, before)) throw new AssertionError("Admission changed existing sessions");
        active[80] = true;
        equal(-1, find(3, active), "low holes cannot bypass the configured limit");
        active[50] = true;
        equal(0, AccountLimits.getAvailable(3, 4), "reduced cap cannot report negative availability");
        equal(-1, find(3, active), "cap reduction preserves over-limit sessions");
        equal(0, find(5, active), "raising cap re-enables low holes");
        equal(0, find(100, active), "raising cap to storage ceiling");
        for (int index : new int[]{50, 80, 98, 99}) {
            if (!active[index]) throw new AssertionError("Existing session disappeared: " + index);
        }
    }

    private static void matrix() {
        for (int limit = 3; limit <= 100; limit++) {
            for (int active = 0; active <= 100; active++) {
                equal(Math.max(0, limit - active), AccountLimits.getAvailable(limit, active),
                        "availability count " + active + " / " + limit);
            }
        }
        Random random = new Random(0xAC_C0_99L);
        for (int iteration = 0; iteration < 1000; iteration++) {
            int limit = 3 + random.nextInt(98);
            boolean[] active = new boolean[100];
            int count = 0, firstFree = -1;
            for (int account = 0; account < 100; account++) {
                active[account] = random.nextBoolean();
                if (active[account]) count++;
                else if (firstFree < 0) firstFree = account;
            }
            equal(count >= limit ? -1 : firstFree, find(limit, active), "sparse session layout " + iteration);
        }
    }

    private static int find(int limit, boolean[] active) {
        return AccountLimits.findFree(limit, account -> {
            if (account < 0 || account >= 100) throw new AssertionError("Out-of-range probe: " + account);
            return active[account];
        });
    }

    static void equal(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}
