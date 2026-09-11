package app.exteraless.plugins.catalog;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Small raw-response cache with same-directory atomic publication. */
final class CatalogCache {
    static final String PAGE = "last_page";
    static final String CATEGORIES = "categories";

    private final File directory;

    CatalogCache(Context context) {
        directory = new File(context.getApplicationContext().getCacheDir(), "plugin_catalog");
    }

    synchronized void write(String name, String sourceUrl, String fingerprint, String raw)
            throws CatalogException {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > CatalogClient.MAX_JSON_BYTES) {
            throw new CatalogException(CatalogException.Kind.RESPONSE_TOO_LARGE,
                    "Catalog cache entry is too large");
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new CatalogException(CatalogException.Kind.STORAGE,
                    "Cannot create catalog cache");
        }
        File target = new File(directory, name + ".json");
        File staging = new File(directory, name + "." + UUID.randomUUID() + ".part");
        try {
            JSONObject wrapper = new JSONObject()
                    .put("source", sourceUrl)
                    .put("fingerprint", fingerprint)
                    .put("savedAt", System.currentTimeMillis())
                    .put("raw", raw);
            byte[] bytes = wrapper.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(staging)) {
                output.write(bytes);
                output.getFD().sync();
            }
            atomicMove(staging, target);
        } catch (Exception e) {
            staging.delete();
            throw new CatalogException(CatalogException.Kind.STORAGE,
                    "Cannot write catalog cache", e);
        }
    }

    synchronized Entry read(String name, String sourceUrl, String fingerprint) {
        File target = new File(directory, name + ".json");
        if (!target.isFile()) return null;
        try {
            byte[] bytes = readBounded(target, CatalogClient.MAX_JSON_BYTES * 2 + 128 * 1024);
            JSONObject wrapper = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!sourceUrl.equals(wrapper.optString("source"))
                    || !fingerprint.equals(wrapper.optString("fingerprint"))) {
                return null;
            }
            long savedAt = wrapper.getLong("savedAt");
            String raw = wrapper.getString("raw");
            if (savedAt <= 0 || raw.getBytes(StandardCharsets.UTF_8).length
                    > CatalogClient.MAX_JSON_BYTES) return null;
            return new Entry(raw, savedAt);
        } catch (Exception ignored) {
            return null;
        }
    }

    synchronized void clear() {
        deleteIfFile(new File(directory, PAGE + ".json"));
        deleteIfFile(new File(directory, CATEGORIES + ".json"));
    }

    synchronized long sizeBytes() {
        long result = 0;
        File page = new File(directory, PAGE + ".json");
        File categories = new File(directory, CATEGORIES + ".json");
        if (page.isFile()) result += page.length();
        if (categories.isFile()) result += categories.length();
        return result;
    }

    private static byte[] readBounded(File file, int cap) throws IOException {
        if (file.length() > cap) throw new IOException("Cache entry too large");
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(file.length(), cap));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > cap) throw new IOException("Cache entry too large");
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    static void atomicMove(File staging, File target) throws IOException {
        try {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            if (!staging.renameTo(target)) throw e;
        }
    }

    private static void deleteIfFile(File file) {
        if (file.isFile()) file.delete();
    }

    static final class Entry {
        final String raw;
        final long savedAtMs;
        Entry(String raw, long savedAtMs) {
            this.raw = raw;
            this.savedAtMs = savedAtMs;
        }
    }
}
