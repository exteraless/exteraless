package app.exteraless.plugins.catalog;

/** A typed failure returned by the catalog transport, protocol, cache or verifier. */
public final class CatalogException extends Exception {

    public enum Kind {
        CONFIGURATION,
        NETWORK,
        HTTP,
        PROTOCOL,
        SERVER,
        RESPONSE_TOO_LARGE,
        INTEGRITY,
        STORAGE,
        CANCELLED
    }

    public final Kind kind;
    public final int httpStatus;
    public final String serverCode;

    public CatalogException(Kind kind, String message) {
        this(kind, message, null, 0, null);
    }

    public CatalogException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, 0, null);
    }

    public CatalogException(Kind kind, String message, Throwable cause, int httpStatus,
                            String serverCode) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.serverCode = serverCode;
    }
}
