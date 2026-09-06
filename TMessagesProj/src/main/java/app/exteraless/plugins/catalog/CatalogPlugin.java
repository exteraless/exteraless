package app.exteraless.plugins.catalog;

import org.json.JSONObject;

import java.net.URI;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Store plugin metadata. It intentionally does not claim to know the runtime __id__. */
public final class CatalogPlugin {
    public final long id;
    public final String name;
    public final String slug;
    public final String description;
    public final String shortDescription;
    public final String version;
    public final String author;
    public final String authorId;
    public final String authorImage;
    public final String category;
    public final List<String> tags;
    public final long downloadCount;
    public final double rating;
    public final long ratingCount;
    public final boolean featured;
    public final boolean verified;
    public final String telegramBotDeeplink;
    public final String githubUrl;
    public final String documentationUrl;
    public final List<String> screenshots;
    public final String requirements;
    public final String changelog;
    public final String minExteraVersion;
    /** true = compatible, false = explicitly unsupported, null = unknown. */
    public final Boolean exteralessCompatible;
    public final String minExteralessVersion;
    public final long createdAt;
    public final Long updatedAt;
    public final AiCheck securityCheck;
    public final AiCheck performanceCheck;
    public final String checkSummary;
    public final String contentLocale;
    public final String localizedLocale;

    public static final class AiCheck {
        public final String status;
        public final String classification;
        public final int score;
        public final String shortDescription;
        public final String model;
        public final List<String> issues;

        AiCheck(JSONObject json) throws CatalogException {
            status = CatalogJson.nullableString(json, "status", 128);
            classification = CatalogJson.nullableString(json, "classification", 128);
            int value = json.optInt("score", -1);
            score = value < 0 || value > 100 ? -1 : value;
            shortDescription = CatalogJson.nullableString(json, "shortDescription", 4_000);
            model = CatalogJson.nullableString(json, "llmModel", 256);
            issues = parseIssues(json);
        }

        private static List<String> parseIssues(JSONObject json) {
            org.json.JSONArray array = json.optJSONArray("issues");
            if (array == null) {
                Object rawDetails = json.opt("details");
                JSONObject details = rawDetails instanceof JSONObject
                        ? (JSONObject) rawDetails : null;
                if (details == null && rawDetails instanceof String) {
                    try {
                        details = new JSONObject((String) rawDetails);
                    } catch (Exception ignored) {
                    }
                }
                array = details == null ? null : details.optJSONArray("issues");
            }
            if (array == null) {
                return Collections.emptyList();
            }
            ArrayList<String> result = new ArrayList<>();
            for (int i = 0; i < array.length() && result.size() < 20; i++) {
                Object item = array.opt(i);
                String text = null;
                if (item instanceof String) {
                    text = (String) item;
                } else if (item instanceof JSONObject) {
                    JSONObject issue = (JSONObject) item;
                    for (String key : new String[] {
                            "title", "description", "message", "issue", "text"}) {
                        String candidate = issue.optString(key, "");
                        if (!candidate.isEmpty()) {
                            text = candidate;
                            break;
                        }
                    }
                }
                if (text == null) continue;
                text = text.trim();
                if (text.isEmpty()) continue;
                if (text.length() > 500) text = text.substring(0, 500) + "…";
                result.add(text);
            }
            return immutable(result);
        }
    }

    CatalogPlugin(JSONObject json) throws CatalogException {
        id = CatalogJson.requiredPositiveLong(json, "id");
        name = CatalogJson.requiredString(json, "name", 512);
        slug = CatalogJson.requiredString(json, "slug", 512);
        description = CatalogJson.string(json, "description", "", 200_000);
        shortDescription = CatalogJson.nullableString(json, "shortDescription", 10_000);
        version = CatalogJson.requiredString(json, "version", 256);
        author = CatalogJson.string(json, "author", "", 512);
        authorId = safePositiveId(CatalogJson.nullableString(json, "authorId", 64));
        authorImage = safeHttpsUrl(CatalogJson.nullableString(json, "authorImage", 4_096));
        category = CatalogJson.string(json, "category", "", 256);
        tags = immutable(CatalogJson.stringList(json.opt("tags"), 100, 256));
        downloadCount = CatalogJson.nonNegativeLong(json, "downloadCount", 0);
        rating = CatalogJson.finiteDouble(json, "rating", 0);
        ratingCount = CatalogJson.nonNegativeLong(json, "ratingCount", 0);
        featured = json.optBoolean("featured", false);
        verified = json.optBoolean("verified", false);
        telegramBotDeeplink = CatalogJson.nullableString(json, "telegramBotDeeplink", 4_096);
        githubUrl = safeHttpsUrl(CatalogJson.nullableString(json, "githubUrl", 4_096));
        documentationUrl = safeHttpsUrl(
                CatalogJson.nullableString(json, "documentationUrl", 4_096));
        screenshots = safeHttpsUrls(CatalogJson.stringList(json.opt("screenshots"), 50, 4_096));
        requirements = CatalogJson.nullableString(json, "requirements", 50_000);
        changelog = CatalogJson.nullableString(json, "changelog", 200_000);
        minExteraVersion = CatalogJson.nullableString(json, "minExteraVersion", 256);
        exteralessCompatible = CatalogJson.nullableBoolean(json, "exteralessCompatible");
        minExteralessVersion = CatalogJson.nullableString(json, "minExteralessVersion", 256);
        createdAt = CatalogJson.nonNegativeLong(json, "createdAt", 0);
        updatedAt = CatalogJson.nullableLong(json, "updatedAt");
        JSONObject security = json.optJSONObject("latestSecurityCheck");
        securityCheck = security == null ? null : new AiCheck(security);
        JSONObject performance = json.optJSONObject("latestPerformanceCheck");
        performanceCheck = performance == null ? null : new AiCheck(performance);
        checkSummary = CatalogJson.nullableString(json, "checkSummary", 32);
        contentLocale = CatalogJson.nullableString(json, "contentLocale", 16);
        localizedLocale = CatalogJson.nullableString(json, "localizedLocale", 16);
    }

    private static List<String> immutable(List<String> source) {
        return Collections.unmodifiableList(source);
    }

    private static List<String> safeHttpsUrls(List<String> source) {
        List<String> result = new ArrayList<>(source.size());
        for (String value : source) {
            String safe = safeHttpsUrl(value);
            if (safe != null) {
                result.add(safe);
            }
        }
        return immutable(result);
    }

    private static String safeHttpsUrl(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !"https".equals(scheme.toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getRawUserInfo() != null) {
                return null;
            }
            return uri.toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safePositiveId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 20) {
            return null;
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (!Character.isDigit(normalized.charAt(i))) {
                return null;
            }
        }
        try {
            return Long.parseLong(normalized) > 0 ? normalized : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
