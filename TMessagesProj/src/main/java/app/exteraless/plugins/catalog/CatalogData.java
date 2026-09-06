package app.exteraless.plugins.catalog;

/** A value plus its network/cache provenance. */
public final class CatalogData<T> {
    public final T value;
    public final boolean fromCache;
    public final boolean stale;
    public final long cachedAtMs;

    private CatalogData(T value, boolean fromCache, boolean stale, long cachedAtMs) {
        this.value = value;
        this.fromCache = fromCache;
        this.stale = stale;
        this.cachedAtMs = cachedAtMs;
    }

    public static <T> CatalogData<T> network(T value) {
        return new CatalogData<>(value, false, false, 0);
    }

    public static <T> CatalogData<T> cache(T value, boolean stale, long cachedAtMs) {
        return new CatalogData<>(value, true, stale, cachedAtMs);
    }
}
