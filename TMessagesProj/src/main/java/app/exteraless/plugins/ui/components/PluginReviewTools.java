package app.exteraless.plugins.ui.components;

import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import app.exteraless.ai.data.Message;
import app.exteraless.ai.network.ToolHandler;
import app.exteraless.plugins.PythonPluginsEngine;

final class PluginReviewTools implements ToolHandler {

    static final long MAX_FILE = 16L * 1024 * 1024;

    private static final int MAX_LINES = 400;
    private static final int MAX_RESULT = 30000;
    private static final int LONG_LINE = 1000;
    private static final int MIN_BLOB = 200;
    private static final int MAX_ENTRY = 8 * 1024 * 1024;
    private static final int MAX_INFLATED = 32 * 1024 * 1024;
    private static final int MIN_STRING = 5;
    private static final Pattern INLINE_BASE64 = Pattern.compile("[A-Za-z0-9+/_-]{" + MIN_BLOB + ",}={0,2}");
    private static final Pattern BASE64_LINE = Pattern.compile("[A-Za-z0-9+/_-]{16,}={0,2}");

    private final LinkedHashMap<String, String[]> files = new LinkedHashMap<>();
    private final ArrayList<Blob> blobs = new ArrayList<>();
    private final LinkedHashMap<String, Integer> lineBlobs = new LinkedHashMap<>();
    private final List<Message> fallback;

    private static final class Blob {

        final String origin;
        final String encoded;
        byte[] bytes;
        boolean decoded;

        Blob(String origin, String encoded, byte[] bytes) {
            this.origin = origin;
            this.encoded = encoded;
            this.bytes = bytes;
            this.decoded = bytes != null;
        }

        byte[] bytes() {
            if (!decoded) {
                decoded = true;
                bytes = decodeBase64(encoded);
            }
            return bytes;
        }
    }

    private PluginReviewTools(List<Message> fallback) {
        this.fallback = fallback;
    }

    static PluginReviewTools load(File file, List<Message> fallback) {
        if (file == null || !file.isFile() || file.length() > MAX_FILE) {
            return null;
        }
        PluginReviewTools tools = new PluginReviewTools(fallback);
        try {
            if (isZip(file)) {
                tools.loadArchive(file);
            } else {
                try (FileInputStream stream = new FileInputStream(file)) {
                    tools.addEntry(file.getName(), readAll(stream, (int) MAX_FILE));
                }
            }
        } catch (Throwable t) {
            FileLog.e("PluginReviewTools: cannot read " + file, t);
            return null;
        }
        return tools.files.isEmpty() && tools.blobs.isEmpty() ? null : tools;
    }

