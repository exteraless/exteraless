package app.exteraless.plugins.catalog;

/** Immutable input for plugins.getAll. A null compatibility filter means all plugins. */
public final class CatalogQuery {
    public enum Sort {
        NEWEST("newest"), POPULAR("popular"), RATING("rating"), DOWNLOADS("downloads");

        final String wireValue;
        Sort(String wireValue) { this.wireValue = wireValue; }
    }

    public final int page;
    public final int limit;
    public final String category;
    public final String search;
    public final Sort sort;
    public final Boolean exteralessOnly;

    public CatalogQuery(int page, int limit, String category, String search, Sort sort,
                        Boolean exteralessOnly) {
        if (page < 1) throw new IllegalArgumentException("page must be >= 1");
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be 1..50");
        if (search != null && search.trim().length() > 100) {
            throw new IllegalArgumentException("search must be <= 100 characters");
        }
        this.page = page;
        this.limit = limit;
        this.category = emptyToNull(category);
        this.search = emptyToNull(search);
        this.sort = sort == null ? Sort.NEWEST : sort;
        this.exteralessOnly = exteralessOnly;
    }

    public static CatalogQuery firstPage() {
        return new CatalogQuery(1, 24, null, null, Sort.NEWEST, null);
    }

    public CatalogQuery withPage(int value) {
        return new CatalogQuery(value, limit, category, search, sort, exteralessOnly);
    }

    public CatalogQuery withSearch(String value) {
        return new CatalogQuery(1, limit, category, value, sort, exteralessOnly);
    }

    public CatalogQuery withCategory(String value) {
        return new CatalogQuery(1, limit, value, search, sort, exteralessOnly);
    }

    public CatalogQuery withSort(Sort value) {
        return new CatalogQuery(1, limit, category, search, value, exteralessOnly);
    }

    public CatalogQuery withExteralessOnly(Boolean value) {
        return new CatalogQuery(1, limit, category, search, sort, value);
    }

    private static String emptyToNull(String value) {
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }
}
