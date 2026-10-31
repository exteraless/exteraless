package app.exteraless.crash;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;

import com.google.firebase.crashlytics.CustomKeysAndValues;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.json.JSONObject;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.LaunchActivity;

import app.exteraless.plugins.Plugin;
import app.exteraless.plugins.PluginsController;
import app.exteraless.plugins.xposed.XposedHooks;
import tw.nekomimi.nekogram.utils.AndroidUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

public final class CrashLog {

    private static final String FILE_NAME = "last_crash.txt";
    private static final String OUT_OF_MEMORY_FILE_NAME = "last_oom.json";
    private static final int MAX_LENGTH = 64 * 1024;
    private static final int MAX_KEY_LENGTH = 1000;
    private static final String[] COUNTED_CLASSES = {
            "org.telegram.ui.ActionBar.BaseFragment",
            "org.telegram.ui.ChatActivity",
            "android.app.Dialog",
            "android.view.View",
            "org.telegram.ui.Cells.ChatMessageCell",
            "org.telegram.messenger.MessageObject",
            "org.telegram.tgnet.TLRPC$Message",
            "org.telegram.messenger.ImageReceiver",
            "android.graphics.Bitmap",
            "android.text.StaticLayout",
            "org.telegram.ui.Components.RLottieDrawable",
            "org.telegram.ui.Components.AnimatedEmojiDrawable",
            "com.chaquo.python.PyObject",
            "java.lang.ref.FinalizerReference",
    };

