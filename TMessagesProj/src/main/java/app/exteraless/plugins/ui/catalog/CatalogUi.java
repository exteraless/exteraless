package app.exteraless.plugins.ui.catalog;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CombinedDrawable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import app.exteraless.plugins.Plugin;
import app.exteraless.plugins.PluginsController;
import app.exteraless.plugins.catalog.CatalogCategory;
import app.exteraless.plugins.catalog.CatalogConfig;
import app.exteraless.plugins.catalog.CatalogException;
import app.exteraless.plugins.catalog.CatalogPlugin;
import app.exteraless.plugins.catalog.CatalogUpdateMatch;

final class CatalogUi {

    private CatalogUi() {
    }

    static String categoryEmoji(String icon, String slug) {
        String normalizedIcon = icon == null ? "" : icon.trim();
        if (Emoji.isValidEmoji(normalizedIcon)) {
            return normalizedIcon;
        }
        if (!normalizedIcon.isEmpty()) {
            switch (normalizedIcon.toLowerCase(Locale.ROOT)) {
                case "camera": return "📸";
                case "code": return "💻";
                case "eye-off": return "🕶️";
                case "file-text": return "📄";
                case "globe": return "🌐";
                case "heart": return "❤️";
                case "image": return "🎬";
                case "lock":
                case "shield": return "🔐";
                case "message-square": return "💬";
                case "music": return "🎵";
                case "palette": return "🎨";
                case "rocket": return "🚀";
                case "settings": return "⚙️";
                case "sliders": return "🎛️";
                case "users": return "👥";
                case "zap": return "⚡";
            }
        }
        String normalizedSlug = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        switch (normalizedSlug) {
            case "automation": return "⚡";
            case "bots-automation": return "🤖";
            case "communication": return "💬";
            case "customization": return "🎛️";
            case "development": return "💻";
            case "favorites": return "❤️";
            case "fun": return "🤪";
            case "integrations": return "🌐";
            case "media": return "🎬";
            case "music": return "🎵";
            case "photography": return "📸";
            case "privacy": return "🕶️";
            case "productivity": return "🚀";
            case "security": return "🔐";
            case "social": return "👥";
            case "stickers": return "✨";
            case "tools": return "🛠️";
            case "ui": return "🎨";
            case "utility": return "🧰";
            default: return "📦";
        }
    }

    static String categoryTitle(CatalogCategory category, String fallback) {
        String name = category.name;
        if (TextUtils.isEmpty(name)) {
            name = humanizeSlug(category.slug);
        }
        return TextUtils.isEmpty(name) ? fallback : name;
    }

