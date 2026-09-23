package app.exteraless.nowplaying;

import android.annotation.SuppressLint;
import android.os.SystemClock;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;

public final class LastFmWebFetcher {

    public interface Result {
        void onResult(String html, String error);
    }

    private static final String ORIGIN = "https://www.last.fm";
    private static final String HOME = ORIGIN + "/";
    private static final long PAGE_TIMEOUT = 25_000L;
    private static final long IDLE_RELEASE = 180_000L;

    private static final class Pending {
        final String path;
        final Result result;

        Pending(String path, Result result) {
            this.path = path;
            this.result = result;
        }
    }

    private static volatile String userAgent;
    private static WebView webView;
    private static boolean passed;
    private static Pending current;
    private static long startedAt;
    private static final ArrayList<Pending> queue = new ArrayList<>();
    private static final Runnable timeout = LastFmWebFetcher::onTimeout;
    private static final Runnable release = LastFmWebFetcher::release;

    private LastFmWebFetcher() {
    }

    public static boolean isChallenge(String html) {
        return html != null && (html.contains("<title>Client Challenge</title>") || html.contains("/_fs-ch-"));
    }

    public static void fetch(String path, Result result) {
        AndroidUtilities.runOnUIThread(() -> {
            queue.add(new Pending(path, result));
            scheduleRelease();
            next();
        });
    }

    public static WebView debugWebView() {
        WebView view = ensureWebView();
        if (current == null && TextUtils.isEmpty(view.getUrl())) {
            view.loadUrl(HOME);
        }
        scheduleRelease();
        return view;
    }

    public static String userAgent() {
        String agent = userAgent;
        if (agent == null) {
            try {
                agent = WebSettings.getDefaultUserAgent(ApplicationLoader.applicationContext)
                        .replace("; wv)", ")").replace(" Version/4.0", "");
            } catch (Throwable e) {
                agent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
            }
            userAgent = agent;
        }
        return agent;
    }

    public static String cookies() {
        try {
            return CookieManager.getInstance().getCookie(ORIGIN);
        } catch (Throwable e) {
            return null;
        }
    }

    public static boolean isReady() {
        return passed;
    }

    public static String cookieSummary() {
        String cookies = CookieManager.getInstance().getCookie(ORIGIN);
        if (TextUtils.isEmpty(cookies)) {
            return "none";
        }
        StringBuilder names = new StringBuilder();
        for (String part : cookies.split(";")) {
            String name = part.trim();
            int eq = name.indexOf('=');
            if (eq > 0) {
                name = name.substring(0, eq);
            }
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(name);
        }
        return names.toString();
    }

    public static void resetCookies() {
        AndroidUtilities.runOnUIThread(() -> {
            CookieManager manager = CookieManager.getInstance();
            String cookies = manager.getCookie(ORIGIN);
            if (!TextUtils.isEmpty(cookies)) {
                for (String part : cookies.split(";")) {
                    String name = part.trim();
                    int eq = name.indexOf('=');
                    if (eq > 0) {
                        name = name.substring(0, eq);
                    }
                    manager.setCookie(ORIGIN, name + "=; Max-Age=0; Path=/");
                    manager.setCookie(ORIGIN, name + "=; Max-Age=0; Path=/; Domain=.last.fm");
                    manager.setCookie(ORIGIN, name + "=; Max-Age=0; Path=/; Domain=last.fm");
                }
            }
            manager.flush();
            passed = false;
            LastFmNowPlaying.debug("web: cookies reset, left: " + cookieSummary());
        });
    }

    private static void next() {
        if (current != null || queue.isEmpty()) {
            return;
        }
        current = queue.remove(0);
        startedAt = SystemClock.elapsedRealtime();
        WebView view = ensureWebView();
        LastFmNowPlaying.debug("web: open " + current.path + ", cookies: " + cookieSummary());
        AndroidUtilities.cancelRunOnUIThread(timeout);
        AndroidUtilities.runOnUIThread(timeout, PAGE_TIMEOUT);
        view.loadUrl(ORIGIN + current.path);
    }

    private static void finish(String html, String error) {
        AndroidUtilities.cancelRunOnUIThread(timeout);
        Pending done = current;
        current = null;
        if (done != null) {
            done.result.onResult(html, error);
        }
        next();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static WebView ensureWebView() {
        if (webView != null) {
            return webView;
        }
        WebView view = new WebView(ApplicationLoader.applicationContext);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(userAgent());
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                onPageLoaded(view, url);
            }
        });
        webView = view;
        LastFmNowPlaying.debug("web: created, UA=" + settings.getUserAgentString());
        return view;
    }

    private static void onPageLoaded(WebView view, String url) {
        if (view != webView) {
            return;
        }
        view.evaluateJavascript("document.documentElement.outerHTML", value -> {
            String html = unquote(value);
            long elapsed = SystemClock.elapsedRealtime() - startedAt;
            if (isChallenge(html)) {
                passed = false;
                LastFmNowPlaying.debug("web: challenge at " + url + " (" + elapsed + " ms), waiting");
                return;
            }
            passed = true;
            LastFmNowPlaying.debug("web: loaded " + url + ", " + (html == null ? 0 : html.length())
                    + " chars, " + elapsed + " ms, cookies: " + cookieSummary());
            if (current != null && url != null && url.startsWith(ORIGIN + current.path.split("\\?")[0])) {
                finish(html, null);
            }
        });
    }

    private static void onTimeout() {
        if (current == null) {
            return;
        }
        LastFmNowPlaying.debug("web: no page after " + PAGE_TIMEOUT + " ms");
        if (webView != null) {
            webView.stopLoading();
        }
        finish(null, passed ? "page timeout" : "challenge timeout");
    }

    private static void scheduleRelease() {
        AndroidUtilities.cancelRunOnUIThread(release);
        AndroidUtilities.runOnUIThread(release, IDLE_RELEASE);
    }

    private static void release() {
        if (webView == null || current != null || !queue.isEmpty() || webView.getParent() != null) {
            if (webView != null) {
                scheduleRelease();
            }
            return;
        }
        CookieManager.getInstance().flush();
        webView.stopLoading();
        webView.destroy();
        webView = null;
        LastFmNowPlaying.debug("web: released after idle");
    }

    private static String unquote(String value) {
        if (value == null || "null".equals(value)) {
            return null;
        }
        try {
            return new JSONArray("[" + value + "]").optString(0, null);
        } catch (Throwable e) {
            return value;
        }
    }
}
