"""Execute production push routing/registration with lightweight Android stand-ins."""
import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
MESSENGER = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger"
SOURCE = MESSENGER / "PushListenerController.java"


def method(source, name):
    masked = re.sub(
        r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"',
        lambda match: " " * len(match[0]), source, flags=re.DOTALL,
    )
    start = re.search(rf"private static [\w<>]+ {name}\([^{{]*\) \{{", masked)
    assert start, f"Production method {name} not found"
    opening = start.end() - 1
    depth = 0
    for end in range(opening, len(masked)):
        depth += (masked[end] == "{") - (masked[end] == "}")
        if depth == 0:
            return source[start.start():end + 1]
    raise AssertionError(f"Unterminated method {name}")


@pytest.fixture(scope="module")
def push_harness(tmp_path_factory):
    suffix = ".exe" if os.name == "nt" else ""
    home = os.environ.get("JAVA_HOME")
    tools = [str(Path(home) / "bin" / (name + suffix)) if home else shutil.which(name)
             for name in ("javac", "java")]
    if not all(tools):
        pytest.skip("JDK required: set JAVA_HOME or add javac/java to PATH")
    source = SOURCE.read_text(encoding="utf-8")
    methods = "\n".join(method(source, name) for name in (
        "getAccountForPush", "sendRegistrationToServerInternal",
        "unregisterCurrentPushInternal", "unregisterPush", "onDecryptError",
    ))
    gate_start = source.index("int account = getAccountForPush(json);")
    gate_end = source.index("final int accountFinal = currentAccount = account;", gate_start)
    harness = HARNESS.replace("// PRODUCTION_METHODS", methods)
    harness = harness.replace("// PRODUCTION_ROUTING_GATE", source[gate_start:gate_end])
    output = tmp_path_factory.mktemp("push-java")
    path = output / "PushAccountTest.java"
    path.write_text(harness, encoding="utf-8")
    subprocess.run([tools[0], "-encoding", "UTF-8", "-d", str(output), str(path)],
                   check=True, capture_output=True, text=True, timeout=60)
    return tools[1], output


