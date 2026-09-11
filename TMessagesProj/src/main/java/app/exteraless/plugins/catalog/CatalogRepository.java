package app.exteraless.plugins.catalog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Public facade for catalog browsing, cache fallback, dependency plans and verified downloads. */
public final class CatalogRepository {

    public static String appLocale() {
        try {
            String language = org.telegram.messenger.LocaleController.getInstance()
                    .getCurrentLocale().getLanguage();
            if (language == null || language.isEmpty()) return "en";
            return language.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "en";
        }
    }

    private static String categoriesFingerprint() {
        return "categories.getAll:v2:" + appLocale();
    }

    private final CatalogConfig config;
    private final CatalogIdentityStore identities;
    private final CatalogClient client;
    private final CatalogCache cache;
    private final Executor callbackExecutor;
    private final File downloadsDirectory;

    public CatalogRepository(Context context) {
        this(context, command -> new Handler(Looper.getMainLooper()).post(command));
    }

    public CatalogRepository(Context context, Executor callbackExecutor) {
        Context appContext = context.getApplicationContext();
        this.config = new CatalogConfig(appContext);
        this.identities = new CatalogIdentityStore(appContext);
        this.client = new CatalogClient(config);
        this.cache = new CatalogCache(appContext);
        this.callbackExecutor = callbackExecutor;
        this.downloadsDirectory = new File(appContext.getCacheDir(), "plugin_catalog/downloads");
    }

    public CatalogConfig getConfig() {
        return config;
    }

    public CatalogIdentityStore getIdentityStore() {
        return identities;
    }

    public CatalogQuery getDefaultQuery() {
        return CatalogQuery.firstPage().withExteralessOnly(
                config.isCompatibleOnly() ? Boolean.TRUE : null);
    }

    public CatalogCall getAll(CatalogQuery query,
                              CatalogCall.Callback<CatalogData<CatalogPage>> callback) {
        JSONObject input = queryJson(query);
        String source = config.getBaseUrl();
        String fingerprint = pageFingerprint(input);
        return client.query(source, "plugins.getAll", input, CatalogClient.MAX_JSON_BYTES,
                new CatalogClient.RawCallback() {
                    @Override
                    public void onSuccess(CatalogClient.RawResponse response) {
                        try {
                            CatalogPage page = parsePage(response.data);
                            validatePage(page, query);
                            if (query.page == 1) {
                                try { cache.write(CatalogCache.PAGE, source, fingerprint,
                                        response.rawEnvelope); }
                                catch (CatalogException ignored) {}
                            }
                            success(callback, CatalogData.network(page));
                        } catch (CatalogException error) {
                            error(callback, error);
                        }
                    }

                    @Override
                    public void onError(CatalogException networkError) {
                        if (networkError.kind == CatalogException.Kind.CANCELLED) {
                            error(callback, networkError);
                            return;
                        }
                        CatalogData<CatalogPage> fallback = cachedPage(source, fingerprint,
                                query, true);
                        if (fallback != null) success(callback, fallback);
                        else error(callback, networkError);
                    }
                });
    }

    public CatalogCall getCategories(
            CatalogCall.Callback<CatalogData<List<CatalogCategory>>> callback) {
        String source = config.getBaseUrl();
        return client.query(source, "categories.getAll", new JSONObject(),
                CatalogClient.MAX_JSON_BYTES, new CatalogClient.RawCallback() {
                    @Override
                    public void onSuccess(CatalogClient.RawResponse response) {
                        try {
                            List<CatalogCategory> categories = parseCategories(response.data);
                            try { cache.write(CatalogCache.CATEGORIES, source,
                                    categoriesFingerprint(), response.rawEnvelope); }
                            catch (CatalogException ignored) {}
                            success(callback, CatalogData.network(categories));
                        } catch (CatalogException error) {
                            error(callback, error);
                        }
                    }

                    @Override
                    public void onError(CatalogException networkError) {
                        if (networkError.kind == CatalogException.Kind.CANCELLED) {
                            error(callback, networkError);
                            return;
                        }
                        CatalogData<List<CatalogCategory>> fallback = cachedCategories(source,
                                true);
                        if (fallback != null) success(callback, fallback);
                        else error(callback, networkError);
                    }
                });
    }

