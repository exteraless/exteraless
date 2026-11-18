package app.exteraless.speech;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import xyz.nextalone.nagram.NaConfig;

public final class SystemSttTranscriber {

    private static final String TAG = "SystemSttTranscriber";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final long TIMEOUT_MS = 60000;

    private SystemSttTranscriber() {}

    public static boolean isAvailable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false;
        }
        Context context = ApplicationLoader.applicationContext;
        return context != null && SpeechRecognizer.isRecognitionAvailable(context);
    }

    public static List<ResolveInfo> getInstalledServices(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }
        try {
            PackageManager pm = context.getPackageManager();
            return pm.queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        } catch (Throwable e) {
            FileLog.e(TAG, e);
            return new ArrayList<>();
        }
    }

    public static void transcribe(String path, BiConsumer<String, Exception> callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            callback.accept(null, new Exception(LocaleController.getString(R.string.SystemSttRequiresAndroid13)));
            return;
        }

        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            callback.accept(null, new Exception("Application context is null"));
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback.accept(null, new Exception(LocaleController.getString(R.string.SystemSttServiceNone)));
            return;
        }

        executor.submit(() -> {
            File tempPcmFile = null;
            ParcelFileDescriptor pfd = null;
            try {
                if (TextUtils.isEmpty(path) || !new File(path).exists()) {
                    throw new IOException("Audio file not found: " + path);
                }

                tempPcmFile = File.createTempFile("stt_", ".pcm", context.getCacheDir());
                AudioPcmDecoder.decodeToFile(path, tempPcmFile);

                pfd = ParcelFileDescriptor.open(tempPcmFile, ParcelFileDescriptor.MODE_READ_ONLY);
                final File finalFile = tempPcmFile;
                final ParcelFileDescriptor finalPfd = pfd;

                AndroidUtilities.runOnUIThread(() -> startRecognitionOnUI(context, finalFile, finalPfd, callback));
            } catch (Throwable e) {
                FileLog.e(TAG, e);
                safeClose(pfd, tempPcmFile);
                callback.accept(null, e instanceof Exception ? (Exception) e : new Exception(e));
            }
        });
    }

    private static void startRecognitionOnUI(Context context, File pcmFile, ParcelFileDescriptor pfd, BiConsumer<String, Exception> callback) {
        AtomicBoolean finished = new AtomicBoolean(false);

        String customService = NaConfig.INSTANCE.getTranscribeProviderSystemService().String();
        SpeechRecognizer recognizer = null;
        try {
            if (!TextUtils.isEmpty(customService)) {
                ComponentName cn = ComponentName.unflattenFromString(customService);
                if (cn != null) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context, cn);
                }
            }
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            }
        } catch (Throwable e) {
            FileLog.e(TAG, e);
            safeClose(pfd, pcmFile);
            callback.accept(null, new Exception("Failed to initialize SpeechRecognizer: " + e.getMessage()));
            return;
        }

        final SpeechRecognizer finalRecognizer = recognizer;

        Runnable cleanup = () -> {
            if (finished.compareAndSet(false, true)) {
                try {
                    finalRecognizer.destroy();
                } catch (Throwable ignore) {}
                safeClose(pfd, pcmFile);
            }
        };

        Runnable timeoutRunnable = () -> {
            if (!finished.get()) {
                cleanup.run();
                callback.accept(null, new Exception("Speech recognition timed out"));
            }
        };

        AndroidUtilities.runOnUIThread(timeoutRunnable, TIMEOUT_MS);

        finalRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {}

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {}

            @Override
            public void onError(int error) {
                AndroidUtilities.cancelRunOnUIThread(timeoutRunnable);
                cleanup.run();
                String errorMsg = getErrorMessage(error);
                FileLog.d(TAG + ": onError " + error + " (" + errorMsg + ")");
                callback.accept(null, new Exception(errorMsg));
            }

            @Override
            public void onResults(Bundle results) {
                AndroidUtilities.cancelRunOnUIThread(timeoutRunnable);
                cleanup.run();
                ArrayList<String> matches = results != null ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                if (matches != null && !matches.isEmpty() && !TextUtils.isEmpty(matches.get(0))) {
                    callback.accept(matches.get(0).trim(), null);
                } else {
                    callback.accept(null, new Exception(LocaleController.getString(R.string.VoskNothingRecognized)));
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {}

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });

        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioPcmDecoder.TARGET_RATE);
            }

            String lang = NaConfig.INSTANCE.getTranscribeProviderSystemLanguage().String();
            if (TextUtils.isEmpty(lang)) {
                lang = LocaleController.getInstance().getCurrentLocale().toLanguageTag();
            }
            if (!TextUtils.isEmpty(lang)) {
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            }

            finalRecognizer.startListening(intent);
        } catch (Throwable e) {
            FileLog.e(TAG, e);
            AndroidUtilities.cancelRunOnUIThread(timeoutRunnable);
            cleanup.run();
            callback.accept(null, e instanceof Exception ? (Exception) e : new Exception(e));
        }
    }

    private static void safeClose(ParcelFileDescriptor pfd, File file) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Throwable ignore) {}
        }
        if (file != null) {
            try {
                file.delete();
            } catch (Throwable ignore) {}
        }
    }

    private static String getErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return LocaleController.getString(R.string.VoskNothingRecognized);
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Recognition service busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech input";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                return "Language not supported";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "Language unavailable";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "Server disconnected";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                return "Too many requests";
            default:
                return "Speech recognition error (" + error + ")";
        }
    }
}
