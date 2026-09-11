package app.exteraless.plugins.catalog;

/** Disk usage of raw metadata cache and verified temporary downloads. */
public final class CatalogCacheStats {
    public final long metadataBytes;
    public final long downloadBytes;
    public final int downloadedFiles;

    CatalogCacheStats(long metadataBytes, long downloadBytes, int downloadedFiles) {
        this.metadataBytes = metadataBytes;
        this.downloadBytes = downloadBytes;
        this.downloadedFiles = downloadedFiles;
    }

    public long totalBytes() {
        return metadataBytes + downloadBytes;
    }
}
