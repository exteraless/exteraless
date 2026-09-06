package app.exteraless.plugins.catalog;

import android.content.Context;
import android.content.SharedPreferences;

import app.exteraless.plugins.PluginsConstants;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** User-configurable catalog policy stored in exteraless_plugins preferences. */
public final class CatalogConfig {
    public static final String DEFAULT_BASE_URL = "https://exterastore.app";
    public static final long DEFAULT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    public static boolean isTrustedOfficialMediaUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) {
                return false;
            }
            String host = uri.getHost();
            return "t.me".equalsIgnoreCase(host)
                    || "exteragram-plugins.storage.yandexcloud.net".equalsIgnoreCase(host);
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private static final String KEY_BASE_URL = "catalog_base_url";
    private static final String KEY_ALLOW_INSECURE = "catalog_allow_insecure";
    private static final String KEY_ALLOW_LOCAL = "catalog_allow_local_sources";
    private static final String KEY_COMPATIBLE_ONLY = "catalog_exteraless_only";
    private static final String KEY_CACHE_MAX_AGE = "catalog_cache_max_age_ms";

    private final SharedPreferences preferences;

    public CatalogConfig(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PluginsConstants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getBaseUrl() {
        String stored = preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
        try {
            return normalizeBaseUrl(stored, isInsecureAllowed());
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_BASE_URL;
        }
    }

    public void setBaseUrl(String value) {
        String normalized = normalizeBaseUrl(value, isInsecureAllowed());
        preferences.edit().putString(KEY_BASE_URL, normalized).apply();
    }

    public void resetBaseUrl() {
        preferences.edit().remove(KEY_BASE_URL).apply();
    }

    /** Insecure HTTP can only be enabled while the app's developer mode is already on. */
    public void setAllowInsecure(boolean enabled, boolean developerMode) {
        if (enabled && !developerMode) {
            throw new IllegalStateException("Developer mode is required for insecure catalogs");
        }
        SharedPreferences.Editor editor = preferences.edit().putBoolean(KEY_ALLOW_INSECURE, enabled);
        if (!enabled) {
            String stored = preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
            if (stored != null && stored.toLowerCase(Locale.ROOT).startsWith("http://")) {
                editor.remove(KEY_BASE_URL);
            }
        }
        editor.apply();
    }

    public boolean isInsecureAllowed() {
        return preferences.getBoolean(KEY_ALLOW_INSECURE, false)
                && preferences.getBoolean(PluginsConstants.KEY_DEVELOPER_MODE, false);
    }

    /** Local/private network sources are a separate developer-only capability. */
    public void setAllowLocalSources(boolean enabled, boolean developerMode) {
        if (enabled && !developerMode) {
            throw new IllegalStateException("Developer mode is required for local catalogs");
        }
        preferences.edit().putBoolean(KEY_ALLOW_LOCAL, enabled).apply();
    }

    public boolean isLocalSourcesAllowed() {
        return preferences.getBoolean(KEY_ALLOW_LOCAL, false)
                && preferences.getBoolean(PluginsConstants.KEY_DEVELOPER_MODE, false);
    }

    /** Defaults to false: null compatibility metadata must remain visible as unknown. */
    public boolean isCompatibleOnly() {
        return preferences.getBoolean(KEY_COMPATIBLE_ONLY, false);
    }

    public void setCompatibleOnly(boolean value) {
        preferences.edit().putBoolean(KEY_COMPATIBLE_ONLY, value).apply();
    }

    public long getCacheMaxAgeMs() {
        return Math.max(60_000L, preferences.getLong(KEY_CACHE_MAX_AGE,
                DEFAULT_CACHE_MAX_AGE_MS));
    }

    public void setCacheMaxAgeMs(long value) {
        if (value < 60_000L || value > 30L * 24L * 60L * 60L * 1000L) {
            throw new IllegalArgumentException("Cache max age must be between 1 minute and 30 days");
        }
        preferences.edit().putLong(KEY_CACHE_MAX_AGE, value).apply();
    }

    public static String normalizeBaseUrl(String value, boolean allowInsecure) {
        if (value == null) throw new IllegalArgumentException("Catalog URL is required");
        value = value.trim();
        if (value.isEmpty() || value.length() > 2_048) {
            throw new IllegalArgumentException("Invalid catalog URL");
        }
        final URI parsed;
        try {
            parsed = new URI(value).normalize();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid catalog URL", e);
        }
        String scheme = parsed.getScheme();
        if (scheme == null) throw new IllegalArgumentException("Catalog URL needs a scheme");
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || (allowInsecure && "http".equals(scheme)))) {
            throw new IllegalArgumentException("Catalog URL must use HTTPS");
        }
        if (parsed.getRawUserInfo() != null || parsed.getRawFragment() != null
                || parsed.getRawQuery() != null) {
            throw new IllegalArgumentException("Credentials, query and fragment are not allowed");
        }
        String host = parsed.getHost();
        if (host == null || host.isEmpty() || host.endsWith(".") || host.contains("%")) {
            throw new IllegalArgumentException("Catalog URL needs a valid host");
        }
        String path = parsed.getRawPath();
        if (path == null || "/".equals(path)) path = "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        try {
            return new URI(scheme, null, host.toLowerCase(Locale.ROOT), parsed.getPort(),
                    path, null, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid catalog URL", e);
        }
    }
}
