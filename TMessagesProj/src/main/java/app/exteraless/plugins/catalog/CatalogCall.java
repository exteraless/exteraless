package app.exteraless.plugins.catalog;

/** Cancellable asynchronous catalog operation. */
public interface CatalogCall {
    void cancel();
    boolean isCancelled();

    interface Callback<T> {
        void onSuccess(T value);
        void onError(CatalogException error);
    }
}
