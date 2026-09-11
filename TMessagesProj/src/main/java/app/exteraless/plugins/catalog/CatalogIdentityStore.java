package app.exteraless.plugins.catalog;

import android.content.Context;
import android.content.SharedPreferences;

import app.exteraless.plugins.PluginsConstants;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/** Persists source origin + catalog slug -> locally parsed runtime plugin ID. */
public final class CatalogIdentityStore {
    private static final String KEY_PREFIX = "catalog_identity_";
    private final SharedPreferences preferences;

    public CatalogIdentityStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PluginsConstants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Call only after the installed local file's metadata parser has returned pluginId.
     * Store slugs and display names are never treated as runtime IDs.
     */
    public void recordLocalIdentity(String sourceUrl, String catalogSlug, String pluginId) {
        validateSlug(catalogSlug);
        validateLocalId(pluginId);
        preferences.edit().putString(key(sourceUrl, catalogSlug), pluginId).apply();
    }

    /** Convenience gate for a core-verified direct download followed by local metadata parsing. */
    public void recordVerifiedDownloadIdentity(CatalogDownload download, String locallyParsedId) {
        if (download == null || !download.verified
                || download.kind != CatalogDownload.Kind.VERIFIED_FILE) {
            throw new IllegalArgumentException("A verified direct download is required");
        }
        recordLocalIdentity(download.sourceUrl, download.pluginSlug, locallyParsedId);
    }

    public String getLocalPluginId(String sourceUrl, String catalogSlug) {
        validateSlug(catalogSlug);
        return preferences.getString(key(sourceUrl, catalogSlug), null);
    }

    public void clearLocalIdentity(String sourceUrl, String catalogSlug) {
        validateSlug(catalogSlug);
        preferences.edit().remove(key(sourceUrl, catalogSlug)).apply();
    }

    /** Uninstall hook: a removed plugin id must not keep matching catalog slugs. */
    public void clearIdentitiesForLocalId(String pluginId) {
        if (pluginId == null || pluginId.isEmpty()) {
            return;
        }
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(KEY_PREFIX)
                    && pluginId.equals(entry.getValue())) {
                if (editor == null) editor = preferences.edit();
                editor.remove(entry.getKey());
            }
        }
        if (editor != null) editor.apply();
    }

    public CatalogUpdateMatch matchUpdate(String sourceUrl, CatalogPlugin catalogPlugin,
                                          Map<String, String> installedVersionsByPluginId) {
        String pluginId = getLocalPluginId(sourceUrl, catalogPlugin.slug);
        if (pluginId == null) {
            return new CatalogUpdateMatch(CatalogUpdateMatch.State.UNKNOWN_IDENTITY, null,
                    null, catalogPlugin.version);
        }
        String installed = installedVersionsByPluginId == null ? null
                : installedVersionsByPluginId.get(pluginId);
        if (installed == null || installed.trim().isEmpty()) {
            return new CatalogUpdateMatch(CatalogUpdateMatch.State.INSTALLED_VERSION_UNKNOWN,
                    pluginId, null, catalogPlugin.version);
        }
        int comparison = CatalogVersions.compare(catalogPlugin.version, installed);
        CatalogUpdateMatch.State state = comparison > 0
                ? CatalogUpdateMatch.State.UPDATE_AVAILABLE
                : comparison < 0 ? CatalogUpdateMatch.State.LOCAL_NEWER
                : CatalogUpdateMatch.State.UP_TO_DATE;
        return new CatalogUpdateMatch(state, pluginId, installed, catalogPlugin.version);
    }

    private static String key(String sourceUrl, String slug) {
        String source = CatalogConfig.normalizeBaseUrl(sourceUrl, true);
        byte[] bytes = (source + "\n" + slug).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder(KEY_PREFIX);
            for (byte item : digest) {
                int value = item & 0xff;
                result.append(hex[value >>> 4]).append(hex[value & 0x0f]);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is unavailable", e);
        }
    }

    private static void validateSlug(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 512) {
            throw new IllegalArgumentException("Invalid catalog slug");
        }
    }

    private static void validateLocalId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{2,32}")) {
            throw new IllegalArgumentException("Invalid locally parsed plugin ID");
        }
    }
}
