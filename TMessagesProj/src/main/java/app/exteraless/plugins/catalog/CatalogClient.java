package app.exteraless.plugins.catalog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Minimal tRPC v11/SuperJSON HTTP client for ExteraStore-compatible public procedures. */
final class CatalogClient {
    static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    static final int MAX_PLUGIN_BYTES = 8 * 1024 * 1024;
    static final int MAX_DOWNLOAD_JSON_BYTES = MAX_PLUGIN_BYTES + 4 * 1024 * 1024;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http;

    CatalogClient(CatalogConfig config) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(4);
        dispatcher.setMaxRequestsPerHost(4);
        http = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .dns(new PublicDns(config))
                .connectionPool(new ConnectionPool(3, 2, TimeUnit.MINUTES))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(35, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    CatalogCall query(String baseUrl, String procedure, JSONObject input, int responseCap,
                      RawCallback callback) {
        return request(baseUrl, procedure, input, false, responseCap, callback);
    }

    CatalogCall mutation(String baseUrl, String procedure, JSONObject input, int responseCap,
                         RawCallback callback) {
        return request(baseUrl, procedure, input, true, responseCap, callback);
    }

    private CatalogCall request(String baseUrl, String procedure, JSONObject input,
                                boolean mutation, int responseCap, RawCallback callback) {
        final Request request;
        try {
            JSONObject envelope = new JSONObject().put("json", input == null
                    ? new JSONObject() : input);
            HttpUrl endpoint = HttpUrl.get(baseUrl).newBuilder()
                    .addPathSegments("api/trpc")
                    .addPathSegment(procedure)
                    .build();
            Request.Builder builder = new Request.Builder()
                    .header("Accept", "application/json")
                    .header("Accept-Language", CatalogRepository.appLocale())
                    .header("User-Agent", "Exteraless-PluginCatalog/1")
                    .url(mutation ? endpoint : endpoint.newBuilder()
                            .addQueryParameter("input", envelope.toString()).build());
            if (mutation) builder.post(RequestBody.create(envelope.toString(), JSON));
            request = builder.build();
        } catch (Exception e) {
            callback.onError(new CatalogException(CatalogException.Kind.CONFIGURATION,
                    "Cannot build catalog request", e));
            return CompletedCall.INSTANCE;
        }

        Call call = http.newCall(request);
        NetworkCall handle = new NetworkCall(call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call ignored, IOException error) {
                callback.onError(new CatalogException(call.isCanceled()
                        ? CatalogException.Kind.CANCELLED : CatalogException.Kind.NETWORK,
                        call.isCanceled() ? "Catalog request cancelled" : "Catalog request failed",
                        error));
            }

            @Override
            public void onResponse(Call ignored, Response response) {
                try (response) {
                    ResponseBody body = response.body();
                    if (body == null) {
                        throw new CatalogException(CatalogException.Kind.PROTOCOL,
                                "Catalog returned an empty response", null, response.code(), null);
                    }
                    long declaredLength = body.contentLength();
                    if (declaredLength > responseCap) {
                        throw new CatalogException(CatalogException.Kind.RESPONSE_TOO_LARGE,
                                "Catalog response is too large");
                    }
                    if (response.isRedirect()) {
                        throw new CatalogException(CatalogException.Kind.HTTP,
                                "Catalog redirect refused", null, response.code(), null);
                    }
                    String contentType = response.header("Content-Type", "");
                    if (!contentType.toLowerCase().startsWith("application/json")) {
                        throw new CatalogException(CatalogException.Kind.PROTOCOL,
                                "Catalog did not return JSON", null, response.code(), null);
                    }
                    byte[] bytes = readBounded(body.byteStream(), responseCap);
                    String raw = new String(bytes, StandardCharsets.UTF_8);
                    Object data = parseEnvelope(raw, response.code(), response.isSuccessful());
                    callback.onSuccess(new RawResponse(data, raw));
                } catch (CatalogException e) {
                    callback.onError(e);
                } catch (IOException e) {
                    callback.onError(new CatalogException(CatalogException.Kind.NETWORK,
                            "Cannot read catalog response", e));
                } catch (Exception e) {
                    callback.onError(new CatalogException(CatalogException.Kind.PROTOCOL,
                            "Invalid catalog response", e));
                }
            }
        });
        return handle;
    }

    static Object parseEnvelope(String raw, int httpStatus, boolean successful)
            throws CatalogException {
        final JSONObject root;
        try {
            root = new JSONObject(raw);
        } catch (JSONException e) {
            throw new CatalogException(CatalogException.Kind.PROTOCOL,
                    "Malformed catalog JSON", e, httpStatus, null);
        }
        boolean hasResult = root.has("result") && !root.isNull("result");
        boolean hasError = root.has("error") && !root.isNull("error");
        if (hasResult == hasError) {
            throw new CatalogException(CatalogException.Kind.PROTOCOL,
                    "Invalid tRPC envelope", null, httpStatus, null);
        }
        if (hasError) {
            JSONObject outer = root.optJSONObject("error");
            JSONObject error = outer == null ? null : outer.optJSONObject("json");
            if (error == null) error = outer;
            if (error == null) {
                throw new CatalogException(CatalogException.Kind.PROTOCOL,
                        "Invalid tRPC error", null, httpStatus, null);
            }
            String message = error.optString("message", "Catalog request failed");
            JSONObject details = error.optJSONObject("data");
            String code = details == null ? null : details.optString("code", null);
            throw new CatalogException(CatalogException.Kind.SERVER, message, null,
                    httpStatus, code);
        }
        if (!successful) {
            throw new CatalogException(CatalogException.Kind.HTTP,
                    "Catalog returned HTTP " + httpStatus, null, httpStatus, null);
        }
        JSONObject result = root.optJSONObject("result");
        JSONObject data = result == null ? null : result.optJSONObject("data");
        if (data == null || !data.has("json")) {
            throw new CatalogException(CatalogException.Kind.PROTOCOL,
                    "Missing tRPC result data", null, httpStatus, null);
        }
        return data.opt("json");
    }

    private static byte[] readBounded(InputStream input, int cap)
            throws IOException, CatalogException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(cap, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > cap) {
                throw new CatalogException(CatalogException.Kind.RESPONSE_TOO_LARGE,
                        "Catalog response is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    interface RawCallback {
        void onSuccess(RawResponse response);
        void onError(CatalogException error);
    }

    static final class RawResponse {
        final Object data;
        final String rawEnvelope;
        RawResponse(Object data, String rawEnvelope) {
            this.data = data;
            this.rawEnvelope = rawEnvelope;
        }
    }

    private static final class NetworkCall implements CatalogCall {
        private final Call call;
        NetworkCall(Call call) { this.call = call; }
        @Override public void cancel() { call.cancel(); }
        @Override public boolean isCancelled() { return call.isCanceled(); }
    }

    private enum CompletedCall implements CatalogCall {
        INSTANCE;
        @Override public void cancel() {}
        @Override public boolean isCancelled() { return true; }
    }

    /** Resolves normally, then rejects DNS rebinding into non-public address space. */
    private static final class PublicDns implements Dns {
        private final CatalogConfig config;

        PublicDns(CatalogConfig config) {
            this.config = config;
        }

        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            if (config.isLocalSourcesAllowed()) return addresses;
            boolean vpn = isVpnActive();
            for (InetAddress address : addresses) {
                if (isPublic(address)) continue;
                if (vpn && isFakeDnsRange(address)) continue;
                throw new UnknownHostException("Catalog host resolved to a non-public address");
            }
            return addresses;
        }

        private static boolean isFakeDnsRange(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != 4) return false;
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            return a == 198 && (b == 18 || b == 19);
        }

        private static boolean isVpnActive() {
            try {
                android.content.Context context =
                        org.telegram.messenger.ApplicationLoader.applicationContext;
                if (context == null) return false;
                android.net.ConnectivityManager connectivity =
                        (android.net.ConnectivityManager) context.getSystemService(
                                android.content.Context.CONNECTIVITY_SERVICE);
                if (connectivity == null) return false;
                android.net.Network network = connectivity.getActiveNetwork();
                if (network == null) return false;
                android.net.NetworkCapabilities capabilities =
                        connectivity.getNetworkCapabilities(network);
                return capabilities != null && capabilities.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_VPN);
            } catch (Exception ignored) {
                return false;
            }
        }

        private static boolean isPublic(InetAddress address) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return false;
            }
            byte[] bytes = address.getAddress();
            if (bytes.length == 16) {
                int first = bytes[0] & 0xff;
                return (first & 0xfe) != 0xfc;
            }
            if (bytes.length == 4) {
                int a = bytes[0] & 0xff;
                int b = bytes[1] & 0xff;
                if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
                if (a == 169 && b == 254) return false;
                if (a == 172 && b >= 16 && b <= 31) return false;
                if (a == 192 && b == 168) return false;
                if (a == 100 && b >= 64 && b <= 127) return false;
                if (a == 198 && (b == 18 || b == 19)) return false;
            }
            return true;
        }
    }
}
