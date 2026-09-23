package app.exteraless.nowplaying;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class LastFmNowPlaying {

    public static class Track {
        public String name;
        public String artist;
        public String album;
        public String coverUrl;
        public String trackUrl;
        public boolean live;
    }

    public interface Callback {
        void onResult(String nick, Track track);
    }

    private static final String URL = "https://www.last.fm/user/%s/partial/recenttracks?ajax=1&page=1";
    private static final String PATH = "/user/%s/partial/recenttracks?ajax=1&page=1";
    private static final int DEBUG_LIMIT = 120;
    private static final int PREFIX_LIMIT = 24 * 1024;
    private static final long TTL = 60_000L;
    private static final long CONNECT_TIMEOUT = 6_000L;
    private static final long READ_TIMEOUT = 6_000L;
    private static final long CALL_TIMEOUT = 10_000L;

    private static final Pattern ROW = Pattern.compile("<tr[^>]*\\bchartlist-row\\b");
    private static final Pattern TRACK = Pattern.compile("data-track-name=\"([^\"]*)\"");
    private static final Pattern ARTIST = Pattern.compile("data-artist-name=\"([^\"]*)\"");
    private static final Pattern TRACK_URL = Pattern.compile("data-track-url=\"([^\"]*)\"");
    private static final Pattern COVER = Pattern.compile("src=\"(https://lastfm-img[^\"]*)\"");
    private static final Pattern ALBUM = Pattern.compile("href=\"/music/[^/\"]+/(?!_/)([^\"]+)\"");

    private static final HashMap<String, Track> CACHE = new HashMap<>();
    private static final HashMap<String, Long> STAMPS = new HashMap<>();
    private static final HashMap<String, ArrayList<Callback>> WAITING = new HashMap<>();
    private static final ArrayList<String> DEBUG = new ArrayList<>();
    private static volatile boolean webMode;
    private static OkHttpClient client;

    private LastFmNowPlaying() {
    }

    public static Track cached(String nick) {
        if (TextUtils.isEmpty(nick)) {
            return null;
        }
        synchronized (CACHE) {
            Long stamp = STAMPS.get(nick);
            if (stamp == null || System.currentTimeMillis() - stamp > TTL) {
                return null;
            }
            return CACHE.get(nick);
        }
    }

    public static void debug(String message) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + message;
        synchronized (DEBUG) {
            DEBUG.add(line);
            while (DEBUG.size() > DEBUG_LIMIT) {
                DEBUG.remove(0);
            }
        }
        FileLog.d("LastFm: " + message);
    }

    public static String debugLog() {
        synchronized (DEBUG) {
            return TextUtils.join("\n", DEBUG);
        }
    }

    public static void clearDebug() {
        synchronized (DEBUG) {
            DEBUG.clear();
        }
    }

    public static boolean isWebMode() {
        return webMode;
    }

    public static void setWebMode(boolean enabled) {
        webMode = enabled;
        debug("mode: " + (enabled ? "webview" : "okhttp"));
    }

    public static void forget(String nick) {
        synchronized (CACHE) {
            CACHE.remove(nick);
            STAMPS.remove(nick);
        }
    }

    public static void prefetch(String nick) {
        if (TextUtils.isEmpty(nick) || cached(nick) != null) {
            return;
        }
        request(nick, (resultNick, track) -> {
        });
    }

    public static void request(String nick, Callback callback) {
        if (TextUtils.isEmpty(nick) || callback == null) {
            return;
        }
        Track fresh = cached(nick);
        if (fresh != null) {
            callback.onResult(nick, fresh);
            return;
        }
        synchronized (CACHE) {
            ArrayList<Callback> waiting = WAITING.get(nick);
            if (waiting != null) {
                waiting.add(callback);
                return;
            }
            waiting = new ArrayList<>();
            waiting.add(callback);
            WAITING.put(nick, waiting);
        }
        if (webMode) {
            requestWeb(nick);
            return;
        }
        Call call;
        try {
            call = client().newCall(requestFor(nick));
        } catch (Throwable e) {
            FileLog.e(e);
            debug("okhttp: " + e);
            deliver(nick, null, false);
            return;
        }
        long startedAt = System.currentTimeMillis();
        debug("okhttp: request " + nick + (TextUtils.isEmpty(LastFmWebFetcher.cookies()) ? "" : " with webview cookies"));
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                FileLog.e(e);
                debug("okhttp: failed " + e);
                deliver(nick, null, true);
            }

            @Override
            public void onResponse(Call call, Response response) {
                Track track = null;
                boolean challenge = false;
                try {
                    String html = read(response);
                    challenge = LastFmWebFetcher.isChallenge(html);
                    debug("okhttp: HTTP " + response.code() + ", " + (html == null ? 0 : html.length()) + " chars, "
                            + (System.currentTimeMillis() - startedAt) + " ms" + (challenge ? ", CHALLENGE" : ""));
                    if (!challenge) {
                        track = parse(html);
                        debug("parse: " + describe(track));
                        if (track == null) {
                            debug("html: " + hint(html));
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    debug("okhttp: " + e);
                } finally {
                    response.close();
                }
                if (challenge) {
                    debug("okhttp: falling back to webview");
                    requestWeb(nick);
                    return;
                }
                deliver(nick, track, true);
            }
        });
    }

    private static void requestWeb(String nick) {
        requestWeb(nick, 0);
    }

    private static void requestWeb(String nick, int attempt) {
        String path;
        try {
            path = String.format(Locale.US, PATH, URLEncoder.encode(nick, "UTF-8"));
        } catch (Throwable e) {
            deliver(nick, null, false);
            return;
        }
        LastFmWebFetcher.fetch(path, (html, error) -> {
            if (error != null) {
                debug("web: error " + error);
                deliver(nick, null, true);
                return;
            }
            Track track = null;
            try {
                track = parse(html);
            } catch (Throwable e) {
                FileLog.e(e);
                debug("parse: " + e);
            }
            debug("parse: " + describe(track));
            if (track == null) {
                debug("html: " + hint(html));
                if (attempt == 0 && html != null && html.contains("Temporarily Unavailable")) {
                    debug("web: retrying after unavailable page");
                    AndroidUtilities.runOnUIThread(() -> requestWeb(nick, 1), 1500);
                    return;
                }
            }
            deliver(nick, track, true);
        });
    }

    private static String hint(String html) {
        if (html == null) {
            return "empty";
        }
        Matcher title = Pattern.compile("<title>([^<]*)</title>").matcher(html);
        String head = html.substring(0, Math.min(160, html.length())).replaceAll("\\s+", " ");
        return "rows=" + (html.contains("chartlist-row") ? "yes" : "no")
                + ", title=" + (title.find() ? title.group(1).trim() : "-")
                + ", head=" + head;
    }

    private static String describe(Track track) {
        if (track == null) {
            return "no track";
        }
        return (track.live ? "LIVE " : "last ") + track.artist + " — " + track.name
                + (track.album != null ? " [" + track.album + "]" : "")
                + (track.coverUrl != null ? " +cover" : "");
    }

    private static void deliver(String nick, Track track, boolean remember) {
        ArrayList<Callback> waiting;
        synchronized (CACHE) {
            waiting = WAITING.remove(nick);
            if (remember) {
                CACHE.put(nick, track);
                STAMPS.put(nick, System.currentTimeMillis());
            }
        }
        if (waiting == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (Callback callback : waiting) {
                callback.onResult(nick, track);
            }
        });
    }

    private static OkHttpClient client() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
                    .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                    .callTimeout(CALL_TIMEOUT, TimeUnit.MILLISECONDS)
                    .build();
        }
        return client;
    }

    private static Request requestFor(String nick) throws Exception {
        Request.Builder builder = new Request.Builder()
                .url(String.format(Locale.US, URL, URLEncoder.encode(nick, "UTF-8")))
                .header("User-Agent", LastFmWebFetcher.userAgent());
        String cookies = LastFmWebFetcher.cookies();
        if (!TextUtils.isEmpty(cookies)) {
            builder.header("Cookie", cookies);
        }
        return builder.build();
    }

    private static String read(Response response) throws Exception {
        {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            try (InputStream stream = response.body().byteStream();
                 InputStreamReader reader = new InputStreamReader(stream, "UTF-8")) {
                char[] buffer = new char[8192];
                StringBuilder sb = new StringBuilder();
                int read;
                while (sb.length() < PREFIX_LIMIT && (read = reader.read(buffer)) > 0) {
                    sb.append(buffer, 0, read);
                    int rowAt = sb.indexOf("chartlist-row--");
                    if (rowAt >= 0 && sb.indexOf("</tr>", rowAt) >= 0) {
                        break;
                    }
                }
                return sb.toString();
            }
        }
    }

    private static Track parse(String html) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        Matcher rowMatcher = ROW.matcher(html);
        if (!rowMatcher.find()) {
            return null;
        }
        int start = rowMatcher.start();
        int end = html.indexOf("</tr>", start);
        String row = end > start ? html.substring(start, end) : html.substring(start);

        Track track = new Track();
        track.live = row.contains("chartlist-row--now-scrobbling") || row.contains("chartlist-now-scrobbling");
        track.name = unescape(group(TRACK, row));
        track.artist = unescape(group(ARTIST, row));
        track.trackUrl = group(TRACK_URL, row);
        String cover = group(COVER, row);
        track.coverUrl = cover != null ? cover.replace("/64s/", "/300x300/") : null;
        track.album = decodeSegment(group(ALBUM, row));
        if (TextUtils.isEmpty(track.name) || TextUtils.isEmpty(track.artist)) {
            return null;
        }
        return track;
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String decodeSegment(String segment) {
        if (TextUtils.isEmpty(segment)) {
            return null;
        }
        try {
            return URLDecoder.decode(segment.replace("+", " "), "UTF-8");
        } catch (Throwable e) {
            return null;
        }
    }

    private static String unescape(String text) {
        if (text == null) {
            return null;
        }
        return text
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
