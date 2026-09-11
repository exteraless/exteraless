package app.exteraless.plugins.catalog;

/** Update state derived only after a catalog source/slug has a locally parsed plugin ID. */
public final class CatalogUpdateMatch {
    public enum State { UNKNOWN_IDENTITY, INSTALLED_VERSION_UNKNOWN, UPDATE_AVAILABLE, UP_TO_DATE, LOCAL_NEWER }

    public final State state;
    public final String pluginId;
    public final String installedVersion;
    public final String catalogVersion;

    CatalogUpdateMatch(State state, String pluginId, String installedVersion,
                       String catalogVersion) {
        this.state = state;
        this.pluginId = pluginId;
        this.installedVersion = installedVersion;
        this.catalogVersion = catalogVersion;
    }
}
