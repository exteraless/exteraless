package app.exteraless.privacy;

public class BotStartDeduplicatorTest {
    public static void main(String[] args) {
        BotStartDeduplicator starts = new BotStartDeduplicator();
        if (!starts.begin(100L, 300L, "payload")) throw new AssertionError();
        if (starts.begin(100L, 300L, "payload")) throw new AssertionError("duplicate start");
        if (!starts.begin(200L, 300L, "payload")) throw new AssertionError("different identity");
        if (!starts.begin(100L, 300L, "other")) throw new AssertionError("different payload");
        starts.failed(100L, 300L, "payload");
        if (!starts.begin(100L, 300L, "payload")) throw new AssertionError("retry failed request");
        System.out.println("BotStartDeduplicator: all cases passed");
    }
}
