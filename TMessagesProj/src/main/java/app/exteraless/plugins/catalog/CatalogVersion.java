package app.exteraless.plugins.catalog;

import org.json.JSONObject;

/** A published, content-addressed plugin version. */
public final class CatalogVersion {
    public final long id;
    public final String version;
    public final String changelog;
    public final long fileSize;
    public final String fileHash;
    public final String gitCommitHash;
    public final String gitBranch;
    public final String gitTag;
    public final boolean stable;
    public final long downloadCount;
    public final long createdAt;
    public final String createdByName;
    public final String createdByImage;

    CatalogVersion(JSONObject json) throws CatalogException {
        id = CatalogJson.requiredPositiveLong(json, "id");
        version = CatalogJson.requiredString(json, "version", 256);
        changelog = CatalogJson.nullableString(json, "changelog", 200_000);
        fileSize = CatalogJson.nonNegativeLong(json, "fileSize", -1);
        if (fileSize < 0 || fileSize > CatalogClient.MAX_PLUGIN_BYTES) {
            throw CatalogJson.protocol("Invalid plugin file size");
        }
        fileHash = CatalogJson.requiredSha256(json, "fileHash");
        gitCommitHash = CatalogJson.nullableString(json, "gitCommitHash", 256);
        gitBranch = CatalogJson.nullableString(json, "gitBranch", 512);
        gitTag = CatalogJson.nullableString(json, "gitTag", 512);
        stable = json.optBoolean("isStable", true);
        downloadCount = CatalogJson.nonNegativeLong(json, "downloadCount", 0);
        createdAt = CatalogJson.nonNegativeLong(json, "createdAt", 0);
        JSONObject creator = json.optJSONObject("createdBy");
        createdByName = creator == null ? null : CatalogJson.nullableString(creator, "name", 512);
        createdByImage = creator == null ? null : CatalogJson.nullableString(creator, "image", 4_096);
    }
}
