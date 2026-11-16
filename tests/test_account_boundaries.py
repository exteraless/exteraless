"""Execute production account-boundary/lifecycle methods offline with a 100-slot JDK harness.

Android and media I/O are doubles; the Java validation, constructor, start/stop,
and stream open/close bodies are taken verbatim from production sources.
"""
import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "TMessagesProj/src/main/java"
MESSENGER = JAVA / "org/telegram/messenger"


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    masked = re.sub(
        r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
        lambda match: " " * len(match[0]), source, flags=re.DOTALL,
    )
    depth = 0
    for end in range(opening, len(masked)):
        if masked[end] == "{":
            depth += 1
        elif masked[end] == "}":
            depth -= 1
            if depth == 0:
                return source[start:end + 1]
    raise AssertionError(f"Unterminated production method: {signature}")


def fields(source, names):
    declarations = []
    for name in names:
        match = re.search(
            rf"(?m)^[ \t]*private\s+[^\n;=]*\b{re.escape(name)}\s*(?:=[^\n;]*)?;",
            source,
        )
        assert match, f"Production field {name} not found"
        declarations.append(match[0])
    return "\n".join(declarations)


def run(command):
    result = subprocess.run(
        [str(arg) for arg in command], capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=90,
    )
    assert result.returncode == 0, f"{result.stdout}\n{result.stderr}"


@pytest.fixture(scope="module")
def boundary_java(tmp_path_factory):
    suffix = ".exe" if os.name == "nt" else ""
    home = os.environ.get("JAVA_HOME")
    if home:
        javac, java = (Path(home) / "bin" / (name + suffix) for name in ("javac", "java"))
        assert javac.is_file() and java.is_file(), f"Invalid JAVA_HOME: {home}"
    else:
        javac, java = (shutil.which(name) for name in ("javac", "java"))
        if not javac or not java:
            pytest.skip("JDK required: set JAVA_HOME or put javac/java on PATH")

    story = (JAVA / "org/telegram/ui/Stories/recorder/StoryUploadingService.java").read_text(encoding="utf-8")
    stream = (MESSENGER / "FileStreamLoadOperation.java").read_text(encoding="utf-8")
    config = (MESSENGER / "UserConfig.java").read_text(encoding="utf-8")
    harness = HARNESS.replace("// VALID_ACCOUNT", method(config, "public static boolean isValidAccount("))
    harness = harness.replace("// STORY_FIELDS", fields(story, ["builder", "path", "currentProgress", "currentAccount"]))
    harness = harness.replace("// STORY_METHODS", "\n".join(method(story, signature) for signature in [
        "public StoryUploadingService(", "public int onStartCommand(", "public void onDestroy(",
    ]))
    for kind in ("Chats", "Contacts"):
        widget = (MESSENGER / f"{kind}WidgetService.java").read_text(encoding="utf-8")
        body = fields(widget, ["dids", "mContext", "appWidgetId", "accountInstance", "deleted"])
        body += "\n" + "\n".join(method(widget, signature) for signature in [
            f"public {kind}RemoteViewsFactory(", "public void onCreate(", "public int getCount(",
        ])
        harness = harness.replace(f"// {kind.upper()}_FACTORY", body)
    harness = harness.replace("// STREAM_FIELDS", stream[
        stream.index("    public static final ConcurrentHashMap<Long, FileStreamLoadOperation> allStreams"):
        stream.index("    public FileStreamLoadOperation()")
    ])
    harness = harness.replace("// STREAM_METHODS", "\n".join(method(stream, signature) for signature in [
        "public long open(", "public void close(", "private int getCurrentPriority(",
    ]))
    output = tmp_path_factory.mktemp("account-boundaries")
    source = output / "AccountBoundariesTest.java"
    source.write_text(harness, encoding="utf-8")
    run([javac, "-encoding", "UTF-8", "-d", output, source])
    return java, output


def check_case(boundary_java, *args):
    java, output = boundary_java
    run([java, "-cp", output, "AccountBoundariesTest", *args])


@pytest.mark.parametrize("account", ["-2147483648", "-1", "100", "2147483647", "50", "missing", "wrong-type", "null-intent", "no-path"])
def test_story_rejected_start_and_cleanup(boundary_java, account):
    check_case(boundary_java, "story-invalid", account)


def test_story_switch_repeat_and_logout_cleanup(boundary_java):
    check_case(boundary_java, "story-valid")


@pytest.mark.parametrize("kind", ["Chats", "Contacts"])
@pytest.mark.parametrize("account", ["-2147483648", "-1", "100", "2147483647", "50", "missing", "0", "99", "deleted"])
def test_widget_persisted_account_after_cold_start(boundary_java, kind, account):
    check_case(boundary_java, "widget", kind, account)


