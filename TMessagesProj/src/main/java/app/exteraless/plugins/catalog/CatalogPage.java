package app.exteraless.plugins.catalog;

import java.util.Collections;
import java.util.List;

/** One deterministic page returned by plugins.getAll. */
public final class CatalogPage {
    public final List<CatalogPlugin> plugins;
    public final long totalCount;
    public final int totalPages;
    public final int currentPage;

    CatalogPage(List<CatalogPlugin> plugins, long totalCount, int totalPages, int currentPage) {
        this.plugins = Collections.unmodifiableList(plugins);
        this.totalCount = totalCount;
        this.totalPages = totalPages;
        this.currentPage = currentPage;
    }

    public boolean hasNextPage() {
        return currentPage < totalPages;
    }
}
