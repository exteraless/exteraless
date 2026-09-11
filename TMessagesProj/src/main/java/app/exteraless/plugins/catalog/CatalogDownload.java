package app.exteraless.plugins.catalog;

import java.io.File;

/** Verified local artifact or an explicit Telegram fallback supplied by the store. */
public final class CatalogDownload {
    public enum Kind { VERIFIED_FILE, TELEGRAM }

    public final Kind kind;
    public final String sourceUrl;
    public final String pluginSlug;
    public final String version;
    public final File file;
    public final String telegramDeeplink;
    public final String fileName;
    public final String mimeType;
    public final long byteSize;
    public final String sha256;
    public final boolean verified;

    private CatalogDownload(Kind kind, String sourceUrl, String pluginSlug, String version,
                            File file, String telegramDeeplink, String fileName, String mimeType,
                            long byteSize, String sha256, boolean verified) {
        this.kind = kind;
        this.sourceUrl = sourceUrl;
        this.pluginSlug = pluginSlug;
        this.version = version;
        this.file = file;
        this.telegramDeeplink = telegramDeeplink;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.byteSize = byteSize;
        this.sha256 = sha256;
        this.verified = verified;
    }

    static CatalogDownload file(String sourceUrl, String slug, String version, File file,
                                String fileName, String mimeType, long size, String hash) {
        return new CatalogDownload(Kind.VERIFIED_FILE, sourceUrl, slug, version, file, null,
                fileName, mimeType, size, hash, true);
    }

    static CatalogDownload telegram(String sourceUrl, String slug, String version,
                                    String deeplink, String fileName, long size, String hash) {
        return new CatalogDownload(Kind.TELEGRAM, sourceUrl, slug, version, null, deeplink,
                fileName, null, size, hash, false);
    }
}