    /** Validates and probes a source without persisting it or changing the active cache. */
    public CatalogCall testSource(String candidateUrl,
            CatalogCall.Callback<List<CatalogCategory>> callback) {
        final String source;
        try {
            source = CatalogConfig.normalizeBaseUrl(candidateUrl, config.isInsecureAllowed());
        } catch (IllegalArgumentException e) {
            error(callback, new CatalogException(CatalogException.Kind.CONFIGURATION,
                    e.getMessage(), e));
            return NoOpCall.INSTANCE;
        }
        return client.query(source, "categories.getAll", new JSONObject(),
                CatalogClient.MAX_JSON_BYTES,
                parsed(callback, CatalogRepository::parseCategories));
    }

    public CatalogCall getDetail(String slug, CatalogCall.Callback<CatalogPlugin> callback) {
        JSONObject input = object("slug", requireSlug(slug));
        return client.query(config.getBaseUrl(), "plugins.getBySlug", input,
                CatalogClient.MAX_JSON_BYTES, parsed(callback, data ->
                        new CatalogPlugin(requireObject(data, "plugin detail"))));
    }

    public CatalogCall getVersions(String slug,
                                   CatalogCall.Callback<List<CatalogVersion>> callback) {
        JSONObject input = object("pluginSlug", requireSlug(slug));
        return client.query(config.getBaseUrl(), "pluginVersions.getVersions", input,
                CatalogClient.MAX_JSON_BYTES, parsed(callback, CatalogRepository::parseVersions));
    }

    /** Returns the dependency order for display/consent; this method never installs anything. */
    public CatalogCall getInstallPlan(long pluginId,
            CatalogCall.Callback<List<CatalogInstallPlanItem>> callback) {
        if (pluginId <= 0) throw new IllegalArgumentException("Invalid store plugin ID");
        JSONObject input = object("pluginId", pluginId);
        return client.query(config.getBaseUrl(), "plugins.getInstallPlan", input,
                CatalogClient.MAX_JSON_BYTES,
                parsed(callback, CatalogRepository::parseInstallPlan));
    }

