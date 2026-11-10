package app.exteraless.ai.chatgpt;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import app.exteraless.ai.data.ModelInfo;
import app.exteraless.ai.data.Service;
import app.exteraless.ai.network.GenerationCallback;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

/** Direct ChatGPT-plan transport. OAuth credentials never enter provider configuration. */
public final class ChatGptClient {
    private static final String API = "https://api.openai.com/v1";
    private static final String TOOL_NAMESPACE = "exteraless";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES).build();
    private static volatile Catalog catalog;

    private ChatGptClient() {}

    static void clearCatalog() { catalog = null; }

    public static final class Operation {
        private final String accountId = ChatGptAuth.getInstance().snapshot().activeClientId;
        private volatile boolean cancelled;
        private volatile Call call;
        private ChatGptAuth.Session session;

        public synchronized void cancel() {
            cancelled = true;
            if (call != null) call.cancel();
        }

        private void check() {
            if (cancelled || Thread.currentThread().isInterrupted()
                    || session != null && !ChatGptAuth.getInstance().current(session)) throw new CancellationException();
        }

        private synchronized void attach(Call next) {
            check();
            call = next;
        }
    }

    public static final class Failure extends IOException {
        public final int status;
        public final boolean retryable;
        public final String code;

        Failure(int status, String code, boolean retryable) {
            super(errorText(status, code));
            this.status = status;
            this.retryable = retryable;
            this.code = code;
        }
    }

    private record Catalog(String clientId, long version, List<ModelInfo> models) {}

    public static ModelInfo model(String id) {
        Catalog saved = catalog;
        if (saved == null || !saved.clientId.equals(ChatGptAuth.getInstance().snapshot().activeClientId)) return null;
        for (ModelInfo model : saved.models) if (model.getId().equals(id)) return model;
        return null;
    }

    public static String defaultModel() {
        Catalog saved = catalog;
        return saved != null && saved.clientId.equals(ChatGptAuth.getInstance().snapshot().activeClientId) && !saved.models.isEmpty()
                ? saved.models.get(0).getId() : "";
    }

    public static List<ModelInfo> models(Operation operation) throws Exception {
        prepare(operation);
        List<ModelInfo> models = perform(operation, API + "/models", null, response -> {
            return parseModels(readJson(response));
        });
        operation.check();
        catalog = new Catalog(operation.session.clientId, operation.session.version, models);
        return models;
    }

    static List<ModelInfo> parseModels(JSONObject json) throws Exception {
        JSONArray entries = json.optJSONArray("models");
        if (entries == null) throw new Failure(0, "invalid_model_catalog", false);
        ArrayList<ModelInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !"list".equals(entry.optString("visibility"))) continue;
            String slug = entry.optString("slug");
            if (!slug.isEmpty() && seen.add(slug)) result.add(new ModelInfo(slug,
                    entry.optString("display_name", slug), entry.optBoolean("reasoning", false), entry.optLong("context_window", 0)));
        }
        return Collections.unmodifiableList(result);
    }

    static Round chat(Operation operation, JSONObject request, GenerationCallback events) throws Exception {
        prepare(operation);
        String model = request.optString("model");
        if (model.isEmpty() || ChatGptProxy.DEFAULT_MODEL.equals(model)) {
            model = defaultModel();
            if (model.isEmpty()) {
                List<ModelInfo> available = models(operation);
                if (available.isEmpty()) throw new Failure(0, "model_required", false);
                model = available.get(0).getId();
            }
        }
        String effort = request.optString("reasoning_effort");
        int reasoning = "low".equals(effort) ? Service.REASONING_LOW : "medium".equals(effort)
                ? Service.REASONING_MEDIUM : "high".equals(effort) ? Service.REASONING_HIGH : 0;
        JSONObject body = payload(model, input(request.getJSONArray("messages")), reasoning);
        JSONArray tools = request.optJSONArray("tools");
        if (tools != null && tools.length() > 0) {
            body.put("tools", new JSONArray().put(new JSONObject().put("type", "namespace")
                    .put("name", TOOL_NAMESPACE).put("description", "Tools supplied by exteraless.")
                    .put("tools", definitions(tools))));
            body.put("include", new JSONArray().put("reasoning.encrypted_content"));
            body.put("tool_choice", "none".equals(request.optString("tool_choice")) ? "none" : "auto");
        }
        Round result = perform(operation, API + "/responses", body, response -> readStream(response, events));
        operation.check();
        return result;
    }

    static JSONObject payload(String model, JSONArray input, int effort) throws Exception {
        JSONObject body = new JSONObject().put("model", model).put("input", input).put("store", false).put("stream", true);
        if (effort > 0) body.put("reasoning", new JSONObject().put("effort", effort == Service.REASONING_LOW
                ? "low" : effort == Service.REASONING_HIGH ? "high" : "medium"));
        return body;
    }

    static JSONArray input(JSONArray messages) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String role = message.getString("role");
            if ("tool".equals(role)) {
                result.put(new JSONObject().put("type", "function_call_output")
                        .put("call_id", message.getString("tool_call_id")).put("output", message.optString("content")));
                continue;
            }
            JSONArray calls = message.optJSONArray("tool_calls");
            if ("assistant".equals(role) && calls != null && calls.length() > 0) {
                JSONArray previous = calls.getJSONObject(0).optJSONArray("chatgpt_output");
                if (previous != null) {
                    for (int j = 0; j < previous.length(); j++) result.put(previous.getJSONObject(j));
                } else {
                    for (int j = 0; j < calls.length(); j++) {
                        JSONObject call = calls.getJSONObject(j);
                        JSONObject function = call.getJSONObject("function");
                        result.put(new JSONObject().put("type", "function_call").put("call_id", call.getString("id"))
                                .put("namespace", TOOL_NAMESPACE).put("name", function.getString("name"))
                                .put("arguments", function.optString("arguments", "{}")));
                    }
                }
                continue;
            }
            if ("system".equals(role)) role = "developer";
            Object content = message.get("content");
            if (content instanceof JSONArray parts) {
                JSONArray converted = new JSONArray();
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.getJSONObject(j);
                    if ("text".equals(part.optString("type"))) {
                        converted.put(new JSONObject().put("type", "assistant".equals(role) ? "output_text" : "input_text").put("text", part.getString("text")));
                    } else if ("image_url".equals(part.optString("type"))) {
                        converted.put(new JSONObject().put("type", "input_image").put("image_url", part.getJSONObject("image_url").getString("url")));
                    } else throw new Failure(0, "unsupported_input", false);
                }
                content = converted;
            }
            result.put(new JSONObject().put("role", role).put("content", content));
        }
        return result;
    }

    private static JSONArray definitions(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject function = source.getJSONObject(i).getJSONObject("function");
            result.put(new JSONObject(function.toString()).put("type", "function").put("strict", false));
        }
        return result;
    }

    static final class Round {
        final String text;
        final JSONArray output;
        Round(String text, JSONArray output) { this.text = text; this.output = output; }
    }

    static Round readStream(Response response, GenerationCallback events) throws Exception {
        if (response.body() == null) throw new Failure(0, "empty_response", false);
        BufferedSource source = response.body().source();
        StringBuilder data = new StringBuilder();
        StringBuilder text = new StringBuilder();
        long bytes = 0;
        while (!source.exhausted()) {
            String line = source.readUtf8LineStrict(8 * 1024 * 1024);
            bytes += line.length();
            if (bytes > 32 * 1024 * 1024) throw new Failure(0, "response_too_large", false);
            if (!line.isEmpty()) {
                if (line.startsWith("data:")) data.append(line.substring(5).trim()).append('\n');
                continue;
            }
            if (data.length() == 0) continue;
            String value = data.toString().trim();
            data.setLength(0);
            if ("[DONE]".equals(value)) break;
            JSONObject event = new JSONObject(value);
            String type = event.optString("type");
            if ("response.output_text.delta".equals(type) || "response.refusal.delta".equals(type)) {
                String delta = event.optString("delta");
                text.append(delta);
                if (events != null) events.onChunk(delta);
            } else if ("response.reasoning_summary_text.delta".equals(type)) {
                if (events != null) { events.onThinking(); events.onReasoning(event.optString("delta")); }
            } else if ("response.failed".equals(type) || "response.incomplete".equals(type) || "error".equals(type)) {
                JSONObject failed = event.optJSONObject("response");
                throw failure(failed == null ? event : failed, 0);
            } else if ("response.completed".equals(type)) {
                JSONObject completed = event.getJSONObject("response");
                if (!"completed".equals(completed.optString("status", "completed"))) throw failure(completed, 0);
                JSONArray output = completed.optJSONArray("output");
                if (output == null) output = new JSONArray();
                String finalText = outputText(output);
                return new Round(finalText.isEmpty() ? text.toString() : finalText, output);
            }
        }
        throw new Failure(0, "interrupted_stream", true);
    }

    private static String outputText(JSONArray output) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            JSONArray content = item == null ? null : item.optJSONArray("content");
            if (content == null || !"message".equals(item.optString("type"))) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null) continue;
                if ("output_text".equals(part.optString("type"))) result.append(part.optString("text"));
                else if ("refusal".equals(part.optString("type"))) result.append(part.optString("refusal"));
            }
        }
        return result.toString();
    }

    private interface Reader<T> { T read(Response response) throws Exception; }

    private static <T> T perform(Operation operation, String url, JSONObject body, Reader<T> reader) throws Exception {
        ChatGptAuth auth = ChatGptAuth.getInstance();
        for (int attempt = 0; attempt < 2; attempt++) {
            operation.check();
            Request.Builder request = new Request.Builder().url(url)
                    .header("Authorization", "Bearer " + operation.session.accessToken);
            if (body != null) request.header("Accept", "text/event-stream").post(RequestBody.create(body.toString(), JSON));
            Call call = HTTP.newCall(request.build());
            auth.register(operation.session, call);
            try {
                operation.attach(call);
                try (Response response = call.execute()) {
                    if (response.code() == 401 && attempt == 0) {
                        operation.session = auth.session(operation.accountId, operation.session);
                        continue;
                    }
                    if (!response.isSuccessful()) throw failure(readJson(response), response.code());
                    T result = reader.read(response);
                    operation.check();
                    return result;
                }
            } finally {
                auth.unregister(call);
                operation.call = null;
            }
        }
        throw new Failure(401, "unauthorized", false);
    }

    private static JSONObject readJson(Response response) throws Exception {
        if (response.body() == null) throw new Failure(response.code(), "empty_response", false);
        BufferedSource source = response.body().source();
        source.request(1048577);
        if (source.getBuffer().size() > 1048576) throw new Failure(response.code(), "response_too_large", false);
        try { return new JSONObject(source.readUtf8()); }
        catch (Exception e) { throw new Failure(response.code(), "invalid_response", response.code() >= 500); }
    }

    private static Failure failure(JSONObject json, int status) {
        JSONObject error = json.optJSONObject("error");
        String code = error == null ? json.optString("code", json.optString("type", "request_failed")) : error.optString("code", "request_failed");
        return new Failure(status, code, status >= 500);
    }

    private static void prepare(Operation operation) throws Exception {
        operation.check();
        if (operation.session == null) operation.session = ChatGptAuth.getInstance().session(operation.accountId, null);
        operation.check();
    }

    static String safeError(Exception error) {
        return error instanceof Failure || error instanceof ChatGptAuth.Unavailable
                ? error.getMessage() : LocaleController.getString(R.string.OEChatGptNetworkError);
    }

    private static String errorText(int status, String code) {
        if (code.contains("usage_limit") || code.contains("usage_unavailable")) return LocaleController.getString(R.string.OEChatGptUsageLimit);
        if ("model_required".equals(code)) return LocaleController.getString(R.string.OEChatGptChooseModel);
        String detail = code.matches("[a-z][a-z0-9_]{0,99}") ? code : "request_failed";
        return LocaleController.getString(R.string.OEAiFailed) + (status > 0 ? " · " + status : "") + " · " + detail;
    }
}
