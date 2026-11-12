import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

class BotAccountNavigationTest {
    static class UserConfig { static int selectedAccount; }
    static class Fragment {
        int account;
        void setCurrentAccount(int value) { account = value; }
    }
    static class Params {
        boolean removeLast;
        Params setRemoveLast(boolean value) { removeLast = value; return this; }
    }
    static class Layout {
        final List<String> stack = new ArrayList<>(List.of("chats", "source"));
        void presentFragment(Params params) {
            if (params.removeLast) stack.remove(stack.size() - 1);
            stack.add("bot");
        }
        void back() { stack.remove(stack.size() - 1); }
    }
    static class Activity {
        final Layout main = new Layout();
        void switchToAccount(int account, boolean removeAll) {
            UserConfig.selectedAccount = account;
            main.stack.clear();
            main.stack.add("chats");
        }
        Layout getActionBarLayout() { return main; }
    }
    static final HashMap<Fragment, String> navigation = new HashMap<>();
    static final HashSet<String> approvedSends = new HashSet<>();
    static boolean enabled() { return true; }
    static String stamp(int account, long botId) { return account + ":" + botId; }

    static void route(Activity activity, Layout layout, Params params, int account,
                      Fragment fragment, long botId) {
        boolean isBot = true;
        // ROUTE_CALLBACK
    }

    public static void main(String[] args) {
        for (boolean switched : new boolean[] {true, false}) {
            for (boolean replace : new boolean[] {true, false}) {
                for (boolean separateLayout : new boolean[] {false, true}) {
                    UserConfig.selectedAccount = 0;
                    Activity activity = new Activity();
                    Layout source = separateLayout ? new Layout() : activity.main;
                    Params params = new Params();
                    params.removeLast = replace;
                    Fragment fragment = new Fragment();
                    route(activity, source, params, switched ? 1 : 0, fragment, 123);
                    Layout destination = switched ? activity.main : source;
                    if (!destination.stack.get(destination.stack.size() - 1).equals("bot"))
                        throw new AssertionError("Bot did not open in the destination layout");
                    if (fragment.account != (switched ? 1 : 0))
                        throw new AssertionError("Bot opened with the wrong account");
                    destination.back();
                    List<String> expected = switched || replace
                            ? List.of("chats") : List.of("chats", "source");
                    if (!destination.stack.equals(expected))
                        throw new AssertionError("Back lost the chat list: switched=" + switched
                                + ", replace=" + replace + ", stack=" + destination.stack);
                }
            }
        }
    }
}
