package app.exteraless.plugins.ui.components;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.exteraless.ai.data.Role;
import app.exteraless.ai.ui.AiResponseSheet;
import app.exteraless.plugins.Plugin;
import app.exteraless.plugins.PluginCapabilityScan;

public final class PluginAiReview {

    private static final int MAX_SOURCE = 48 * 1024;
    private static final long CACHE_TTL = 5 * 60 * 1000L;

    private static final Map<String, Cached> CACHE = new HashMap<>();

    private static final class Cached {

        final String answer;
        final long time;

        Cached(String answer, long time) {
            this.answer = answer;
            this.time = time;
        }
    }

    private PluginAiReview() {
    }

    public static boolean canReview(File file) {
        return PluginFileViewer.canOpen(file);
    }

    public static boolean isCached(File file) {
        return file != null && !TextUtils.isEmpty(cached(cacheKey(file)));
    }

    public static void review(Context context, Theme.ResourcesProvider resourcesProvider,
                              File file, Plugin plugin, Map<String, List<String>> capabilities) {
        String source = PluginFileViewer.readSource(file);
        if (context == null || TextUtils.isEmpty(source)) {
            return;
        }
        if (source.length() > MAX_SOURCE) {
            source = source.substring(0, MAX_SOURCE);
        }
        Role role = new Role(getString(R.string.PluginsAiReview),
                getString(R.string.PluginsAiReviewRole));
        String key = cacheKey(file);
        AiResponseSheet.show(context, resourcesProvider,
                buildPrompt(plugin, capabilities, source), false, null, role, false,
                cached(key), answer -> remember(key, answer));
    }

    private static String cacheKey(File file) {
        return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified()
                + "|" + language();
    }

    private static synchronized String cached(String key) {
        forget();
        Cached entry = CACHE.get(key);
        return entry == null ? null : entry.answer;
    }

    private static synchronized void remember(String key, String answer) {
        forget();
        if (!TextUtils.isEmpty(answer)) {
            CACHE.put(key, new Cached(answer, System.currentTimeMillis()));
        }
    }

    private static void forget() {
        long now = System.currentTimeMillis();
        Iterator<Cached> iterator = CACHE.values().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().time > CACHE_TTL) {
                iterator.remove();
            }
        }
    }

    private static String language() {
        try {
            String name = LocaleController.getInstance().getCurrentLocale()
                    .getDisplayLanguage(Locale.ENGLISH);
            return TextUtils.isEmpty(name) ? "English" : name;
        } catch (Exception e) {
            return "English";
        }
    }

    private static String buildPrompt(Plugin plugin, Map<String, List<String>> capabilities,
                                      String source) {
        StringBuilder facts = new StringBuilder();
        if (plugin != null) {
            facts.append(plugin.name == null ? plugin.id : plugin.name);
            if (!TextUtils.isEmpty(plugin.version)) {
                facts.append(" ").append(plugin.version);
            }
            if (!TextUtils.isEmpty(plugin.author)) {
                facts.append(" — ").append(plugin.author);
            }
        }
        List<String> found = PluginCapabilityScan.ordered(capabilities);
        if (found != null && !found.isEmpty()) {
            facts.append("\n").append(TextUtils.join(", ", found));
        }
        return LocaleController.formatString(R.string.PluginsAiReviewPrompt,
                facts.toString(), language(), source);
    }
}