    private void loadArchive(File file) throws Exception {
        try (ZipFile archive = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getSize() > MAX_ENTRY) {
                    continue;
                }
                try (InputStream stream = archive.getInputStream(entry)) {
                    addEntry(entry.getName(), readAll(stream, MAX_ENTRY));
                }
            }
        }
    }

    private void addEntry(String name, byte[] bytes) {
        if (bytes == null) {
            return;
        }
        if (isText(bytes)) {
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
            files.put(name, lines);
            findBlobs(name, lines);
        } else {
            blobs.add(new Blob(name, null, bytes));
        }
    }

    private void findBlobs(String name, String[] lines) {
        StringBuilder block = null;
        int blockStart = -1;
        for (int i = 0; i <= lines.length; i++) {
            String stripped = i < lines.length ? stripLine(lines[i]) : "";
            if (!stripped.isEmpty() && BASE64_LINE.matcher(stripped).matches() && looksEncoded(stripped)) {
                if (block == null) {
                    block = new StringBuilder();
                    blockStart = i;
                }
                block.append(stripped);
                continue;
            }
            if (block != null) {
                if (block.length() >= MIN_BLOB) {
                    addBlob(name, blockStart, i - 1, block.toString());
                }
                block = null;
            }
            if (i < lines.length && lines[i].length() >= MIN_BLOB) {
                Matcher matcher = INLINE_BASE64.matcher(lines[i]);
                while (matcher.find()) {
                    String value = matcher.group();
                    if (looksEncoded(value)) {
                        addBlob(name, i, i, value);
                    }
                }
            }
        }
    }

    private void addBlob(String name, int first, int last, String encoded) {
        int index = blobs.size();
        String origin = name + ":" + (first + 1) + (last > first ? "-" + (last + 1) : "");
        blobs.add(new Blob(origin, encoded, null));
        for (int line = first; line <= last; line++) {
            lineBlobs.put(name + ":" + line, index);
        }
    }

    private static String stripLine(String line) {
        String value = line.trim();
        while (value.startsWith("#") || value.startsWith("//")) {
            value = value.substring(value.startsWith("#") ? 1 : 2).trim();
        }
        if (value.startsWith("b\"") || value.startsWith("b'") || value.startsWith("r\"") || value.startsWith("r'")) {
            value = value.substring(1);
        }
        value = value.replaceAll("^[\"'+(]+", "").replaceAll("[\"',+)\\\\]+$", "").trim();
        return value;
    }

    private static boolean looksEncoded(String value) {
        boolean digit = false;
        boolean upper = false;
        boolean lower = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            digit |= c >= '0' && c <= '9';
            upper |= c >= 'A' && c <= 'Z';
            lower |= c >= 'a' && c <= 'z';
        }
        return digit && upper && lower;
    }

    String summary() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String[]> entry : files.entrySet()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(entry.getKey()).append(" (").append(entry.getValue().length).append(" lines)");
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    String blobSummary() {
        if (blobs.isEmpty()) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < blobs.size(); i++) {
            out.append("\n#").append(i).append(" ").append(describeBlob(blobs.get(i)));
        }
        return out.toString();
    }

    private String describeBlob(Blob blob) {
        byte[] bytes = blob.bytes();
        String where = blob.encoded == null ? "binary file " + blob.origin
                : "base64 at " + blob.origin + ", " + blob.encoded.length() + " chars";
        if (bytes == null) {
            return where + ", not valid base64";
        }
        return where + ", " + kind(bytes) + ", " + bytes.length + " bytes";
    }

    @Override
    public JSONArray definitions() {
        try {
            JSONArray tools = new JSONArray();
            tools.put(function("read_source",
                    "Read numbered lines of a plugin file. Returns at most " + MAX_LINES + " lines per call; call again with the next range until the file is fully read. Very long lines are shortened and point to the base64 blob they contain.",
                    new JSONObject()
                            .put("file", new JSONObject().put("type", "string").put("description", "File name from the list; omit for the main file"))
                            .put("start_line", new JSONObject().put("type", "integer").put("description", "First line, 1-based"))
                            .put("end_line", new JSONObject().put("type", "integer").put("description", "Last line, inclusive")),
                    new JSONArray().put("start_line").put("end_line")));
            tools.put(function("list_blobs",
                    "List embedded data: base64 strings found in the files and binary files inside the plugin archive, with the detected type after decoding.",
                    new JSONObject(), new JSONArray()));
            tools.put(function("decode_base64",
                    "Decode an embedded blob by its number, or a base64 string passed directly. Text is returned as text; gzip and zlib are unpacked; for a zip archive the entry list is returned, and an entry can be read by name; for DEX, native libraries and other binaries the readable strings are returned. Long output is paged with offset.",
                    new JSONObject()
                            .put("blob", new JSONObject().put("type", "integer").put("description", "Blob number from list_blobs"))
                            .put("data", new JSONObject().put("type", "string").put("description", "Base64 string to decode instead of a blob"))
                            .put("entry", new JSONObject().put("type", "string").put("description", "Entry name inside a decoded zip archive"))
                            .put("offset", new JSONObject().put("type", "integer").put("description", "Character offset into the output, default 0")),
                    new JSONArray()));
            tools.put(function("run_python",
                    "Run a short Python script over one blob when decode_base64 is not enough: a cipher of its own, an XOR key, a shuffled alphabet, several packing layers. The blob bytes are in `data`. Available: print, len, range, bytes, bytearray, int, str, list, dict, set, tuple, sorted, enumerate, zip, sum, min, max, abs, ord, chr, bin, hex, isinstance, and the helpers b64, b64url, b32, b16, b85, a85, hexbytes, unzlib, ungzip, unbz2, unlzma, rot13, xor, sha256, sha1, md5, crc32, utf16le, utf16be, to_text. Print what you want back, or assign it to `result`. No imports, no file access, no dunder names; about 5 seconds and " + MAX_RESULT + " characters per call, so keep the script small and print only what matters.",
                    new JSONObject()
                            .put("code", new JSONObject().put("type", "string").put("description", "Python source of the script"))
                            .put("blob", new JSONObject().put("type", "integer").put("description", "Blob number from list_blobs, its bytes become data"))
                            .put("data", new JSONObject().put("type", "string").put("description", "Base64 string becomes data, when there is no blob")),
                    new JSONArray().put("code")));
            return tools;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static JSONObject function(String name, String description, JSONObject properties, JSONArray required)
            throws Exception {
        JSONObject parameters = new JSONObject().put("type", "object").put("properties", properties);
        if (required.length() > 0) {
            parameters.put("required", required);
        }
        return new JSONObject().put("type", "function").put("function",
                new JSONObject().put("name", name).put("description", description).put("parameters", parameters));
    }

    @Override
    public String describe(String name, JSONObject arguments) {
        if ("read_source".equals(name)) {
            String file = arguments.optString("file", "");
            return "read_source " + (file.isEmpty() ? mainFile() : file) + " "
                    + arguments.optInt("start_line", 1) + "–" + arguments.optInt("end_line", 0);
        }
        if ("decode_base64".equals(name)) {
            if (arguments.has("blob")) {
                String entry = arguments.optString("entry", "");
                return "decode_base64 #" + arguments.optInt("blob") + (entry.isEmpty() ? "" : " " + entry);
            }
            return "decode_base64";
        }
        if ("run_python".equals(name)) {
            return "run_python" + (arguments.has("blob") ? " #" + arguments.optInt("blob") : "");
        }
        return name;
    }

    @Override
    public List<Message> fallback() {
        return fallback;
    }

    @Override
    public String call(String name, JSONObject arguments) {
        switch (name == null ? "" : name) {
            case "read_source":
                return readSource(arguments);
            case "list_blobs":
                return "Blobs:" + blobSummary();
            case "decode_base64":
                return decode(arguments);
            case "run_python":
                return runScript(arguments);
            default:
                return "error: unknown tool " + name;
        }
    }

    private String runScript(JSONObject arguments) {
        String code = arguments.optString("code", "");
        if (TextUtils.isEmpty(code)) {
            return "error: pass code";
        }
        byte[] bytes = null;
        if (arguments.has("blob")) {
            int index = arguments.optInt("blob", -1);
            if (index < 0 || index >= blobs.size()) {
                return "error: no such blob. Blobs:" + blobSummary();
            }
            bytes = blobs.get(index).bytes();
        } else {
            String data = arguments.optString("data", "");
            if (!data.isEmpty()) {
                bytes = decodeBase64(data);
                if (bytes == null) {
                    return "error: data is not valid base64";
                }
            }
        }
        String payload = bytes == null ? "" : Base64.encodeToString(bytes, Base64.NO_WRAP);
        String out = PythonPluginsEngine.getInstance().runReviewScript(code, payload);
        if (out == null) {
            return "error: Python engine is not running";
        }
        return out.length() > MAX_RESULT ? out.substring(0, MAX_RESULT) + "\n[truncated]" : out;
    }

    private String mainFile() {
        String best = null;
        for (String file : files.keySet()) {
            if (best == null || file.endsWith("main.py") || file.endsWith(".plugin") && !best.endsWith("main.py")) {
                best = file;
            }
        }
        return best == null ? "" : best;
    }

    private String readSource(JSONObject arguments) {
        String file = arguments.optString("file", "");
        if (TextUtils.isEmpty(file)) {
            file = mainFile();
        }
        String[] lines = files.get(file);
        if (lines == null) {
            for (Map.Entry<String, String[]> entry : files.entrySet()) {
                if (entry.getKey().endsWith(file)) {
                    file = entry.getKey();
                    lines = entry.getValue();
                    break;
                }
            }
        }
        if (lines == null) {
            return "error: no such file. Files: " + summary();
        }
        int start = Math.max(1, arguments.optInt("start_line", 1));
        int end = Math.min(lines.length, Math.max(start, arguments.optInt("end_line", start + MAX_LINES - 1)));
        end = Math.min(end, start + MAX_LINES - 1);
        if (start > lines.length) {
            return "error: the file has only " + lines.length + " lines";
        }
        StringBuilder out = new StringBuilder();
        int line = start;
        for (; line <= end; line++) {
            String text = lines[line - 1];
            if (text.length() > LONG_LINE) {
                Integer blob = lineBlobs.get(file + ":" + (line - 1));
                text = text.substring(0, LONG_LINE) + " …[+" + (text.length() - LONG_LINE) + " chars"
                        + (blob != null ? ", base64 blob #" + blob : "") + "]";
            } else {
                Integer blob = lineBlobs.get(file + ":" + (line - 1));
                if (blob != null && line > start && lineBlobs.get(file + ":" + (line - 2)) != null
                        && blob.equals(lineBlobs.get(file + ":" + (line - 2)))) {
                    int skip = line;
                    while (skip <= lines.length && blob.equals(lineBlobs.get(file + ":" + (skip - 1)))) {
                        skip++;
                    }
                    out.append(line).append("-").append(skip - 1).append("| …[base64 blob #").append(blob).append(" continues]\n");
                    line = skip - 1;
                    continue;
                }
            }
            out.append(line).append("| ").append(text).append('\n');
            if (out.length() > MAX_RESULT) {
                line++;
                break;
            }
        }
        int last = Math.min(line - 1, lines.length);
        out.insert(0, file + " lines " + start + "-" + last + " of " + lines.length + "\n");
        if (last < lines.length) {
            out.append("[continue with start_line=").append(last + 1).append("]");
        } else {
            out.append("[end of file]");
        }
        return out.toString();
    }

    private String decode(JSONObject arguments) {
        byte[] bytes;
        String label;
        if (arguments.has("blob")) {
            int index = arguments.optInt("blob", -1);
            if (index < 0 || index >= blobs.size()) {
                return "error: no such blob. Blobs:" + blobSummary();
            }
            bytes = blobs.get(index).bytes();
            label = "blob #" + index;
        } else {
            String data = arguments.optString("data", "");
            if (data.isEmpty()) {
                return "error: pass blob or data";
            }
            bytes = decodeBase64(data);
            label = "data";
        }
        if (bytes == null) {
            return "error: " + label + " is not valid base64";
        }
        bytes = unpack(bytes);
        String entry = arguments.optString("entry", "");
        if (isZipBytes(bytes)) {
            if (entry.isEmpty()) {
                return label + ": zip archive\n" + zipListing(bytes);
            }
            byte[] content = zipEntry(bytes, entry);
            if (content == null) {
                return "error: no entry " + entry + "\n" + zipListing(bytes);
            }
            bytes = unpack(content);
            label = label + " / " + entry;
        }
        String kind = kind(bytes);
        String text = isText(bytes) ? new String(bytes, StandardCharsets.UTF_8) : strings(bytes);
        int offset = Math.max(0, arguments.optInt("offset", 0));
        if (offset >= text.length()) {
            return label + ": " + kind + ", " + bytes.length + " bytes; offset is past the end (" + text.length() + " chars)";
        }
        int end = Math.min(text.length(), offset + MAX_RESULT);
        StringBuilder out = new StringBuilder();
        out.append(label).append(": ").append(kind).append(", ").append(bytes.length).append(" bytes")
                .append(isText(bytes) ? "" : ", readable strings").append(", chars ").append(offset).append('-').append(end)
                .append(" of ").append(text.length()).append('\n');
        out.append(text, offset, end);
        if (end < text.length()) {
            out.append("\n[continue with offset=").append(end).append("]");
        }
        return out.toString();
    }

    private static byte[] decodeBase64(String value) {
        if (value == null) {
            return null;
        }
        String compact = value.replaceAll("\\s", "");
        for (int flags : new int[]{Base64.DEFAULT, Base64.URL_SAFE}) {
            try {
                byte[] bytes = Base64.decode(compact, flags);
                if (bytes != null && bytes.length > 0) {
                    return bytes;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private static byte[] unpack(byte[] bytes) {
        for (int depth = 0; depth < 3 && bytes != null && bytes.length > 2; depth++) {
            InputStream stream;
            try {
                if ((bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
                    stream = new GZIPInputStream(new ByteArrayInputStream(bytes));
                } else if ((bytes[0] & 0xff) == 0x78 && ((bytes[0] & 0xff) * 256 + (bytes[1] & 0xff)) % 31 == 0) {
                    stream = new InflaterInputStream(new ByteArrayInputStream(bytes));
                } else {
                    return bytes;
                }
                byte[] inflated;
                try (InputStream input = stream) {
                    inflated = readAll(input, MAX_INFLATED);
                }
                if (inflated == null || inflated.length == 0) {
                    return bytes;
                }
                bytes = inflated;
            } catch (Throwable t) {
                return bytes;
            }
        }
        return bytes;
    }

    private static String kind(byte[] bytes) {
        if (bytes.length >= 4) {
            if (bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4) {
                return "zip archive";
            }
            if (bytes[0] == 'd' && bytes[1] == 'e' && bytes[2] == 'x' && bytes[3] == '\n') {
                return "DEX (Android bytecode)";
            }
            if ((bytes[0] & 0xff) == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F') {
                return "ELF (native library)";
            }
            if ((bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
                return "gzip";
            }
            if ((bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
                return "PNG image";
            }
        }
        return isText(bytes) ? "text" : "binary";
    }

    private static boolean isZip(File file) {
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] head = new byte[4];
            return stream.read(head) == 4 && isZipBytes(head);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isZipBytes(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    private static String zipListing(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null && count++ < 500) {
                if (!entry.isDirectory()) {
                    byte[] content = readAll(zip, MAX_ENTRY);
                    out.append(entry.getName()).append(" — ")
                            .append(content == null ? "?" : kind(content) + ", " + content.length + " bytes").append('\n');
                }
            }
        } catch (Throwable t) {
            out.append("error: ").append(t.getMessage());
        }
        return out.toString();
    }

    private static byte[] zipEntry(byte[] bytes, String name) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && (entry.getName().equals(name) || entry.getName().endsWith("/" + name))) {
                    return readAll(zip, MAX_ENTRY);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return null;
    }

    private static boolean isText(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        if (limit == 0) {
            return true;
        }
        int control = 0;
        for (int i = 0; i < limit; i++) {
            int b = bytes[i] & 0xff;
            if (b == 0) {
                return false;
            }
            if (b < 0x20 && b != '\n' && b != '\r' && b != '\t') {
                control++;
            }
        }
        return control * 20 < limit;
    }

    private static String strings(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        StringBuilder current = new StringBuilder();
        for (byte value : bytes) {
            int b = value & 0xff;
            if (b >= 0x20 && b < 0x7f) {
                current.append((char) b);
                continue;
            }
            if (current.length() >= MIN_STRING) {
                out.append(current).append('\n');
            }
            current.setLength(0);
        }
        if (current.length() >= MIN_STRING) {
            out.append(current).append('\n');
        }
        return out.toString();
    }

    private static byte[] readAll(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = input.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            if (out.size() > limit) {
                return null;
            }
        }
        return out.toByteArray();
    }
}
