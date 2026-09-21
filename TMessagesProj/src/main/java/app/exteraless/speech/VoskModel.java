package app.exteraless.speech;

public final class VoskModel {

    public final String code;
    public final String language;
    public final String name;
    public final String url;
    public final long size;
    public final String md5;

    VoskModel(String code, String language, String name, String url, long size, String md5) {
        this.code = code;
        this.language = language;
        this.name = name;
        this.url = url;
        this.size = size;
        this.md5 = md5;
    }
}