@pytest.mark.parametrize("scenario", ["routing", "registration", "resume"])
def test_push_account_isolation(push_harness, scenario):
    java, output = push_harness
    result = subprocess.run([java, "-cp", str(output), "PushAccountTest", scenario],
                            capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr


def test_push_diagnostics_do_not_log_keys_or_payloads():
    source = SOURCE.read_text(encoding="utf-8")
    logs = re.findall(r"FileLog\.[dew]\((.*?)\);", source, flags=re.DOTALL)
    for expression in logs:
        assert not re.search(
            r"\b(?:pushAuthKey|pushAuthKeyId|inAuthKeyId|jsonString|json|custom|"
            r"strBytes|bytes|data|messageText)\b", expression,
        ), expression
        # JSON exceptions can include the complete input in their message.
        assert expression.strip() != "e", expression
        assert not re.search(r"\be\.(?:getMessage|toString)\(", expression), expression
    gcm = (MESSENGER / "GcmPushListenerService.java").read_text(encoding="utf-8")
    receive = gcm[gcm.index("public void onMessageReceived"):gcm.index("public void onNewToken")]
    for expression in re.findall(r"FileLog\.[dew]\((.*?)\);", receive, flags=re.DOTALL):
        assert not re.search(r"\b(?:data|message)\b", expression), expression


HARNESS = r"""
import java.util.*;
import java.util.concurrent.CountDownLatch;

public class PushAccountTest {
    static final int PUSH_TYPE_FIREBASE = 2, PUSH_TYPE_SIMPLE = 4,
            PUSH_TYPE_WEB = 10, PUSH_TYPE_HUAWEI = 13;
    @interface PushType {}
    static boolean isPushTypeDisabled(int type) { return false; }
    // PRODUCTION_METHODS

    static class JSONObject {
        static final Object NULL = new Object();
        final boolean present;
        final Object value;
        JSONObject() { present = false; value = null; }
        JSONObject(Object value) { present = true; this.value = value; }
        boolean has(String key) { return present; }
        Object opt(String key) { return value; }
    }
    static class UserConfig {
        static final int MAX_ACCOUNT_COUNT = 100;
        static int selectedAccount;
        static UserConfig[] instances = new UserConfig[MAX_ACCOUNT_COUNT];
        long uid;
        boolean active, registeredForPush = true;
        int saves;
        static UserConfig getInstance(int slot) {
            if (instances[slot] == null) instances[slot] = new UserConfig();
            return instances[slot];
        }
        boolean isClientActivated() { return active; }
        long getClientUserId() { return uid; }
        void saveConfig(boolean withFile) { saves++; }
    }
    static class SharedConfig {
        static String pushString = "", pushStringStatus = "";
        static int pushType;
        static long pushStringGetTimeStart, pushStringGetTimeEnd;
        static boolean pushStatSent;
        static void saveConfig() {}
    }
    static class TextUtils {
        static boolean equals(String a, String b) { return Objects.equals(a, b); }
        static boolean isEmpty(String value) { return value == null || value.isEmpty(); }
    }
    static class TLRPC {
        static class TL_help_saveAppLog {
            ArrayList<TL_inputAppEvent> events = new ArrayList<>();
        }
        static class TL_inputAppEvent { long time, peer; String type; Object data; }
        static class TL_jsonNull {}
    }
    static class ConnectionsManager {
        static int[] resumes = new int[100], pushes = new int[100];
        final int slot;
        ConnectionsManager(int slot) { this.slot = slot; }
        static ConnectionsManager getInstance(int slot) { return new ConnectionsManager(slot); }
        static void setRegId(String token, int type, String status) {}
        static void onInternalPushReceived(int slot) { pushes[slot]++; }
        void sendRequest(Object request, Object callback) {}
        void resumeNetworkMaybe() { resumes[slot]++; }
    }
    static class MessagesController {
        static Set<Integer> created = new HashSet<>();
        static int[] registered = new int[100], unregistered = new int[100];
        final int slot;
        MessagesController(int slot) { this.slot = slot; }
        static MessagesController getInstance(int slot) {
            created.add(slot);
            return new MessagesController(slot);
        }
        void registerForPush(int type, String token) { registered[slot]++; }
        void unregisterPush(int type, String token) { unregistered[slot]++; }
        void clearSimplePushRegistration() {}
    }
    static class AndroidUtilities { static void runOnUIThread(Runnable work) { work.run(); } }
    static class Utilities {
        static final Queue stageQueue = new Queue();
        static class Queue { void postRunnable(Runnable work) { work.run(); } }
    }
    static class BuildVars { static boolean LOGS_ENABLED; }
    static class FileLog { static void d(String message) {} }

    static int deliveredAccount;
    static CountDownLatch countDownLatch;
    static void dispatchGate(JSONObject json) {
        String tag = "TEST";
        // PRODUCTION_ROUTING_GATE
        deliveredAccount = account;
    }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void activate(int slot, long uid) {
        UserConfig config = UserConfig.getInstance(slot);
        config.active = true;
        config.uid = uid;
    }
    static void route(JSONObject json, int expected) {
        check(getAccountForPush(json) == expected, "Recipient resolved to wrong account");
        deliveredAccount = -1;
        countDownLatch = new CountDownLatch(1);
        dispatchGate(json);
        check(deliveredAccount == expected, "Routing gate delivered to wrong account");
        if (expected == -1) check(countDownLatch.getCount() == 0, "Rejected push did not release waiter");
    }
    static void routing() {
        activate(0, 7);
        activate(99, 4294967303L); // Low 32 bits equal account 0's ID.
        activate(50, Long.MAX_VALUE);
        route(new JSONObject(7), 0);
        route(new JSONObject(4294967303L), 99);
        route(new JSONObject("4294967303"), 99);
        route(new JSONObject("9223372036854775807"), 50);
        route(new JSONObject(), 0); // Only an absent field gets the legacy fallback.
        for (Object invalid : new Object[] {
                null, JSONObject.NULL, true, false, 7.0, 7.5, new JSONObject(), new Object[] {7},
                "", "user7", "7suffix", " 7", "7 ", "7.0", "7e0", "0x7",
                "9223372036854775808", "-9223372036854775809", 0, -7L, "-7", "0", "999999"
        }) route(new JSONObject(invalid), -1);
        UserConfig.selectedAccount = 99;
        route(new JSONObject(), 99);
        route(new JSONObject("7"), 0); // Explicit recipient ignores foreground selection.
        UserConfig.getInstance(99).active = false;
        route(new JSONObject("4294967303"), -1);
        route(new JSONObject(), -1);
        UserConfig.selectedAccount = 100;
        route(new JSONObject(), -1);
        UserConfig.selectedAccount = -1;
        route(new JSONObject(), -1);
    }
    static void registration() {
        activate(99, 4294967303L);
        sendRegistrationToServerInternal(PUSH_TYPE_FIREBASE, "test-token");
        check(MessagesController.created.equals(Collections.singleton(99)), "Unused account controller created");
        check(MessagesController.registered[99] == 1, "High slot not registered");
        for (int slot = 0; slot < 100; slot++) {
            UserConfig config = UserConfig.getInstance(slot);
            check(config.saves == (slot == 99 ? 1 : 0), "Registration saved inactive slot " + slot);
        }
        unregisterCurrentPushInternal();
        check(MessagesController.unregistered[99] == 1, "High slot not unregistered");
        check(MessagesController.created.equals(Collections.singleton(99)), "Unregistration created unused controller");
        for (int slot = 0; slot < 100; slot++) {
            UserConfig config = UserConfig.getInstance(slot);
            check(config.saves == (slot == 99 ? 2 : 0), "Unregistration saved inactive slot " + slot);
        }
        UserConfig.getInstance(99).active = false;
        sendRegistrationToServerInternal(PUSH_TYPE_FIREBASE, "next-token");
        unregisterCurrentPushInternal();
        check(UserConfig.getInstance(99).saves == 2, "Logged-out account was saved");
    }
    static void resume() {
        activate(0, 7);
        activate(99, 4294967303L);
        CountDownLatch latch = new CountDownLatch(1);
        onDecryptError(latch);
        check(latch.getCount() == 0, "Decrypt failure did not release waiter");
        for (int slot = 0; slot < 100; slot++) {
            int expected = slot == 0 || slot == 99 ? 1 : 0;
            check(ConnectionsManager.resumes[slot] == expected, "Resumed wrong slot " + slot);
            check(ConnectionsManager.pushes[slot] == expected, "Pushed wrong slot " + slot);
        }
    }
    public static void main(String[] args) {
        switch (args[0]) {
            case "routing": routing(); break;
            case "registration": registration(); break;
            case "resume": resume(); break;
            default: throw new AssertionError("Unknown test");
        }
    }
}
"""
