package app.exteraless.crash;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;

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

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class CrashLog {

    private static final String FILE_NAME = "last_crash.txt";
    private static final int MAX_LENGTH = 64 * 1024;
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

    public static void record(Thread thread, Throwable error) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || error == null) {
            return;
        }
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
                appendMemoryState(report);
            }
            report.append('\n').append(stack);

            String text = report.toString();
            if (text.length() > MAX_LENGTH) {
                text = text.substring(0, MAX_LENGTH);
            }

            final OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file(context)), StandardCharsets.UTF_8);
            try {
                writer.write(text);
                writer.flush();
            } finally {
                writer.close();
            }
        } catch (Throwable ignore) {
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

    private static void appendMemoryState(StringBuilder report) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            report.append("heap: ").append(used >> 20).append(" / ").append(runtime.maxMemory() >> 20)
                    .append(" MB, native: ").append(Debug.getNativeHeapAllocatedSize() >> 20).append(" MB\n");
            long uptime = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 60000L;
            report.append("uptime: ").append(uptime / 60).append(" h ").append(uptime % 60).append(" min\n");
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
        } catch (Throwable ignore) {
        }
        try {
            LaunchActivity activity = LaunchActivity.instance;
            INavigationLayout layout = activity != null ? activity.getActionBarLayout() : null;
            if (layout != null) {
                report.append("screens: ").append(layout.getFragmentStack().size()).append('\n');
            }
        } catch (Throwable ignore) {
        }
        try {
            report.append("threads: ").append(Thread.activeCount())
                    .append(", hooks: ").append(XposedHooks.activeHookCount()).append('\n');
        } catch (Throwable ignore) {
        }
        appendInstanceCounts(report);
    }

    private static void appendInstanceCounts(StringBuilder report) {
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
                report.append(i == 0 ? " " : ", ")
                        .append(name.substring(name.lastIndexOf('.') + 1))
                        .append(' ').append(counts[i]);
            }
            report.append('\n');
        } catch (Throwable ignore) {
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
            final byte[] bytes = new byte[(int) Math.min(file.length(), MAX_LENGTH)];
            final java.io.FileInputStream stream = new java.io.FileInputStream(file);
            try {
                final int read = stream.read(bytes);
                if (read <= 0) {
                    return null;
                }
                return new String(bytes, 0, read, StandardCharsets.UTF_8);
            } finally {
                stream.close();
            }
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
}
