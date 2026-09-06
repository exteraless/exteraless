package app.exteraless.plugins.catalog;

import org.json.JSONObject;

/** A category returned by categories.getAll. */
public final class CatalogCategory {
    public final long id;
    public final String name;
    public final String slug;
    public final String description;
    public final String icon;
    public final String color;
    public final long pluginCount;

    CatalogCategory(JSONObject json) throws CatalogException {
        id = CatalogJson.requiredPositiveLong(json, "id");
        name = CatalogJson.requiredString(json, "name", 512);
        slug = CatalogJson.requiredString(json, "slug", 256);
        description = CatalogJson.nullableString(json, "description", 20_000);
        icon = CatalogJson.nullableString(json, "icon", 256);
        color = CatalogJson.nullableString(json, "color", 128);
        pluginCount = CatalogJson.nonNegativeLong(json, "pluginCount", 0);
    }
}
