import java.util.Arrays;

/** Executes ApplicationLoader's real account startup/network loops. */
public class AccountStartupTest {
    static final boolean[] persisted = new boolean[100];
    static final boolean[] loaded = new boolean[100];
    static final boolean[] touched = new boolean[100];
    static final boolean[] connections = new boolean[100];
    static final boolean[] checked = new boolean[100];

    static class TLRPC { static class User {} }
    static class UserConfig {
        static final int MAX_ACCOUNT_COUNT = 100;
        static int selectedAccount;
        final int account;
        UserConfig(int account) { this.account = account; }
        static UserConfig getInstance(int account) { return new UserConfig(account); }
        void loadConfig() { loaded[account] = true; }
        boolean isClientActivated() { return loaded[account] && persisted[account]; }
        TLRPC.User getCurrentUser() { return isClientActivated() ? new TLRPC.User() : null; }
    }

    static void touch(int account) {
        if (!persisted[account] && account != UserConfig.selectedAccount) {
            throw new AssertionError("Started an inactive account runtime: " + account);
        }
        touched[account] = true;
    }
    static class MessagesController {
        static MessagesController getInstance(int account) { touch(account); return new MessagesController(); }
        void putUser(TLRPC.User user, boolean cache) {}
    }
    static class ConnectionsManager {
        final int account;
        ConnectionsManager(int account) { this.account = account; }
        static ConnectionsManager getInstance(int account) {
            touch(account); connections[account] = true; return new ConnectionsManager(account);
        }
        static ConnectionsManager getInstanceIfExists(int account) {
            return connections[account] ? new ConnectionsManager(account) : null;
        }
        void checkConnection() { checked[account] = true; }
    }
    static class SendMessagesHelper {
        static SendMessagesHelper getInstance(int account) { touch(account); return new SendMessagesHelper(); }
        void checkUnsentMessages() {}
    }
    static class ContactsController {
        static ContactsController getInstance(int account) { touch(account); return new ContactsController(); }
        void checkAppAccount() {}
    }
    static class DownloadController {
        static DownloadController getInstance(int account) { touch(account); return new DownloadController(); }
    }
    static class FileLoader {
        static FileLoader getInstance(int account) { touch(account); return new FileLoader(); }
        void onNetworkChanged(boolean slow) {}
    }

    static void startup() {
        // STARTUP_LOOPS
    }
    static void networkChanged() {
        boolean isSlow = true;
        // NETWORK_LOOP
    }

    public static void main(String[] args) {
        for (int[] scenario : new int[][]{{0}, {99, 99}, {50, 3, 98, 99}}) {
            Arrays.fill(persisted, false);
            Arrays.fill(loaded, false);
            Arrays.fill(touched, false);
            Arrays.fill(connections, false);
            Arrays.fill(checked, false);
            UserConfig.selectedAccount = scenario[0];
            for (int i = 1; i < scenario.length; i++) persisted[scenario[i]] = true;
            // A broadcast before account initialization must create no runtime.
            networkChanged();
            for (boolean value : touched) if (value) throw new AssertionError("Early network event starts accounts");
            startup();
            for (int i = 0; i < 100; i++) {
                if (!loaded[i]) throw new AssertionError("Account discovery ignored physical slot " + i);
                boolean expected = persisted[i] || i == UserConfig.selectedAccount;
                if (touched[i] != expected || connections[i] != expected) {
                    throw new AssertionError("Startup runtime mismatch at slot " + i);
                }
            }
            networkChanged();
            for (int i = 0; i < 100; i++) {
                if (checked[i] != connections[i]) throw new AssertionError("Network event missed a loaded runtime: " + i);
            }
        }
        System.out.println("AccountStartup: only signed-in/selected accounts start runtimes; slot 99 is restored");
    }
}
