package app.exteraless.crash;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashLog {

    private static final String FILE_NAME = "last_crash.txt";
    private static final int MAX_LENGTH = 64 * 1024;

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
