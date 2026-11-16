package app.exteraless.accounts;

import java.util.function.IntPredicate;

/** Account admission policy. Existing sessions always retain their physical slots. */
public final class AccountLimits {
    public static final int MIN_LIMIT = 3;
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 100;

    public static final String PREFERENCES_NAME = "nkmrcfg";
    public static final String PREFERENCE_KEY = "OEGeneralMaxAccountCount";

    private AccountLimits() {
    }

    public static int clamp(int limit) {
        return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, limit));
    }

    /** Repairs imported preference types without overflowing before the range check. */
    public static int fromPreference(Object value) {
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isNaN(number) && !Double.isInfinite(number) && number == Math.rint(number)) {
                return (int) Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, number));
            }
        } else if (value instanceof String) {
            try {
                long number = Long.parseLong(((String) value).trim());
                return (int) Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, number));
            } catch (NumberFormatException ignore) {
            }
        }
        return DEFAULT_LIMIT;
    }

    /** Number of additional sessions allowed, including when the limit was reduced. */
    public static int getAvailable(int limit, int activatedCount) {
        return Math.max(0, clamp(limit) - Math.max(0, activatedCount));
    }

    /**
     * Returns the lowest unused slot in 0..99, or -1 when admission is full.
     * The limit applies to the number of sessions, never to their slot indexes.
     */
    public static int findFree(int limit, IntPredicate isActivated) {
        int configuredLimit = clamp(limit);
        int activatedCount = 0;
        int firstFree = -1;
        for (int account = 0; account < MAX_LIMIT; account++) {
            if (isActivated.test(account)) {
                if (++activatedCount >= configuredLimit) {
                    return -1;
                }
            } else if (firstFree == -1) {
                firstFree = account;
            }
        }
        return firstFree;
    }
}