    static String humanizeSlug(String slug) {
        String readable = slug == null ? ""
                : slug.replace('_', ' ').replace('-', ' ').trim();
        if (readable.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(readable.charAt(0)) + readable.substring(1);
    }

    private static android.graphics.Paint.FontMetricsInt emojiFontMetrics;

    static CharSequence renderEmoji(String value) {
        if (emojiFontMetrics == null) {
            TextPaint paint = new TextPaint();
            paint.setTextSize(AndroidUtilities.dp(14));
            emojiFontMetrics = paint.getFontMetricsInt();
        }
        return Emoji.replaceEmoji(value, emojiFontMetrics, false);
    }

    static String firstTrustedScreenshot(CatalogPlugin plugin) {
        for (String screenshot : plugin.screenshots) {
            if (CatalogConfig.isTrustedOfficialMediaUrl(screenshot)) {
                return screenshot;
            }
        }
        return null;
    }

    static boolean isInstalled(CatalogUpdateMatch match,
                               boolean showUpdates) {
        return match.state == CatalogUpdateMatch.State.UP_TO_DATE
                || match.state == CatalogUpdateMatch.State.LOCAL_NEWER
                || (!showUpdates && match.state
                        == CatalogUpdateMatch.State.UPDATE_AVAILABLE);
    }

    static boolean isUpdate(CatalogUpdateMatch match,
                            boolean showUpdates) {
        return showUpdates && match.state
                == CatalogUpdateMatch.State.UPDATE_AVAILABLE;
    }

    static CharSequence errorText(CatalogException failure) {
        switch (failure.kind) {
            case INTEGRITY: return getString(R.string.PluginCatalogIntegrityError);
            case CONFIGURATION: return getString(R.string.PluginCatalogConfigurationError);
            case NETWORK: return getString(R.string.PluginCatalogNetworkError);
            default: return getString(R.string.PluginCatalogGenericError);
        }
    }

    static Map<String, String> installedVersions() {
        Map<String, String> result = new HashMap<>();
        for (Plugin local : PluginsController.getInstance().getPluginsSnapshot()) {
            if (!TextUtils.isEmpty(local.id)) {
                result.put(local.id, local.getVersion());
            }
        }
        return result;
    }

    static int checkTone(CatalogPlugin.AiCheck check) {
        if (check == null) return 0;
        String status = check.status == null ? ""
                : check.status.trim().toLowerCase(Locale.ROOT);
        if ("failed".equals(status) || "error".equals(status) || "pending".equals(status)
                || "queued".equals(status) || "running".equals(status)
                || "review".equals(status)) {
            return 0;
        }
        String classification = check.classification == null ? ""
                : check.classification.trim().toLowerCase(Locale.ROOT);
        switch (classification) {
            case "safe":
            case "secure":
            case "trusted":
            case "verified":
            case "optimal":
            case "good":
            case "ok":
                return 1;
            case "unsafe":
            case "malicious":
            case "risky":
            case "suspicious":
            case "critical":
            case "poor":
            case "slow":
                return -1;
            default:
                return 0;
        }
    }

    static CharSequence checkVerdict(CatalogPlugin.AiCheck check, boolean performance) {
        switch (checkTone(check)) {
            case 1:
                return getString(performance ? R.string.PluginCatalogPerfGood
                        : R.string.PluginCatalogSecuritySafe);
            case -1:
                return getString(performance ? R.string.PluginCatalogPerfIssues
                        : R.string.PluginCatalogSecurityRisk);
            default:
                return getString(R.string.PluginCatalogSecurityNeedsReview);
        }
    }

    static int overallTone(CatalogPlugin plugin) {
        int tone = checkTone(plugin.securityCheck);
        if (plugin.performanceCheck != null) {
            tone = Math.min(tone, checkTone(plugin.performanceCheck));
        }
        String summary = normalizedSummary(plugin);
        if ("critical".equals(summary)) {
            tone = -1;
        } else if ("issues".equals(summary) && plugin.performanceCheck != null) {
            tone = Math.min(tone, 0);
        }
        return tone;
    }

    static int toneColor(int tone, Theme.ResourcesProvider resourcesProvider) {
        return Theme.getColor(tone > 0 ? Theme.key_windowBackgroundWhiteGreenText
                : tone < 0 ? Theme.key_text_RedRegular
                : Theme.key_statisticChartLine_orange, resourcesProvider);
    }

    static CharSequence overallLabel(CatalogPlugin plugin) {
        int tone = overallTone(plugin);
        if (tone > 0) return getString(R.string.PluginCatalogCheckSummaryOk);
        if (tone < 0) return getString(R.string.PluginCatalogCheckSummaryCritical);
        if (plugin.performanceCheck != null
                && "issues".equals(normalizedSummary(plugin))) {
            return getString(R.string.PluginCatalogCheckSummaryIssues);
        }
        return getString(R.string.PluginCatalogSecurityNeedsReview);
    }

    private static String normalizedSummary(CatalogPlugin plugin) {
        return plugin.checkSummary == null ? ""
                : plugin.checkSummary.trim().toLowerCase(Locale.ROOT);
    }

    static void applyRating(android.widget.ImageView icon, android.widget.TextView text,
                            CatalogPlugin plugin, CharSequence value,
                            Theme.ResourcesProvider resourcesProvider) {
        boolean rated = plugin.ratingCount > 0;
        if (rated && plugin.rating >= 4.95) {
            icon.setVisibility(android.view.View.GONE);
            text.setText(renderEmoji("🌟 " + value));
        } else if (rated) {
            icon.setVisibility(android.view.View.VISIBLE);
            icon.setColorFilter(null);
            icon.setImageResource(R.drawable.star_small_inner);
            text.setText(value);
        } else {
            icon.setVisibility(android.view.View.VISIBLE);
            icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(
                    Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider),
                    PorterDuff.Mode.SRC_IN));
            icon.setImageResource(R.drawable.ic_rating_star);
            text.setText(value);
        }
        text.setTypeface(rated ? AndroidUtilities.bold()
                : android.graphics.Typeface.DEFAULT);
    }

    static Drawable verifiedBadge(Context context, Theme.ResourcesProvider resourcesProvider) {
        Drawable background = context.getResources()
                .getDrawable(R.drawable.verified_area).mutate();
        background.setColorFilter(new PorterDuffColorFilter(Theme.getColor(
                Theme.key_chats_verifiedBackground, resourcesProvider),
                PorterDuff.Mode.MULTIPLY));
        Drawable check = context.getResources()
                .getDrawable(R.drawable.verified_check).mutate();
        check.setColorFilter(new PorterDuffColorFilter(Theme.getColor(
                Theme.key_chats_verifiedCheck, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        CombinedDrawable drawable = new CombinedDrawable(background, check);
        int size = AndroidUtilities.dp(17);
        drawable.setBounds(0, 0, size, size);
        drawable.setCustomSize(size, size);
        return drawable;
    }
}
