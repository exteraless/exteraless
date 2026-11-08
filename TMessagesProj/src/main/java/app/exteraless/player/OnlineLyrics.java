package app.exteraless.player;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import app.exteraless.appearance.AppearanceConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OnlineLyrics {

    public static final int OK = 0;
    public static final int NOT_FOUND = 1;
    public static final int ERROR = 2;

    public interface Callback {
        void onResult(Lyrics lyrics, int status);
    }

    public static final class Query {
        public final String artist;
        public final String title;
        public final String album;
        public final int duration;

        public Query(String artist, String title, String album, int duration) {
            String a = clean(artist);
            String t = clean(title);
            if (t != null) {
                t = AUDIO_EXT.matcher(t).replaceFirst("").replace('_', ' ').trim();
            }
            if (TextUtils.isEmpty(a) && t != null) {
                int dash = t.indexOf(" - ");
                if (dash > 0) {
                    a = t.substring(0, dash).trim();
                    t = t.substring(dash + 3).trim();
                }
            }
            this.artist = a;
            this.title = stripDecorations(t);
            this.album = clean(album);
            this.duration = duration;
        }

        public String key() {
            return (artist == null ? "" : artist.toLowerCase(Locale.ROOT)) + "\u0001" + (title == null ? "" : title.toLowerCase(Locale.ROOT)) + "\u0001" + duration;
        }

        public boolean valid() {
            return !TextUtils.isEmpty(title);
        }
    }

    private static final class Found {
        final Lyrics lyrics;

        Found(Lyrics lyrics) {
            this.lyrics = lyrics;
        }
    }

    private interface Source {
        Found request(Query query) throws Exception;
    }

    private static final class HttpStatus extends IOException {
        final int code;

        HttpStatus(int code, String url) {
            super("lyrics http " + code + " " + url);
            this.code = code;
        }
    }

    private static final String LRCLIB = "https://lrclib.net/api/";
    private static final String LRCMUX = "https://api.lrcmux.dev/get";
    private static final String BETTER_LYRICS = "https://lyrics-api.boidu.dev/getLyrics";
    private static final String PAXSENIX = "https://lyrics.paxsenix.org/apple-music/lyrics";
    private static final String BINIMUM = "https://lyrics-api.binimum.org/";
    private static final String LYRICS_PLUS = "https://lyricsplus.binimum.org/v2/lyrics/get";
    private static final String APPLE_WEB = "https://beta.music.apple.com";
    private static final String APPLE_SEARCH = "https://amp-api.music.apple.com/v1/catalog/us/search";
    private static final String BROWSER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";
    private static final String LRCLIB_NAME = "LRCLIB";
    private static final int CACHE_VERSION = 4;
    private static final int MIN_PROVIDER_FILTER_VERSION = 3;
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final int MAX_SCRIPT = 8 * 1024 * 1024;
    private static final int MAX_APPLE_CANDIDATES = 2;
    private static final int LATE_LYRICS_SECONDS = 5;
    private static final long RESOLVE_TIMEOUT_MS = 12_000;
    private static final Pattern APPLE_SCRIPT = Pattern.compile("/assets/index~[^/\"']+\\.js");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_=-]+\\.[A-Za-z0-9_=-]+\\.[A-Za-z0-9_=-]+");
    private static final Pattern PARENTHESES = Pattern.compile("\\s*[(\\[][^)\\]]*[)\\]]");
    private static final Source[] SOURCES = {
            OnlineLyrics::requestBetterLyrics,
            OnlineLyrics::requestPaxsenix,
            OnlineLyrics::requestLrclib,
            OnlineLyrics::requestLrcmux,
            OnlineLyrics::requestBinimum,
            OnlineLyrics::requestLyricsPlus,
    };
    private static final int LRCLIB_INDEX = 2;
    private static final int LRCMUX_INDEX = 3;
    private static final ExecutorService workers = Executors.newCachedThreadPool();
    private static final int MAX_DURATION_DIFF = 3;
    private static final int MAX_FALLBACK_DURATION_DIFF = 5;
    private static final int ALIGN_WINDOW = 4;
    private static final float MIN_ALIGNED = 0.9f;
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern AUDIO_EXT = Pattern.compile("\\.(mp3|m4a|flac|ogg|oga|opus|wav|aac|alac|wma)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECORATION = Pattern.compile("\\s*[(\\[][^)\\]]*(official|lyric|video|audio|visualizer|remaster|hq|hd)[^)\\]]*[)\\]]", Pattern.CASE_INSENSITIVE);
    private static final DispatchQueue queue = new DispatchQueue("lyrics");
    private static final int MAX_ARTWORK_SIZE = 1200;
    private static final int ARTWORK_NO_ARTIST_DURATION_DIFF = 3;
    private static final int ATTEMPTS = 3;
    private static final long[] RETRY_DELAYS = {700, 2000};
    private static final long MAX_RETRY_AFTER = 5000;
    private static final LruCache<String, Lyrics> memory = new LruCache<>(24);
    private static final HashSet<String> missing = new HashSet<>();
    private static volatile String appleToken;

    private OnlineLyrics() {
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static String stripDecorations(String s) {
        if (s == null) {
            return null;
        }
        String out = DECORATION.matcher(s).replaceAll("").trim();
        return out.isEmpty() ? s : out;
    }

    public static Lyrics cached(Query query) {
        return memory.get(query.key());
    }

    public static boolean knownMissing(Query query) {
        return missing.contains(query.key());
    }

    public static void loadCached(Query query, Callback callback) {
        String key = query.key();
        Lyrics hit = memory.get(key);
        if (hit != null) {
            callback.onResult(hit, OK);
            return;
        }
        queue.postRunnable(() -> {
            Lyrics disk = readDisk(key, !AppearanceConfig.lrclibAllowed.Bool());
            AndroidUtilities.runOnUIThread(() -> {
                if (disk != null) {
                    memory.put(key, disk);
                }
                callback.onResult(disk, disk != null ? OK : NOT_FOUND);
            });
        });
    }

    public static void fetch(Query query, Callback callback) {
        String key = query.key();
        Lyrics hit = memory.get(key);
        if (hit != null) {
            callback.onResult(hit, OK);
            return;
        }
        queue.postRunnable(() -> {
            Lyrics result = readDisk(key, false);
            int status = OK;
            if (result == null) {
                boolean[] failed = new boolean[1];
                result = resolve(query, failed);
                if (result != null) {
                    if (!failed[0] || result.wordSynced) {
                        writeDisk(key, result);
                    }
                } else {
                    result = readDisk(key, true);
                    if (result == null) {
                        status = failed[0] ? ERROR : NOT_FOUND;
                    }
                }
            }
            final Lyrics lyrics = result;
            final int finalStatus = status;
            AndroidUtilities.runOnUIThread(() -> {
                if (lyrics != null) {
                    memory.put(key, lyrics);
                    missing.remove(key);
                } else if (finalStatus == NOT_FOUND) {
                    missing.add(key);
                }
                callback.onResult(lyrics, finalStatus);
            });
        });
    }

    private static Lyrics resolve(Query q, boolean[] failed) {
        ArrayList<Future<Found>> tasks = new ArrayList<>(SOURCES.length);
        for (Source source : SOURCES) {
            tasks.add(workers.submit(() -> source.request(q)));
        }
        Found[] found = new Found[SOURCES.length];
        long deadline = SystemClock.elapsedRealtime() + RESOLVE_TIMEOUT_MS;
        for (int i = 0; i < SOURCES.length; i++) {
            boolean core = i == LRCLIB_INDEX || i == LRCMUX_INDEX;
            try {
                Found result = tasks.get(i).get(Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
                if (result != null && fits(q, result.lyrics)) {
                    found[i] = result;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed[0] = true;
                break;
            } catch (Exception e) {
                if (core) {
                    FileLog.e(e);
                    failed[0] = true;
                } else if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("lyrics source " + i + ": " + e);
                }
            }
            if (found[i] != null && found[i].lyrics.wordSynced) {
                for (int j = i + 1; j < SOURCES.length; j++) {
                    tasks.get(j).cancel(true);
                }
                break;
            }
        }
        Found best = null;
        int bestRank = 0;
        for (Found candidate : found) {
            int rank = candidate == null ? 0 : rank(candidate.lyrics);
            if (rank > bestRank) {
                best = candidate;
                bestRank = rank;
            }
        }
        if (best == null) {
            return null;
        }
        Found plain = found[LRCLIB_INDEX];
        if (best == found[LRCMUX_INDEX] && best.lyrics.synced && !best.lyrics.wordSynced && plain != null && !plain.lyrics.synced && !plain.lyrics.instrumental) {
            Lyrics punctuated = punctuate(best.lyrics, plain.lyrics);
            if (punctuated != null) {
                return punctuated;
            }
        }
        return best.lyrics;
    }

    private static int rank(Lyrics lyrics) {
        if (lyrics.wordSynced) {
            return 3;
        }
        return lyrics.synced || lyrics.instrumental ? 2 : 1;
    }

    private static boolean fits(Query q, Lyrics lyrics) {
        if (q.duration <= 0 || !lyrics.synced || lyrics.lines.isEmpty()) {
            return true;
        }
        return lyrics.lines.get(lyrics.lines.size() - 1).time / 1000 <= q.duration + LATE_LYRICS_SECONDS;
    }

    private static String titleKey(String title) {
        return wordKey(PARENTHESES.matcher(title).replaceAll(""));
    }

    private static boolean sameTrack(Query q, String title, String artist, int seconds) {
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(artist) || TextUtils.isEmpty(q.artist)) {
            return false;
        }
        if (q.duration > 0 && seconds > 0 && Math.abs(q.duration - seconds) > MAX_FALLBACK_DURATION_DIFF) {
            return false;
        }
        String wanted = titleKey(q.title);
        String actual = titleKey(title);
        if (wanted.isEmpty() || actual.isEmpty() || !wanted.contains(actual) && !actual.contains(wanted)) {
            return false;
        }
        String wantedArtist = wordKey(q.artist);
        String actualArtist = wordKey(artist);
        return !wantedArtist.isEmpty() && !actualArtist.isEmpty() && (wantedArtist.contains(actualArtist) || actualArtist.contains(wantedArtist));
    }

    private static Found foundOrNull(Lyrics lyrics) {
        return lyrics == null ? null : new Found(lyrics);
    }

    private static Found requestBetterLyrics(Query q) throws Exception {
        if (TextUtils.isEmpty(q.artist)) {
            return null;
        }
        StringBuilder url = new StringBuilder(BETTER_LYRICS).append("?s=").append(enc(q.title)).append("&a=").append(enc(q.artist));
        if (q.duration > 0) {
            url.append("&d=").append(q.duration);
        }
        if (!TextUtils.isEmpty(q.album)) {
            url.append("&al=").append(enc(q.album));
        }
        String body = fetchOnce(url.toString(), MAX_BODY, "application/json", null);
        if (body == null) {
            return null;
        }
        return foundOrNull(LyricsFormats.parseTtml(optString(new JSONObject(body), "ttml"), Lyrics.SOURCE_ONLINE, "Better Lyrics"));
    }

    private static Found requestPaxsenix(Query q) throws Exception {
        if (TextUtils.isEmpty(q.artist)) {
            return null;
        }
        JSONArray songs = searchApple(PARENTHESES.matcher(q.title).replaceAll("") + " " + q.artist);
        Found best = null;
        int tried = 0;
        for (int i = 0; songs != null && i < songs.length() && tried < MAX_APPLE_CANDIDATES; i++) {
            JSONObject song = songs.optJSONObject(i);
            JSONObject attributes = song != null ? song.optJSONObject("attributes") : null;
            if (attributes == null || !sameTrack(q, attributes.optString("name"), attributes.optString("artistName"), (int) (attributes.optLong("durationInMillis") / 1000))) {
                continue;
            }
            tried++;
            String body = fetchOnce(PAXSENIX + "?id=" + enc(song.optString("id")), MAX_BODY, "application/json", null);
            Found found = body == null ? null : fromPaxsenix(new JSONObject(body));
            if (found != null && (best == null || rank(found.lyrics) > rank(best.lyrics))) {
                best = found;
            }
            if (best != null && best.lyrics.wordSynced) {
                break;
            }
        }
        return best;
    }

    private static Found fromPaxsenix(JSONObject o) throws Exception {
        String provider = "Apple Music · Paxsenix";
        Lyrics ttml = LyricsFormats.parseTtml(optString(o, "ttmlContent"), Lyrics.SOURCE_ONLINE, provider);
        if (ttml != null) {
            return new Found(ttml);
        }
        Lyrics enhanced = Lyrics.parse(optString(o, "elrc"), Lyrics.SOURCE_ONLINE, provider);
        if (enhanced != null) {
            return new Found(enhanced);
        }
        JSONArray content = o.optJSONArray("content");
        if ("Syllable".equalsIgnoreCase(o.optString("type")) && content != null) {
            ArrayList<Lyrics.Line> lines = new ArrayList<>(content.length());
            for (int i = 0; i < content.length(); i++) {
                JSONObject line = content.optJSONObject(i);
                JSONArray parts = line != null ? line.optJSONArray("text") : null;
                if (parts == null || line.optBoolean("background", false)) {
                    continue;
                }
                Lyrics.LineBuilder builder = new Lyrics.LineBuilder();
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.optJSONObject(j);
                    if (part == null) {
                        continue;
                    }
                    builder.add(part.optString("text"), part.optLong("timestamp"), part.optLong("endtime"));
                    if (!part.optBoolean("part", false)) {
                        builder.space();
                    }
                }
                lines.add(builder.build(line.optLong("timestamp")));
            }
            if (Lyrics.hasTiming(lines)) {
                return foundOrNull(Lyrics.fromTimed(lines, Lyrics.SOURCE_ONLINE, provider));
            }
        }
        return foundOrNull(Lyrics.parse(optString(o, "plain"), Lyrics.SOURCE_ONLINE, provider));
    }

    static String findArtwork(Query q) throws Exception {
        JSONArray songs = searchApple(TextUtils.isEmpty(q.artist) ? q.title : q.artist + " " + q.title);
        for (int i = 0; songs != null && i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            JSONObject attributes = song != null ? song.optJSONObject("attributes") : null;
            if (attributes == null || !sameArtworkTrack(q, attributes.optString("name"), attributes.optString("artistName"),
                    (int) (attributes.optLong("durationInMillis") / 1000))) {
                continue;
            }
            JSONObject artwork = attributes.optJSONObject("artwork");
            String url = artwork != null ? artwork.optString("url") : "";
            if (url.startsWith("https://")) {
                int size = Math.max(1, Math.min(MAX_ARTWORK_SIZE, Math.min(artwork.optInt("width", MAX_ARTWORK_SIZE), artwork.optInt("height", MAX_ARTWORK_SIZE))));
                return url.replace("{w}", String.valueOf(size)).replace("{h}", String.valueOf(size)).replace("{f}", "jpg");
            }
        }
        return null;
    }

    private static boolean sameArtworkTrack(Query q, String title, String artist, int seconds) {
        if (!TextUtils.isEmpty(q.artist)) {
            return sameTrack(q, title, artist, seconds);
        }
        return q.duration > 0 && seconds > 0 && Math.abs(q.duration - seconds) <= ARTWORK_NO_ARTIST_DURATION_DIFF
                && !TextUtils.isEmpty(title) && titleKey(q.title).equals(titleKey(title));
    }

    private static JSONArray searchApple(String term) throws Exception {
        String url = APPLE_SEARCH + "?term=" + enc(term) + "&types=songs&limit=10&l=en-US&platform=web";
        String body;
        try {
            body = appleGet(url, appleToken(false));
        } catch (HttpStatus e) {
            if (e.code != 401) {
                throw e;
            }
            body = appleGet(url, appleToken(true));
        }
        if (body == null) {
            return null;
        }
        JSONObject results = new JSONObject(body).optJSONObject("results");
        JSONObject songs = results != null ? results.optJSONObject("songs") : null;
        return songs != null ? songs.optJSONArray("data") : null;
    }

    private static String appleGet(String url, String token) throws Exception {
        HttpURLConnection connection = openConnection(url, "application/json", BROWSER_AGENT);
        try {
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Origin", "https://music.apple.com");
            connection.setRequestProperty("Referer", "https://music.apple.com/");
            return readResponse(connection, url, MAX_BODY);
        } finally {
            connection.disconnect();
        }
    }

    private static File appleTokenFile() {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "lrclib");
        return dir.exists() || dir.mkdirs() ? new File(dir, "apple_token") : null;
    }

    private static String appleToken(boolean refresh) throws Exception {
        File file = appleTokenFile();
        if (!refresh) {
            if (appleToken != null) {
                return appleToken;
            }
            if (file != null && file.exists()) {
                try (FileInputStream in = new FileInputStream(file)) {
                    String saved = readLimited(in, MAX_BODY).trim();
                    if (!saved.isEmpty()) {
                        appleToken = saved;
                        return saved;
                    }
                }
            }
        }
        String page = fetchOnce(APPLE_WEB, MAX_BODY, "text/html", BROWSER_AGENT);
        Matcher script = page == null ? null : APPLE_SCRIPT.matcher(page);
        if (script == null || !script.find()) {
            throw new IOException("lyrics: apple script not found");
        }
        String code = fetchOnce(APPLE_WEB + script.group(), MAX_SCRIPT, "*/*", BROWSER_AGENT);
        Matcher jwt = code == null ? null : JWT.matcher(code);
        if (jwt == null || !jwt.find()) {
            throw new IOException("lyrics: apple token not found");
        }
        String token = jwt.group();
        appleToken = token;
        if (file != null) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(token.getBytes(StandardCharsets.UTF_8));
            }
        }
        return token;
    }

    private static Found requestBinimum(Query q) throws Exception {
        if (TextUtils.isEmpty(q.artist)) {
            return null;
        }
        StringBuilder url = new StringBuilder(BINIMUM).append("?track=").append(enc(q.title)).append("&artist=").append(enc(q.artist));
        if (!TextUtils.isEmpty(q.album)) {
            url.append("&album=").append(enc(q.album));
        }
        if (q.duration > 0) {
            url.append("&duration=").append(q.duration);
        }
        String body = fetchOnce(url.toString(), MAX_BODY, "application/json", null);
        JSONArray results = body == null ? null : new JSONObject(body).optJSONArray("results");
        if (results == null) {
            return null;
        }
        String wordUrl = null;
        String lineUrl = null;
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.optJSONObject(i);
            String link = result != null ? optString(result, "lyricsUrl") : null;
            if (link == null || !link.startsWith("https://") || !sameTrack(q, result.optString("track_name"), result.optString("artist_name"), result.optInt("duration"))) {
                continue;
            }
            String timing = result.optString("timing_type");
            if (wordUrl == null && "word".equalsIgnoreCase(timing)) {
                wordUrl = link;
            } else if (lineUrl == null && "line".equalsIgnoreCase(timing)) {
                lineUrl = link;
            }
        }
        String link = wordUrl != null ? wordUrl : lineUrl;
        if (link == null) {
            return null;
        }
        String ttml = fetchOnce(link, MAX_BODY, "application/ttml+xml", null);
        return ttml == null ? null : foundOrNull(LyricsFormats.parseTtml(ttml, Lyrics.SOURCE_ONLINE, "Binimum"));
    }

    private static Found requestLyricsPlus(Query q) throws Exception {
        if (TextUtils.isEmpty(q.artist)) {
            return null;
        }
        StringBuilder url = new StringBuilder(LYRICS_PLUS).append("?title=").append(enc(q.title)).append("&artist=").append(enc(q.artist));
        if (q.duration > 0) {
            url.append("&duration=").append(q.duration);
        }
        if (!TextUtils.isEmpty(q.album)) {
            url.append("&album=").append(enc(q.album));
        }
        String body = fetchOnce(url.toString(), MAX_BODY, "application/json", null);
        if (body == null) {
            return null;
        }
        JSONObject o = new JSONObject(body);
        JSONArray rows = o.optJSONArray("lyrics");
        String type = o.optString("type");
        boolean words = "Word".equalsIgnoreCase(type);
        if (rows == null || !words && !"Line".equalsIgnoreCase(type)) {
            return null;
        }
        ArrayList<Lyrics.Line> lines = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            long time = row.optLong("time");
            JSONArray syllables = words ? row.optJSONArray("syllabus") : null;
            Lyrics.LineBuilder builder = new Lyrics.LineBuilder();
            for (int j = 0; syllables != null && j < syllables.length(); j++) {
                JSONObject syllable = syllables.optJSONObject(j);
                if (syllable != null && !syllable.optBoolean("isBackground", false)) {
                    long start = syllable.optLong("time");
                    builder.add(syllable.optString("text"), start, start + syllable.optLong("duration"));
                }
            }
            if (builder.hasWords()) {
                lines.add(builder.build(time));
            } else if (!words) {
                lines.add(new Lyrics.Line(time, row.optString("text").trim()));
            }
        }
        return Lyrics.hasTiming(lines) ? foundOrNull(Lyrics.fromTimed(lines, Lyrics.SOURCE_ONLINE, "LyricsPlus")) : null;
    }

    private static Found requestLrclib(Query q) throws Exception {
        if (!TextUtils.isEmpty(q.artist)) {
            StringBuilder url = new StringBuilder(LRCLIB).append("get?artist_name=").append(enc(q.artist))
                    .append("&track_name=").append(enc(q.title));
            if (!TextUtils.isEmpty(q.album)) {
                url.append("&album_name=").append(enc(q.album));
            }
            if (q.duration > 0) {
                url.append("&duration=").append(q.duration);
            }
            String body = get(url.toString());
            if (body != null) {
                Found found = fromLrclib(new JSONObject(body));
                if (found != null) {
                    return found;
                }
            }
        }
        StringBuilder url = new StringBuilder(LRCLIB).append("search?track_name=").append(enc(q.title));
        if (!TextUtils.isEmpty(q.artist)) {
            url.append("&artist_name=").append(enc(q.artist));
        }
        String body = get(url.toString());
        if (body == null) {
            return null;
        }
        JSONArray arr = new JSONArray(body);
        Found best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            Found found = o == null ? null : fromLrclib(o);
            if (found == null) {
                continue;
            }
            int score = found.lyrics.wordSynced ? 50 : found.lyrics.synced ? 40 : 0;
            if (q.duration > 0 && o.has("duration")) {
                int diff = (int) Math.abs(Math.round(o.optDouble("duration", 0)) - q.duration);
                score -= diff <= MAX_DURATION_DIFF ? diff : 20 + Math.min(diff, 60);
            }
            if (score > bestScore) {
                bestScore = score;
                best = found;
            }
        }
        return best;
    }

    private static String optString(JSONObject o, String name) {
        if (o == null || o.isNull(name)) {
            return null;
        }
        String s = o.optString(name, null);
        return TextUtils.isEmpty(s) ? null : s;
    }

    private static Found fromLrclib(JSONObject o) {
        if (o.optBoolean("instrumental", false)) {
            return new Found(Lyrics.instrumental(Lyrics.SOURCE_ONLINE, LRCLIB_NAME));
        }
        if (o.optBoolean("hasWordSync", false)) {
            try {
                Lyrics words = LyricsFormats.parseLyricsFile(optString(o, "lyricsfile"), Lyrics.SOURCE_ONLINE, LRCLIB_NAME);
                if (words != null && words.wordSynced) {
                    return new Found(words);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        Lyrics synced = Lyrics.parse(optString(o, "syncedLyrics"), Lyrics.SOURCE_ONLINE, LRCLIB_NAME);
        if (synced != null && synced.synced) {
            return new Found(synced);
        }
        Lyrics plain = Lyrics.parse(optString(o, "plainLyrics"), Lyrics.SOURCE_ONLINE, LRCLIB_NAME);
        if (plain != null) {
            return new Found(plain);
        }
        return synced != null ? new Found(synced) : null;
    }

    private static Found requestLrcmux(Query q) throws Exception {
        StringBuilder url = new StringBuilder(LRCMUX).append("?title=").append(enc(q.title)).append("&level=word&format=json");
        if (!TextUtils.isEmpty(q.artist)) {
            url.append("&artist=").append(enc(q.artist));
        }
        if (!TextUtils.isEmpty(q.album)) {
            url.append("&album=").append(enc(q.album));
        }
        if (q.duration > 0) {
            url.append("&duration=").append(q.duration);
        }
        String body = get(url.toString());
        if (body == null) {
            return null;
        }
        JSONObject o = new JSONObject(body);
        JSONObject track = o.optJSONObject("track");
        JSONObject meta = o.optJSONObject("meta");
        if (q.duration > 0 && track != null) {
            int duration = track.optInt("duration", 0);
            if (duration > 0 && Math.abs(duration - q.duration) > MAX_FALLBACK_DURATION_DIFF) {
                return null;
            }
        }
        JSONObject source = meta != null ? meta.optJSONObject("source") : null;
        String sourceName = source != null ? optString(source, "name") : null;
        String provider = sourceName != null ? "lrcmux · " + sourceName : "lrcmux";
        if (meta != null && meta.optBoolean("instrumental", false)) {
            return new Found(Lyrics.instrumental(Lyrics.SOURCE_ONLINE, provider));
        }
        JSONArray lines = o.optJSONArray("lines");
        if (lines == null || lines.length() == 0) {
            return null;
        }
        boolean synced = meta != null && !"none".equals(meta.optString("level"));
        if (!synced) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < lines.length(); i++) {
                JSONObject line = lines.optJSONObject(i);
                if (line != null) {
                    text.append(line.optString("text", "")).append('\n');
                }
            }
            return foundOrNull(Lyrics.parse(text.toString(), Lyrics.SOURCE_ONLINE, provider));
        }
        ArrayList<Lyrics.Line> timed = new ArrayList<>(lines.length());
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line == null) {
                continue;
            }
            long start = Math.max(0, line.optLong("start", 0));
            String text = line.optString("text", "").trim();
            JSONArray words = line.optJSONArray("words");
            if (words == null || words.length() == 0) {
                timed.add(new Lyrics.Line(start, text));
                continue;
            }
            Lyrics.LineBuilder builder = new Lyrics.LineBuilder();
            for (int j = 0; j < words.length(); j++) {
                JSONObject word = words.optJSONObject(j);
                if (word != null) {
                    builder.add(word.optString("text", ""), word.optLong("start", 0), word.optLong("end", 0));
                }
            }
            timed.add(builder.build(start));
        }
        return foundOrNull(Lyrics.fromTimed(timed, Lyrics.SOURCE_ONLINE, provider));
    }

    private static String wordKey(String word) {
        return NOT_WORD.matcher(word.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static int firstLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String withCase(String token, String original) {
        int t = firstLetter(token);
        int o = firstLetter(original);
        if (t < 0 || o < 0) {
            return token;
        }
        boolean upper = Character.isUpperCase(original.charAt(o));
        char c = token.charAt(t);
        if (Character.isUpperCase(c) == upper) {
            return token;
        }
        return token.substring(0, t) + (upper ? Character.toUpperCase(c) : Character.toLowerCase(c)) + token.substring(t + 1);
    }

    private static void appendWord(StringBuilder sb, String word) {
        if (word.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(word);
    }

    private static Lyrics punctuate(Lyrics timed, Lyrics plain) {
        ArrayList<String> tokens = new ArrayList<>();
        ArrayList<String> keys = new ArrayList<>();
        for (Lyrics.Line line : plain.lines) {
            for (String word : line.text.split("\\s+")) {
                String key = wordKey(word);
                if (!key.isEmpty()) {
                    tokens.add(word);
                    keys.add(key);
                }
            }
        }
        if (keys.isEmpty()) {
            return null;
        }
        int pos = 0;
        int total = 0;
        int matched = 0;
        ArrayList<Lyrics.Line> out = new ArrayList<>(timed.lines.size());
        for (Lyrics.Line line : timed.lines) {
            StringBuilder sb = new StringBuilder();
            for (String word : line.text.split("\\s+")) {
                String key = wordKey(word);
                if (key.isEmpty()) {
                    appendWord(sb, word);
                    continue;
                }
                total++;
                int hit = -1;
                for (int j = pos, end = Math.min(pos + ALIGN_WINDOW, keys.size()); j < end; j++) {
                    if (keys.get(j).equals(key)) {
                        hit = j;
                        break;
                    }
                }
                if (hit < 0) {
                    appendWord(sb, word);
                    continue;
                }
                matched++;
                appendWord(sb, withCase(tokens.get(hit), word));
                pos = hit + 1;
            }
            out.add(new Lyrics.Line(line.time, sb.toString()));
        }
        if (total == 0 || matched < total * MIN_ALIGNED) {
            return null;
        }
        return Lyrics.synced(out, timed.source, timed.provider + " + " + LRCLIB_NAME);
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    private static String get(String url) throws Exception {
        Exception failure = null;
        long delay = 0;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (delay > 0) {
                Thread.sleep(delay);
            }
            delay = RETRY_DELAYS[Math.min(attempt, RETRY_DELAYS.length - 1)];
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(10_000 + attempt * 5_000);
                connection.setRequestProperty("User-Agent", userAgent());
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                if (code == 404) {
                    return null;
                }
                if (code >= 200 && code < 300) {
                    String body;
                    try (InputStream in = connection.getInputStream()) {
                        body = readAll(in);
                    }
                    if (looksLikeJson(body)) {
                        return body;
                    }
                    failure = new IOException("lyrics: not json " + url);
                } else if (isTransient(code)) {
                    failure = new IllegalStateException("lyrics http " + code + " " + url);
                    long retryAfter = connection.getHeaderFieldInt("Retry-After", -1) * 1000L;
                    if (retryAfter > 0) {
                        delay = Math.min(retryAfter, MAX_RETRY_AFTER);
                    }
                } else {
                    throw new IllegalStateException("lyrics http " + code + " " + url);
                }
            } catch (IOException e) {
                failure = e;
            } finally {
                connection.disconnect();
            }
        }
        throw failure;
    }

    private static HttpURLConnection openConnection(String url, String accept, String agent) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(6_000);
        connection.setReadTimeout(8_000);
        connection.setRequestProperty("User-Agent", agent != null ? agent : userAgent());
        connection.setRequestProperty("Accept", accept);
        return connection;
    }

    private static String readResponse(HttpURLConnection connection, String url, int limit) throws Exception {
        int code = connection.getResponseCode();
        if (code == 404) {
            return null;
        }
        if (code < 200 || code >= 300) {
            throw new HttpStatus(code, url);
        }
        try (InputStream in = connection.getInputStream()) {
            return readLimited(in, limit);
        }
    }

    private static String fetchOnce(String url, int limit, String accept, String agent) throws Exception {
        HttpURLConnection connection = openConnection(url, accept, agent);
        try {
            return readResponse(connection, url, limit);
        } finally {
            connection.disconnect();
        }
    }

    private static String userAgent() {
        return "exteraless/" + BuildVars.BUILD_VERSION_STRING + " (https://github.com/exteraless/exteraless)";
    }

    private static boolean isTransient(int code) {
        return code == 408 || code == 425 || code == 429 || code >= 500 && code < 600;
    }

    private static boolean looksLikeJson(String body) {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '{' || c == '[';
            }
        }
        return false;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static String readLimited(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > limit) {
                throw new IOException("lyrics: response too large");
            }
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static File cacheFile(String key) {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "lrclib");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return new File(dir, Utilities.MD5(key) + ".json");
    }

    private static Lyrics readDisk(String key, boolean allowOutdated) {
        try {
            File f = cacheFile(key);
            if (f == null || !f.exists()) {
                return null;
            }
            JSONObject o;
            try (FileInputStream in = new FileInputStream(f)) {
                o = new JSONObject(readAll(in));
            }
            int version = o.optInt("v", 1);
            if (version < CACHE_VERSION && !allowOutdated) {
                return null;
            }
            if (version < 2) {
                Found legacy = fromLrclib(o);
                return legacy != null && (legacy.lyrics.synced || legacy.lyrics.instrumental) ? legacy.lyrics : null;
            }
            String provider = optString(o, "provider");
            if (provider == null) {
                provider = LRCLIB_NAME;
            }
            if (version < MIN_PROVIDER_FILTER_VERSION && !LRCLIB_NAME.equals(provider)) {
                return null;
            }
            if (o.optBoolean("instrumental", false)) {
                return Lyrics.instrumental(Lyrics.SOURCE_ONLINE, provider);
            }
            return Lyrics.parse(optString(o, "text"), Lyrics.SOURCE_ONLINE, provider);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void writeDisk(String key, Lyrics lyrics) {
        try {
            File f = cacheFile(key);
            if (f == null) {
                return;
            }
            JSONObject o = new JSONObject();
            o.put("v", CACHE_VERSION);
            o.put("provider", lyrics.provider);
            o.put("instrumental", lyrics.instrumental);
            o.put("text", lyrics.toText());
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
