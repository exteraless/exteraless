package app.exteraless.speech;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public final class VoskTranscriber {

    private static final int TARGET_RATE = 16000;
    private static final int CHUNK_SAMPLES = 8000;
    private static final long DEQUEUE_TIMEOUT = 10000;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static Model cachedModel;
    private static String cachedCode;
    private static boolean logLevelSet;

    private VoskTranscriber() {
    }

    public static boolean isReady() {
        return VoskManager.isInstalled(VoskManager.getLanguage());
    }

    public static synchronized void releaseModel() {
        if (cachedModel != null) {
            try {
                cachedModel.close();
            } catch (Exception e) {
                FileLog.e(e);
            }
            cachedModel = null;
            cachedCode = null;
        }
    }

    private static synchronized Model obtainModel(String code) throws IOException {
        if (cachedModel != null && code.equals(cachedCode)) {
            return cachedModel;
        }
        releaseModel();
        File dir = VoskManager.getModelDir(code);
        if (dir == null || !dir.exists()) {
            throw new IOException("model not installed");
        }
        if (!logLevelSet) {
            logLevelSet = true;
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        cachedModel = new Model(dir.getAbsolutePath());
        cachedCode = code;
        return cachedModel;
    }

    public static void transcribe(String path, BiConsumer<String, Exception> callback) {
        executor.submit(() -> {
            try {
                String text = run(path);
                callback.accept(text, null);
            } catch (Exception e) {
                callback.accept(null, e);
            } catch (Throwable e) {
                callback.accept(null, new Exception(e));
            }
        });
    }

    private static String run(String path) throws Exception {
        if (TextUtils.isEmpty(path) || !new File(path).exists()) {
            throw new IOException("no audio file");
        }
        String code = VoskManager.getLanguage();
        Model model = obtainModel(code);
        StringBuilder text = new StringBuilder();
        try (Recognizer recognizer = new Recognizer(model, TARGET_RATE)) {
            AudioPcmDecoder.decode(path, samples -> {
                if (recognizer.acceptWaveForm(samples, samples.length)) {
                    appendResult(text, recognizer.getResult());
                }
            });
            appendResult(text, recognizer.getFinalResult());
        }
        return text.toString().trim();
    }

    private static void appendResult(StringBuilder builder, String json) {
        if (TextUtils.isEmpty(json)) {
            return;
        }
        try {
            String part = new JSONObject(json).optString("text", "").trim();
            if (!part.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(part);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