@pytest.mark.parametrize("uri", [
    "tg://stream", "tg://stream?account=", "tg://stream?account=garbage",
    "tg://stream?account=prefix0", "tg://stream?account=0suffix", "tg://stream?account=9.9",
    "tg://stream?account=%2099", "tg://stream?account=99%20",
    "tg://stream?account=-1", "tg://stream?account=-2147483648",
    "tg://stream?account=100", "tg://stream?account=2147483647",
    "tg://stream?account=2147483648", "tg://stream?account=-2147483649",
    "tg://stream?account=50", "tg://stream?account=0&account=99", "tg:opaque",
])
def test_stream_invalid_account_is_ioexception_before_loader_access(boundary_java, uri):
    check_case(boundary_java, "stream-invalid", uri)


@pytest.mark.parametrize("account", ["0", "99"])
def test_stream_valid_account_and_close(boundary_java, account):
    check_case(boundary_java, "stream-valid", account)


HARNESS = r"""
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class AccountBoundariesTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static class UserConfig {
        static final int MAX_ACCOUNT_COUNT = 100;
        static int selectedAccount = 0;
        static final boolean[] active = new boolean[MAX_ACCOUNT_COUNT];
        final int account;
        UserConfig(int account) { this.account = account; }
        static UserConfig getInstance(int account) {
            check(account >= 0 && account < MAX_ACCOUNT_COUNT, "out-of-range config access: " + account);
            return new UserConfig(account);
        }
        boolean isClientActivated() { return ApplicationLoader.initialized && active[account]; }
        // VALID_ACCOUNT
    }
    static class ApplicationLoader {
        static boolean initialized;
        static Context applicationContext = new Context();
        static void postInitApplication() { initialized = true; }
    }
    static class Context {
        final SharedPreferences preferences = new SharedPreferences();
        SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
    }
    static class SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        int getInt(String key, int fallback) { return (int) values.getOrDefault(key, fallback); }
        boolean getBoolean(String key, boolean fallback) { return (boolean) values.getOrDefault(key, fallback); }
    }
    static class Intent {
        final Map<String, Object> extras = new HashMap<>();
        Intent putExtra(String key, Object value) { extras.put(key, value); return this; }
        int getIntExtra(String key, int fallback) { return extras.get(key) instanceof Integer ? (int) extras.get(key) : fallback; }
        String getStringExtra(String key) { return extras.get(key) instanceof String ? (String) extras.get(key) : null; }
    }
    static class Service extends Context {
        static final int START_NOT_STICKY = 2;
        int stops, starts;
        void stopSelf() { stops++; }
        void startForeground(int id, Object notification) { starts++; }
        void stopForeground(boolean remove) {}
        public void onDestroy() {}
    }
    static class NotificationCenter {
        static final int uploadStoryEnd = 1, uploadStoryProgress = 2;
        static final NotificationCenter global = new NotificationCenter();
        static final NotificationCenter[] instances = new NotificationCenter[100];
        final Set<Object> observers = new HashSet<>();
        int adds;
        static NotificationCenter getGlobalInstance() { return global; }
        static NotificationCenter getInstance(int account) {
            check(account >= 0 && account < 100, "out-of-range notification access: " + account);
            if (instances[account] == null) instances[account] = new NotificationCenter();
            return instances[account];
        }
        void addObserver(Object observer, int id) { observers.add(observer); adds++; }
        void removeObserver(Object observer, int id) { observers.remove(observer); }
    }
    static class NotificationCompat {
        static class Builder {
            Builder(Context context) {}
            void setSmallIcon(int id) {}
            void setWhen(long value) {}
            void setChannelId(String value) {}
            void setContentTitle(String value) {}
            void setTicker(String value) {}
            void setContentText(String value) {}
            void setProgress(int max, int value, boolean indeterminate) {}
            Object build() { return this; }
        }
    }
    static class NotificationManagerCompat {
        static NotificationManagerCompat from(Context context) { return new NotificationManagerCompat(); }
        void cancel(int id) {}
        void notify(int id, Object notification) {}
    }
    static class NotificationsController {
        static String OTHER_NOTIFICATIONS_CHANNEL = "other";
        static void checkOtherNotificationsChannel() {}
    }
    static class android { static class R { static class drawable { static int stat_sys_upload; } } }
    static class R { static class string { static int NagramX, StoryUploading; } }
    static class LocaleController { static String getString(int id) { return "text"; } }
    static class BuildVars { static boolean LOGS_ENABLED; }
    static class FileLog { static void e(Object value) {} static void d(String value) {} }
    static class StoryUploadingService extends Service {
        // STORY_FIELDS
        // STORY_METHODS
    }
    static class Activity { static final int MODE_PRIVATE = 0; }
    static class AppWidgetManager {
        static final String EXTRA_APPWIDGET_ID = "appWidgetId";
        static final int INVALID_APPWIDGET_ID = 0;
    }
    static class Theme { static void createDialogsResources(Context context) {} }
    static class AccountInstance {
        static final List<Integer> accesses = new ArrayList<>();
        final int account;
        AccountInstance(int account) { this.account = account; }
        static AccountInstance getInstance(int account) {
            check(UserConfig.isValidAccount(account), "invalid widget account accessed: " + account);
            accesses.add(account);
            return new AccountInstance(account);
        }
    }
    static class ChatsRemoteViewsFactory {
        // CHATS_FACTORY
    }
    static class ContactsRemoteViewsFactory {
        // CONTACTS_FACTORY
    }
    static class Uri {
        final URI uri;
        Uri(String value) { uri = URI.create(value); }
        List<String> getQueryParameters(String key) {
            if (uri.isOpaque()) throw new UnsupportedOperationException("opaque URI");
            List<String> values = new ArrayList<>();
            if (uri.getRawQuery() != null) for (String item : uri.getRawQuery().split("&")) {
                String[] pair = item.split("=", 2);
                if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(key))
                    values.add(pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
            }
            return values;
        }
        String getQueryParameter(String key) {
            List<String> values = getQueryParameters(key);
            return values.isEmpty() ? null : values.get(0);
        }
    }
    static class DataSpec {
        final Uri uri;
        long position = 0, length = -1;
        DataSpec(String uri) { this.uri = new Uri(uri); }
    }
    static class C { static final long LENGTH_UNSET = -1; }
    static class TLRPC {
        static class Document {
            long access_hash, id, size;
            int dc_id;
            String mime_type;
            byte[] file_reference;
            List<Object> attributes = new ArrayList<>();
        }
        static class TL_document extends Document {}
        static class TL_documentAttributeFilename { String file_name; }
        static class TL_documentAttributeVideo {}
        static class TL_documentAttributeAudio {}
    }
    static class Utilities {
        static int parseInt(String value) {
            if (value == null) return 0;
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("[-0-9]+").matcher(value);
            try { return match.find() ? Integer.parseInt(match.group()) : 0; }
            catch (NumberFormatException e) { return 0; }
        }
        static long parseLong(String value) { return value == null ? 0 : Long.parseLong(value); }
        static byte[] hexToBytes(String value) { return new byte[0]; }
    }
    static class FileLoadOperation {
        void removeStreamListener(Object listener) {}
        File getCurrentFile() { return null; }
        boolean isFinished() { return false; }
        long[] getDownloadedLengthFromOffset(long offset, long remaining) { return new long[]{remaining}; }
    }
    static class FileLoader {
        static final int PRIORITY_HIGH = 1;
        static final List<Integer> accesses = new ArrayList<>();
        static FileLoader getInstance(int account) {
            check(UserConfig.isValidAccount(account), "invalid stream account accessed: " + account);
            accesses.add(account);
            return new FileLoader();
        }
        Object getParentObject(int id) { return null; }
        FileLoadOperation loadStreamFile(Object listener, TLRPC.Document document, Object location,
                Object parent, long offset, boolean preview, int priority) { return new FileLoadOperation(); }
    }
    static class MessageObject {
        static int getVideoWidth(TLRPC.Document document) { return 0; }
        static String getVideoCodec(TLRPC.Document document) { return ""; }
    }
    static class FileStreamLoadOperation {
        // STREAM_FIELDS
        boolean isNetwork;
        int starts, ends;
        void transferInitializing(DataSpec spec) {}
        void transferStarted(DataSpec spec) { starts++; }
        void transferEnded() { ends++; }
        // STREAM_METHODS
    }
    static Intent upload(int account) { return new Intent().putExtra("currentAccount", account).putExtra("path", "upload"); }
    static Intent invalidUpload(String value) {
        if (value.equals("null-intent")) return null;
        if (value.equals("missing")) return new Intent().putExtra("path", "upload");
        if (value.equals("wrong-type")) return new Intent().putExtra("path", "upload").putExtra("currentAccount", "99");
        if (value.equals("no-path")) return new Intent().putExtra("currentAccount", 99);
        return upload(Integer.parseInt(value));
    }
    static void noObservers(StoryUploadingService service) {
        for (NotificationCenter center : NotificationCenter.instances)
            check(center == null || !center.observers.contains(service), "leaked account observer");
        check(!NotificationCenter.global.observers.contains(service), "leaked global observer");
    }
    public static void main(String[] args) throws Exception {
        UserConfig.active[0] = UserConfig.active[99] = true;
        ApplicationLoader.initialized = true;
        switch (args[0]) {
            case "story-invalid": {
                StoryUploadingService fresh = new StoryUploadingService();
                check(fresh.onStartCommand(invalidUpload(args[1]), 0, 1) == Service.START_NOT_STICKY, "sticky rejection");
                check(fresh.starts == 0 && fresh.stops == 1 && fresh.currentAccount == -1, "rejected start mutated routing");
                fresh.onDestroy();
                noObservers(fresh);
                StoryUploadingService running = new StoryUploadingService();
                running.onStartCommand(upload(99), 0, 2);
                running.onStartCommand(invalidUpload(args[1]), 0, 3);
                check(running.currentAccount == 99 && running.starts == 1, "rejected account replaced subscribed account");
                running.onDestroy();
                noObservers(running);
                break;
            }
            case "story-valid": {
                StoryUploadingService service = new StoryUploadingService();
                service.onStartCommand(upload(0), 0, 1);
                service.onStartCommand(upload(99), 0, 2);
                service.onStartCommand(upload(99), 0, 3);
                check(!NotificationCenter.getInstance(0).observers.contains(service), "old account observer retained");
                check(NotificationCenter.getInstance(99).adds == 1, "duplicate subscription");
                UserConfig.active[99] = false;
                service.onDestroy();
                noObservers(service);
                break;
            }
            case "widget": {
                ApplicationLoader.initialized = false;
                Context context = new Context();
                String value = args[2];
                boolean deleted = value.equals("deleted");
                int account = deleted ? 99 : value.equals("missing") ? -1 : Integer.parseInt(value);
                if (!value.equals("missing")) context.preferences.values.put("account7", account);
                context.preferences.values.put("deleted7", deleted);
                boolean valid = (account == 0 || account == 99) && !deleted;
                Intent intent = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 7);
                if (args[1].equals("Chats")) {
                    ChatsRemoteViewsFactory factory = new ChatsRemoteViewsFactory(context, intent);
                    check(AccountInstance.accesses.isEmpty(), "widget resolved before application initialization");
                    factory.onCreate();
                    check(factory.deleted == !valid && factory.getCount() == 1, "wrong widget fallback");
                    check(valid ? factory.accountInstance.account == account : factory.accountInstance == null, "widget rerouted account");
                } else {
                    ContactsRemoteViewsFactory factory = new ContactsRemoteViewsFactory(context, intent);
                    check(AccountInstance.accesses.isEmpty(), "widget resolved before application initialization");
                    factory.onCreate();
                    check(factory.deleted == !valid && factory.getCount() == 1, "wrong widget fallback");
                    check(valid ? factory.accountInstance.account == account : factory.accountInstance == null, "widget rerouted account");
                }
                check(AccountInstance.accesses.size() == (valid ? 1 : 0), "invalid widget reached account singleton");
                break;
            }
            case "stream-invalid": {
                FileStreamLoadOperation stream = new FileStreamLoadOperation();
                try {
                    stream.open(new DataSpec(args[1]));
                    throw new AssertionError("invalid account accepted: " + args[1]);
                } catch (IOException expected) {
                    check(FileLoader.accesses.isEmpty(), "invalid URI reached loader");
                    check(stream.starts == 0 && FileStreamLoadOperation.allStreams.isEmpty(), "invalid stream started");
                } finally {
                    stream.close();
                }
                check(stream.ends == 0, "unopened transfer ended");
                break;
            }
            case "stream-valid": {
                int account = Integer.parseInt(args[1]);
                FileStreamLoadOperation stream = new FileStreamLoadOperation();
                stream.close();
                long size = stream.open(new DataSpec("tg://stream?account=" + account + "&id=7&size=12&mime=video/mp4"));
                check(size == 12 && stream.starts == 1, "valid stream failed");
                check(!FileLoader.accesses.isEmpty() && FileLoader.accesses.stream().allMatch(a -> a == account), "stream rerouted account");
                stream.close();
                stream.close();
                check(stream.ends == 1 && FileStreamLoadOperation.allStreams.isEmpty(), "bad stream cleanup");
                break;
            }
            default: throw new AssertionError("unknown case: " + args[0]);
        }
    }
}
"""
