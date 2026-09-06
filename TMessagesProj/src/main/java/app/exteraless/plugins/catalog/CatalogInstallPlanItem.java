package app.exteraless.plugins.catalog;

import org.json.JSONObject;

/** A dependency-plan row. Core never auto-installs it. */
public final class CatalogInstallPlanItem {
    public final long id;
    public final String name;
    public final String slug;
    public final String version;
    public final int installOrder;
    public final boolean requestedPlugin;

    CatalogInstallPlanItem(JSONObject json) throws CatalogException {
        id = CatalogJson.requiredPositiveLong(json, "id");
        name = CatalogJson.requiredString(json, "name", 512);
        slug = CatalogJson.requiredString(json, "slug", 512);
        version = CatalogJson.requiredString(json, "version", 256);
        installOrder = CatalogJson.nonNegativeInt(json, "installOrder", 0);
        requestedPlugin = json.optBoolean("isRequestedPlugin", false);
    }
}
