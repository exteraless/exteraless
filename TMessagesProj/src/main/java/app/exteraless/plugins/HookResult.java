package app.exteraless.plugins;

public class HookResult {

    public enum Strategy {
        DEFAULT, CANCEL, MODIFY, MODIFY_FINAL;

        public static Strategy fromString(String s) {
            if (s == null) {
                return DEFAULT;
            }
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return DEFAULT;
            }
        }
    }

    public static final HookResult DEFAULT = new HookResult(Strategy.DEFAULT);

    public final Strategy strategy;
    public final Object value;

    public HookResult(Strategy strategy) {
        this(strategy, null);
    }

    public HookResult(Strategy strategy, Object value) {
        this.strategy = strategy;
        this.value = value;
    }

    public <T> T replacement(Class<T> type) {
        return (strategy == Strategy.MODIFY || strategy == Strategy.MODIFY_FINAL) && type.isInstance(value)
                ? type.cast(value) : null;
    }

    public boolean isCancel() {
        return strategy == Strategy.CANCEL;
    }

    public boolean isFinal() {
        return strategy == Strategy.MODIFY_FINAL;
    }
}