    /** Downloads an exact version and publishes it only after size and SHA-256 verification. */
    public CatalogCall downloadVersion(CatalogPlugin plugin, CatalogVersion version,
                                       CatalogCall.Callback<CatalogDownload> callback) {
        if (plugin == null || version == null) throw new IllegalArgumentException("Plugin/version required");
        String source = config.getBaseUrl();
        JSONObject input = new JSONObject();
        try {
            input.put("pluginSlug", requireSlug(plugin.slug));
            input.put("version", version.version);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return client.mutation(source, "pluginVersions.downloadVersion", input,
                CatalogClient.MAX_DOWNLOAD_JSON_BYTES, new CatalogClient.RawCallback() {
                    @Override
                    public void onSuccess(CatalogClient.RawResponse response) {
                        try {
                            JSONObject data = requireObject(response.data, "download");
                            CatalogDownload result = materializeDownload(source, plugin, version, data);
                            success(callback, result);
                        } catch (CatalogException error) {
                            error(callback, error);
                        }
                    }

                    @Override
                    public void onError(CatalogException failure) {
                        error(callback, failure);
                    }
                });
    }

    /** Reads only the cache entry matching this exact query and current source. */
    public CatalogData<CatalogPage> getCachedPage(CatalogQuery query, boolean allowStale) {
        JSONObject input = queryJson(query);
        return cachedPage(config.getBaseUrl(), pageFingerprint(input), query, allowStale);
    }

    private static String pageFingerprint(JSONObject input) {
        return appLocale() + ":" + input;
    }

    public CatalogData<List<CatalogCategory>> getCachedCategories(boolean allowStale) {
        return cachedCategories(config.getBaseUrl(), allowStale);
    }

    public void clearCache() {
        cache.clear();
    }

    public CatalogCacheStats getCacheStats() {
        long downloadBytes = 0;
        int downloadedFiles = 0;
        File[] files = downloadsDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    downloadBytes += Math.max(0, file.length());
                    downloadedFiles++;
                }
            }
        }
        return new CatalogCacheStats(cache.sizeBytes(), downloadBytes, downloadedFiles);
    }

    /** Clears metadata and, when requested, verified temporary artifacts in the fixed cache dir. */
    public void clearCache(boolean includeDownloads) {
        cache.clear();
        if (!includeDownloads) return;
        File[] files = downloadsDirectory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) file.delete();
        }
    }

    public CatalogUpdateMatch matchInstalled(CatalogPlugin plugin,
                                              Map<String, String> installedVersionsById) {
        return identities.matchUpdate(config.getBaseUrl(), plugin, installedVersionsById);
    }

    private CatalogData<CatalogPage> cachedPage(String source, String fingerprint,
                                                CatalogQuery query, boolean allowStale) {
        CatalogCache.Entry entry = cache.read(CatalogCache.PAGE, source, fingerprint);
        if (entry == null) return null;
        boolean stale = isStale(entry.savedAtMs);
        if (stale && !allowStale) return null;
        try {
            Object data = CatalogClient.parseEnvelope(entry.raw, 200, true);
            CatalogPage page = parsePage(data);
            validatePage(page, query);
            return CatalogData.cache(page, stale, entry.savedAtMs);
        } catch (CatalogException ignored) {
            return null;
        }
    }

    private CatalogData<List<CatalogCategory>> cachedCategories(String source,
                                                                 boolean allowStale) {
        CatalogCache.Entry entry = cache.read(CatalogCache.CATEGORIES, source,
                categoriesFingerprint());
        if (entry == null) return null;
        boolean stale = isStale(entry.savedAtMs);
        if (stale && !allowStale) return null;
        try {
            Object data = CatalogClient.parseEnvelope(entry.raw, 200, true);
            return CatalogData.cache(parseCategories(data), stale, entry.savedAtMs);
        } catch (CatalogException ignored) {
            return null;
        }
    }

    private boolean isStale(long savedAt) {
        long age = System.currentTimeMillis() - savedAt;
        return age < 0 || age > config.getCacheMaxAgeMs();
    }

    private CatalogDownload materializeDownload(String source, CatalogPlugin plugin,
                                                CatalogVersion version, JSONObject data)
            throws CatalogException {
        if (!data.optBoolean("success", false)) {
            throw new CatalogException(CatalogException.Kind.PROTOCOL,
                    "Catalog did not confirm the download");
        }
        long reportedSize = CatalogJson.nonNegativeLong(data, "fileSize", -1);
        if (reportedSize != version.fileSize) {
            throw new CatalogException(CatalogException.Kind.INTEGRITY,
                    "Catalog download size does not match the selected version");
        }
        String fileName = sanitizeFileName(CatalogJson.string(data, "fileName",
                plugin.slug + "-v" + version.version + ".plugin", 512));
        String deeplink = CatalogJson.nullableString(data, "telegramBotDeeplink", 4_096);
        String content = CatalogJson.nullableString(data, "fileContent",
                CatalogClient.MAX_PLUGIN_BYTES);
        if (content == null) {
            if (deeplink == null) {
                throw new CatalogException(CatalogException.Kind.PROTOCOL,
                        "Catalog returned neither file content nor Telegram fallback");
            }
            validateTelegramDeeplink(deeplink);
            return CatalogDownload.telegram(source, plugin.slug, version.version, deeplink,
                    fileName, version.fileSize, version.fileHash);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != version.fileSize || bytes.length > CatalogClient.MAX_PLUGIN_BYTES) {
            throw new CatalogException(CatalogException.Kind.INTEGRITY,
                    "Downloaded plugin byte size is invalid");
        }
        String actualHash = sha256(bytes);
        if (!MessageDigest.isEqual(actualHash.getBytes(StandardCharsets.US_ASCII),
                version.fileHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new CatalogException(CatalogException.Kind.INTEGRITY,
                    "Downloaded plugin SHA-256 is invalid");
        }
        if (!downloadsDirectory.exists() && !downloadsDirectory.mkdirs()
                && !downloadsDirectory.isDirectory()) {
            throw new CatalogException(CatalogException.Kind.STORAGE,
                    "Cannot create download cache");
        }
        String extension = fileName.endsWith(".py") ? ".py" : ".plugin";
        String base = sanitizeFileName(plugin.slug + "-v" + version.version);
        File target = new File(downloadsDirectory,
                base + "-" + version.fileHash.substring(0, 12) + extension);
        File staging = new File(downloadsDirectory,
                target.getName() + "." + UUID.randomUUID() + ".part");
        try {
            try (FileOutputStream output = new FileOutputStream(staging)) {
                output.write(bytes);
                output.getFD().sync();
            }
            CatalogCache.atomicMove(staging, target);
        } catch (Exception e) {
            staging.delete();
            throw new CatalogException(CatalogException.Kind.STORAGE,
                    "Cannot stage verified plugin", e);
        }
        String mime = CatalogJson.string(data, "mimeType",
                extension.equals(".py") ? "text/x-python" : "application/octet-stream", 256);
        return CatalogDownload.file(source, plugin.slug, version.version, target, fileName,
                mime, bytes.length, actualHash);
    }

    private static CatalogPage parsePage(Object wire) throws CatalogException {
        JSONObject json = requireObject(wire, "plugin page");
        JSONArray array = json.optJSONArray("plugins");
        if (array == null || array.length() > 50) throw CatalogJson.protocol("Invalid plugin page");
        ArrayList<CatalogPlugin> plugins = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) throw CatalogJson.protocol("Invalid plugin item");
            plugins.add(new CatalogPlugin(item));
        }
        long totalCount = CatalogJson.nonNegativeLong(json, "totalCount", -1);
        int totalPages = CatalogJson.nonNegativeInt(json, "totalPages", -1);
        int currentPage = CatalogJson.nonNegativeInt(json, "currentPage", -1);
        if (totalCount < 0 || totalPages < 0 || currentPage < 1) {
            throw CatalogJson.protocol("Invalid page metadata");
        }
        return new CatalogPage(plugins, totalCount, totalPages, currentPage);
    }

    private static void validatePage(CatalogPage page, CatalogQuery query)
            throws CatalogException {
        if (page.currentPage != query.page || page.plugins.size() > query.limit) {
            throw CatalogJson.protocol("Catalog page does not match the request");
        }
        long expectedPages = page.totalCount == 0 ? 0
                : 1L + (page.totalCount - 1L) / query.limit;
        boolean impossiblePage = page.totalCount == 0
                ? page.currentPage != 1
                : page.currentPage > page.totalPages;
        if (expectedPages != page.totalPages || page.plugins.size() > page.totalCount
                || impossiblePage) {
            throw CatalogJson.protocol("Inconsistent catalog pagination");
        }
    }

    private static List<CatalogCategory> parseCategories(Object wire) throws CatalogException {
        JSONArray array = requireArray(wire, "categories", 500);
        ArrayList<CatalogCategory> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) throw CatalogJson.protocol("Invalid category item");
            result.add(new CatalogCategory(item));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<CatalogVersion> parseVersions(Object wire) throws CatalogException {
        JSONArray array = requireArray(wire, "versions", 1_000);
        ArrayList<CatalogVersion> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) throw CatalogJson.protocol("Invalid version item");
            result.add(new CatalogVersion(item));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<CatalogInstallPlanItem> parseInstallPlan(Object wire)
            throws CatalogException {
        JSONArray array = requireArray(wire, "install plan", 100);
        ArrayList<CatalogInstallPlanItem> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) throw CatalogJson.protocol("Invalid install plan item");
            result.add(new CatalogInstallPlanItem(item));
        }
        return Collections.unmodifiableList(result);
    }

    private static JSONObject queryJson(CatalogQuery query) {
        if (query == null) throw new IllegalArgumentException("Query is required");
        JSONObject input = new JSONObject();
        try {
            input.put("page", query.page);
            input.put("limit", query.limit);
            input.put("sortBy", query.sort.wireValue);
            if (query.category != null) input.put("category", query.category);
            if (query.search != null) input.put("search", query.search);
            if (query.exteralessOnly != null) input.put("exteralessOnly", query.exteralessOnly);
            return input;
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private <T> CatalogClient.RawCallback parsed(CatalogCall.Callback<T> callback,
                                                 Parser<T> parser) {
        return new CatalogClient.RawCallback() {
            @Override public void onSuccess(CatalogClient.RawResponse response) {
                try { success(callback, parser.parse(response.data)); }
                catch (CatalogException e) { error(callback, e); }
            }
            @Override public void onError(CatalogException failure) { error(callback, failure); }
        };
    }

    private <T> void success(CatalogCall.Callback<T> callback, T value) {
        callbackExecutor.execute(() -> callback.onSuccess(value));
    }

    private <T> void error(CatalogCall.Callback<T> callback, CatalogException failure) {
        callbackExecutor.execute(() -> callback.onError(failure));
    }

    private static JSONObject requireObject(Object value, String what) throws CatalogException {
        if (!(value instanceof JSONObject)) throw CatalogJson.protocol("Invalid " + what);
        return (JSONObject) value;
    }

    private static JSONArray requireArray(Object value, String what, int cap)
            throws CatalogException {
        if (!(value instanceof JSONArray)) throw CatalogJson.protocol("Invalid " + what);
        JSONArray array = (JSONArray) value;
        if (array.length() > cap) throw CatalogJson.protocol("Too many " + what);
        return array;
    }

    private static JSONObject object(String key, Object value) {
        try { return new JSONObject().put(key, value); }
        catch (JSONException impossible) { throw new AssertionError(impossible); }
    }

    private static String requireSlug(String slug) {
        if (slug == null || slug.trim().isEmpty() || slug.length() > 512) {
            throw new IllegalArgumentException("Invalid catalog slug");
        }
        return slug.trim();
    }

    private static String sanitizeFileName(String value) throws CatalogException {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("\\.{2,}", ".");
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty()) throw new CatalogException(CatalogException.Kind.PROTOCOL,
                "Invalid plugin filename");
        return cleaned.length() > 180 ? cleaned.substring(cleaned.length() - 180) : cleaned;
    }

    private static void validateTelegramDeeplink(String value) throws CatalogException {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean allowedHttps = "https".equalsIgnoreCase(scheme) && host != null
                    && (host.equalsIgnoreCase("t.me") || host.equalsIgnoreCase("telegram.me"));
            boolean allowedTg = "tg".equalsIgnoreCase(scheme)
                    && "resolve".equalsIgnoreCase(host);
            if (!allowedHttps && !allowedTg) throw new Exception();
        } catch (Exception e) {
            throw new CatalogException(CatalogException.Kind.PROTOCOL,
                    "Unsafe Telegram fallback URL", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = "0123456789abcdef".toCharArray();
            char[] result = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                result[i * 2] = hex[value >>> 4];
                result[i * 2 + 1] = hex[value & 0x0f];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is unavailable", e);
        }
    }

    private interface Parser<T> {
        T parse(Object value) throws CatalogException;
    }

    private enum NoOpCall implements CatalogCall {
        INSTANCE;
        @Override public void cancel() {}
        @Override public boolean isCancelled() { return true; }
    }
}
