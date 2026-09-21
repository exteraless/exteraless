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
            decode(path, samples -> {
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

    private interface SampleSink {
        void accept(byte[] pcm) throws Exception;
    }

    private static void decode(String path, SampleSink sink) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(path);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0) {
                throw new IOException("no audio track");
            }
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            int sourceRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : TARGET_RATE;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            Resampler resampler = new Resampler(sourceRate, channels);
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sourceRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            && outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    resampler = new Resampler(sourceRate, channels);
                } else if (index >= 0) {
                    if (info.size > 0) {
                        ByteBuffer buffer = codec.getOutputBuffer(index);
                        if (buffer != null) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            resampler.feed(buffer, pcmEncoding, sink);
                        }
                    }
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            resampler.flush(sink);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignore) {
                }
                codec.release();
            }
            extractor.release();
        }
    }

    private static final class Resampler {

        private final int sourceRate;
        private final int channels;
        private final short[] pending = new short[CHUNK_SAMPLES];
        private int pendingCount;
        private double position;

        Resampler(int sourceRate, int channels) {
            this.sourceRate = sourceRate <= 0 ? TARGET_RATE : sourceRate;
            this.channels = channels <= 0 ? 1 : channels;
        }

        void feed(ByteBuffer buffer, int pcmEncoding, SampleSink sink) throws Exception {
            short[] mono = toMono(buffer, pcmEncoding);
            double step = (double) sourceRate / TARGET_RATE;
            while (position < mono.length) {
                int source = (int) position;
                pending[pendingCount++] = mono[Math.min(source, mono.length - 1)];
                if (pendingCount == pending.length) {
                    emit(sink, pendingCount);
                    pendingCount = 0;
                }
                position += step;
            }
            position -= mono.length;
        }

        void flush(SampleSink sink) throws Exception {
            if (pendingCount > 0) {
                emit(sink, pendingCount);
                pendingCount = 0;
            }
        }

        private void emit(SampleSink sink, int count) throws Exception {
            byte[] out = new byte[count * 2];
            ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pending, 0, count);
            sink.accept(out);
        }

        private short[] toMono(ByteBuffer buffer, int pcmEncoding) {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                int frames = buffer.remaining() / 4 / channels;
                short[] mono = new short[frames];
                for (int i = 0; i < frames; i++) {
                    float sum = 0;
                    for (int c = 0; c < channels; c++) {
                        sum += buffer.getFloat();
                    }
                    float value = sum / channels;
                    mono[i] = (short) Math.max(Short.MIN_VALUE,
                            Math.min(Short.MAX_VALUE, Math.round(value * Short.MAX_VALUE)));
                }
                return mono;
            }
            ShortBuffer shorts = buffer.asShortBuffer();
            int frames = shorts.remaining() / channels;
            short[] mono = new short[frames];
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += shorts.get();
                }
                mono[i] = (short) (sum / channels);
            }
            return mono;
        }
    }
}
