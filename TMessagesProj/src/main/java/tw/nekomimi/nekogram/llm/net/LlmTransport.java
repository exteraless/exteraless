package tw.nekomimi.nekogram.llm.net;

import static org.telegram.messenger.LocaleController.getString;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import tw.nekomimi.nekogram.utils.HttpClient;
import app.exteraless.ai.chatgpt.ChatGptProxy;

final class LlmTransport {

    static final OkHttpClient HTTP_CLIENT = HttpClient.INSTANCE.getLlmInstance();
    static final OkHttpClient TEST_HTTP_CLIENT = HTTP_CLIENT.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build();
    private static final OkHttpClient CHATGPT_HTTP_CLIENT = HTTP_CLIENT.newBuilder()
            .callTimeout(ChatGptProxy.REQUEST_TIMEOUT_SECONDS + 15, TimeUnit.SECONDS)
            .build();

    private static final Set<String> optionalParametersDisabledModels = ConcurrentHashMap.newKeySet();

    private LlmTransport() {
    }

    record Credentials(String baseUrl, String apiKey, String error) {
        boolean isInvalid() {
            return error != null;
        }
    }

    @FunctionalInterface
    interface OptionalParametersRequest {
        LlmResponse<String> execute(boolean withOptionalParameters) throws Exception;
    }

    @FunctionalInterface
    interface ResponseParser<T> {
        T parse(String body) throws Exception;
    }

    @FunctionalInterface
    interface TestRequest {
        LlmResponse<String> execute(String model, JSONArray messages) throws Exception;
    }

    @FunctionalInterface
    interface RequestFactory {
        Request create() throws Exception;
    }

    static Credentials prepareCredentials(String baseUrl, String apiKey) {
        String requestBaseUrl = trimTrailingSlash(baseUrl != null ? baseUrl.trim() : "");
        if (requestBaseUrl.isEmpty()) {
            return new Credentials("", "", "Empty base URL");
        }
        String key = apiKey != null ? apiKey.trim() : "";
        if (key.isEmpty()) {
            return new Credentials(requestBaseUrl, "", getString(R.string.ApiKeyNotSet));
        }
        if (key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) {
            return new Credentials(requestBaseUrl, "", "Invalid API key");
        }
        return new Credentials(requestBaseUrl, key, null);
    }

    static LlmResponse<String> execute(RequestFactory requestFactory, OkHttpClient client) {
        long start = System.currentTimeMillis();
        boolean chatGpt = false;
        try {
            Request request = requestFactory.create();
            chatGpt = ChatGptProxy.isLocalRequest(request.url().toString());
            OkHttpClient transport = chatGpt && client == HTTP_CLIENT ? CHATGPT_HTTP_CLIENT : client;
            try (Response response = transport.newCall(request).execute()) {
                String body = response.body().string();
                long duration = System.currentTimeMillis() - start;
                int code = response.code();
                if (chatGpt) FileLog.d("ChatGPT translator status=" + code + " ms=" + duration);
                if (!response.isSuccessful()) {
                    return new LlmResponse<>(null, formatHttpError(code, body), duration, code);
                }
                return new LlmResponse<>(body, null, duration, code);
            }
        } catch (Exception e) {
            if (chatGpt) FileLog.w("ChatGPT translator failure=" + e.getClass().getSimpleName()
                    + " ms=" + (System.currentTimeMillis() - start));
            return new LlmResponse<>(null, e.toString(), System.currentTimeMillis() - start, 0);
        }
    }

    static RequestBody jsonBody(String json) {
        return RequestBody.create(json, HttpClient.MEDIA_TYPE_JSON);
    }

    static LlmResponse<String> executeWithOptionalParameters(String baseUrl, String model, OptionalParametersRequest request) {
        String key = baseUrl + "|" + (model != null ? model.trim() : "");
        boolean withOptionalParameters = !optionalParametersDisabledModels.contains(key);
        try {
            LlmResponse<String> response = request.execute(withOptionalParameters);
            if (!response.isSuccess() && response.httpCode() == 400 && withOptionalParameters) {
                optionalParametersDisabledModels.add(key);
                FileLog.d("HTTP 400 with optional parameters, retrying without them for model: " + model);
                return request.execute(false);
            }
            return response;
        } catch (Exception e) {
            return error(e.toString());
        }
    }

    static <T> LlmResponse<T> parseResponse(LlmResponse<String> response, ResponseParser<T> parser, Predicate<T> isEmpty, String emptyError) {
        if (!response.isSuccess()) {
            return error(response);
        }
        try {
            T data = parser.parse(response.data());
            if (isEmpty.test(data)) {
                return new LlmResponse<>(null, emptyError + truncate(response.data()), response.durationMs(), response.httpCode());
            }
            return new LlmResponse<>(data, null, response.durationMs(), response.httpCode());
        } catch (Exception e) {
            return new LlmResponse<>(null, "Parse error: " + e + " ; raw=" + truncate(response.data()), response.durationMs(), response.httpCode());
        }
    }

    static LlmResponse<String> test(String model, TestRequest request) {
        String modelName = model != null ? model.trim() : "";
        if (modelName.isEmpty()) {
            return error("Model is empty");
        }
        try {
            JSONArray messages = new JSONArray().put(new JSONObject()
                    .put("role", "user")
                    .put("content", "This is a test. Reply with a single word: OK"));
            return request.execute(modelName, messages);
        } catch (Exception e) {
            return error(e.toString());
        }
    }

    static <T> LlmResponse<T> error(String error) {
        return new LlmResponse<>(null, error, 0, 0);
    }

    static <T> LlmResponse<T> error(LlmResponse<?> response) {
        return new LlmResponse<>(null, response.error(), response.durationMs(), response.httpCode());
    }

    static String truncate(String value) {
        if (value == null) {
            return "";
        }
        final int limit = 4096;
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "\n…(truncated)";
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String formatHttpError(int code, String body) {
        return String.format(Locale.ROOT, "HTTP %d : %s", code, truncate(body));
    }
}