    private CrashLog() {
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static File outOfMemoryFile(Context context) {
        return new File(context.getFilesDir(), OUT_OF_MEMORY_FILE_NAME);
    }

    public static void record(Thread thread, Throwable error) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || error == null) {
            return;
        }
        JSONObject outOfMemory = null;
        try {
            final StringWriter stack = new StringWriter();
            final PrintWriter printer = new PrintWriter(stack);
            error.printStackTrace(printer);
            printer.flush();

            final StringBuilder report = new StringBuilder();
            report.append("exteraless ").append(BuildConfig.VERSION_NAME)
                    .append(" (").append(BuildConfig.BUILD_COMMIT_ID).append(")\n");
            report.append("Android ").append(android.os.Build.VERSION.RELEASE)
                    .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
            report.append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n');
            report.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
            if (thread != null) {
                report.append("thread: ").append(thread.getName()).append('\n');
            }
            if (isOutOfMemory(error)) {
                outOfMemory = new JSONObject();
                appendMemoryState(report, outOfMemory);
            }
            report.append('\n').append(stack);

            String text = report.toString();
            if (text.length() > MAX_LENGTH) {
                text = text.substring(0, MAX_LENGTH);
            }
            write(file(context), text);
        } catch (Throwable ignore) {
        }
        if (outOfMemory != null) {
            saveOutOfMemory(context, thread, error, outOfMemory);
        }
    }

    private static boolean isOutOfMemory(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof OutOfMemoryError) {
                return true;
            }
        }
        return false;
    }

    private static void appendMemoryState(StringBuilder report, JSONObject keys) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long used = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
            long max = runtime.maxMemory() >> 20;
            long nativeHeap = Debug.getNativeHeapAllocatedSize() >> 20;
            report.append("heap: ").append(used).append(" / ").append(max)
                    .append(" MB, native: ").append(nativeHeap).append(" MB\n");
            keys.put("heap_mb", used).put("heap_max_mb", max).put("native_mb", nativeHeap);
            long uptime = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 60000L;
            report.append("uptime: ").append(uptime / 60).append(" h ").append(uptime % 60).append(" min\n");
            keys.put("uptime_min", uptime);
        } catch (Throwable ignore) {
        }
        try {
            PluginsController controller = PluginsController.peekInstance();
            if (controller != null) {
                int total = 0;
                int enabled = 0;
                for (Plugin plugin : controller.plugins.values()) {
                    total++;
                    if (plugin.isEnabled()) {
                        enabled++;
                    }
                }
                report.append("plugins: ").append(enabled).append(" enabled / ").append(total).append('\n');
                keys.put("plugins_enabled", enabled).put("plugins_total", total);
            }
        } catch (Throwable ignore) {
        }
        try {
            int accounts = 0;
            int dialogs = 0;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    accounts++;
                    dialogs += MessagesController.getInstance(a).dialogs_dict.size();
                }
            }
            report.append("accounts: ").append(accounts).append(", dialogs: ").append(dialogs).append('\n');
            keys.put("accounts", accounts).put("dialogs", dialogs);
        } catch (Throwable ignore) {
        }
        try {
            LaunchActivity activity = LaunchActivity.instance;
            INavigationLayout layout = activity != null ? activity.getActionBarLayout() : null;
            if (layout != null) {
                int screens = layout.getFragmentStack().size();
                report.append("screens: ").append(screens).append('\n');
                keys.put("screens", screens);
            }
        } catch (Throwable ignore) {
        }
        try {
            int threads = Thread.activeCount();
            int hooks = XposedHooks.activeHookCount();
            report.append("threads: ").append(threads).append(", hooks: ").append(hooks).append('\n');
            keys.put("threads", threads).put("hooks", hooks);
        } catch (Throwable ignore) {
        }
        appendInstanceCounts(report, keys);
    }

    private static void appendInstanceCounts(StringBuilder report, JSONObject keys) {
        try {
            ArrayList<Class<?>> classes = new ArrayList<>(COUNTED_CLASSES.length);
            for (String name : COUNTED_CLASSES) {
                try {
                    classes.add(Class.forName(name));
                } catch (Throwable ignore) {
                }
            }
            Class<?>[] array = classes.toArray(new Class<?>[0]);
            Class<?> vmDebug = Class.forName("dalvik.system.VMDebug");
            Method method = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? HiddenApiBypass.getDeclaredMethod(vmDebug, "countInstancesOfClasses", Class[].class, boolean.class)
                    : vmDebug.getDeclaredMethod("countInstancesOfClasses", Class[].class, boolean.class);
            long[] counts = (long[]) method.invoke(null, array, true);
            if (counts == null) {
                return;
            }
            report.append("objects:");
            for (int i = 0; i < array.length && i < counts.length; i++) {
                String name = array[i].getName();
                String shortName = name.substring(name.lastIndexOf('.') + 1);
                report.append(i == 0 ? " " : ", ").append(shortName).append(' ').append(counts[i]);
                keys.put("obj_" + shortName, counts[i]);
            }
            report.append('\n');
        } catch (Throwable ignore) {
        }
    }

    private static void saveOutOfMemory(Context context, Thread thread, Throwable error, JSONObject keys) {
        try {
            Throwable cause = error;
            while (!(cause instanceof OutOfMemoryError) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            StringBuilder frames = new StringBuilder();
            for (StackTraceElement element : cause.getStackTrace()) {
                String line = element.toString();
                if (frames.length() + line.length() + 1 > MAX_KEY_LENGTH) {
                    break;
                }
                frames.append(line).append('\n');
            }
            String message = String.valueOf(cause.getMessage());
            if (message.length() > MAX_KEY_LENGTH) {
                message = message.substring(0, MAX_KEY_LENGTH);
            }
            keys.put("message", message);
            keys.put("thread", thread != null ? thread.getName() : "");
            keys.put("version", BuildConfig.VERSION_NAME + " (" + BuildConfig.BUILD_COMMIT_ID + ")");
            keys.put("time", System.currentTimeMillis() / 1000L);
            keys.put("stack", frames.toString());
            write(outOfMemoryFile(context), keys.toString());
        } catch (Throwable ignore) {
        }
    }

    public static void reportOutOfMemory() {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        final File file = outOfMemoryFile(context);
        if (!file.exists()) {
            return;
        }
        try {
            final String text = read(file);
            if (text == null || !AndroidUtil.shouldEnableCrashlytics()) {
                return;
            }
            final JSONObject json = new JSONObject(text);
            final CustomKeysAndValues.Builder keys = new CustomKeysAndValues.Builder();
            for (Iterator<String> it = json.keys(); it.hasNext(); ) {
                final String name = it.next();
                final Object value = json.opt(name);
                if (value instanceof Number) {
                    keys.putLong("oom_" + name, ((Number) value).longValue());
                } else {
                    keys.putString("oom_" + name, String.valueOf(value));
                }
            }
            final OutOfMemoryError error = new OutOfMemoryError(json.optString("message"));
            error.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement(CrashLog.class.getName(), "heapExhausted", "CrashLog.java", -1)
            });
            FirebaseCrashlytics.getInstance().recordException(error, keys.build());
        } catch (Throwable ignore) {
        } finally {
            file.delete();
        }
    }

    public static String take() {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        final File file = file(context);
        if (!file.exists() || file.length() == 0) {
            return null;
        }
        try {
            return read(file);
        } catch (Throwable ignore) {
            return null;
        } finally {
            clear();
        }
    }

    public static void clear() {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        try {
            final File file = file(context);
            if (file.exists()) {
                file.delete();
            }
        } catch (Throwable ignore) {
        }
    }

    private static String read(File file) throws Exception {
        final byte[] bytes = new byte[(int) Math.min(file.length(), MAX_LENGTH)];
        final FileInputStream stream = new FileInputStream(file);
        try {
            final int read = stream.read(bytes);
            return read > 0 ? new String(bytes, 0, read, StandardCharsets.UTF_8) : null;
        } finally {
            stream.close();
        }
    }

    private static void write(File file, String text) throws Exception {
        final OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write(text);
            writer.flush();
        } finally {
            writer.close();
        }
    }
}
