package app.exteraless.ai.network;

import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.exteraless.ai.AiConfig;
import app.exteraless.ai.data.Message;
import app.exteraless.ai.data.Role;
import app.exteraless.ai.data.Service;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

public class Client {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int STREAM_SYMBOLS_LIMIT = 16384;
    private static final String[] REASONING_FIELDS = {"reasoning_content", "reasoning", "reasoning_details", "thinking"};

    private static volatile OkHttpClient sharedHttpClient;

    private final OkHttpClient httpClient;
    private final Service serviceOverride;
    private final Role roleOverride;
    private final AtomicBoolean generating = new AtomicBoolean();
    private final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<>();

    public static class Builder {

        private Service serviceOverride;
        private Role roleOverride;

        public Builder serviceOverride(Service service) {
            this.serviceOverride = service;
            return this;
        }

        public Builder roleOverride(Role role) {
            this.roleOverride = role;
            return this;
        }

        public Client build() {
            return new Client(this);
        }
    }

    private Client(Builder builder) {
        this.serviceOverride = builder.serviceOverride;
        this.roleOverride = builder.roleOverride;
        this.httpClient = httpClient();
    }

    private static OkHttpClient httpClient() {
        OkHttpClient client = sharedHttpClient;
        if (client == null) {
            synchronized (Client.class) {
                client = sharedHttpClient;
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(5, TimeUnit.MINUTES)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .build();
                    sharedHttpClient = client;
                }
            }
        }
        return client;
    }

    private static String effortName(int effort) {
        if (effort == Service.REASONING_LOW) {
            return "low";
        }
        return effort == Service.REASONING_HIGH ? "high" : "medium";
    }

    public void listModels(Utilities.Callback2<List<String>, String> callback) {
        final Service service = service();
        if (service == null || TextUtils.isEmpty(service.getUrl())) {
            AndroidUtilities.runOnUIThread(() -> callback.run(null, "service is not configured"));
            return;
        }
        String base = Service.normalizeUrl(service.getUrl());
        String url = base + (base.endsWith("/") ? "models" : "/models");
        final Request built;
        try {
            Request.Builder request = new Request.Builder().url(url).get();
            if (!TextUtils.isEmpty(service.getKey())) {
                request.addHeader("Authorization", "Bearer " + service.getKey());
            }
            built = request.build();
        } catch (Exception e) {
            AndroidUtilities.runOnUIThread(() -> callback.run(null, "invalid url: " + url));
            return;
        }
        httpClient.newCall(built).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                AndroidUtilities.runOnUIThread(() -> callback.run(null, e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    ResponseBody body = closeable.body();
                    if (!closeable.isSuccessful() || body == null) {
                        final String detail = body == null
                                ? String.valueOf(closeable.code()) : readErrorMessage(body);
                        AndroidUtilities.runOnUIThread(() -> callback.run(null, detail));
                        return;
                    }
                    JSONArray data = new JSONObject(body.string()).optJSONArray("data");
                    final ArrayList<String> models = new ArrayList<>();
                    if (data != null) {
                        for (int a = 0; a < data.length(); a++) {
                            JSONObject item = data.optJSONObject(a);
                            String id = text(item, "id");
                            if (!TextUtils.isEmpty(id)) {
                                models.add(id);
                            }
                        }
                    }
                    AndroidUtilities.runOnUIThread(() -> callback.run(models, null));
                } catch (Exception e) {
                    AndroidUtilities.runOnUIThread(() -> callback.run(null, e.getMessage()));
                }
            }
        });
    }

    public boolean isGenerating() {
        return generating.get();
    }

    public void cancel(String requestId) {
        Call call = requestId == null ? null : activeCalls.remove(requestId);
        if (call != null) {
            call.cancel();
        }
        if (activeCalls.isEmpty()) {
            generating.set(false);
        }
    }

    public void cancelAll() {
        for (Call call : activeCalls.values()) {
            call.cancel();
        }
        activeCalls.clear();
        generating.set(false);
    }

    private Service service() {
        return serviceOverride != null ? serviceOverride : AiConfig.getSelectedService();
    }

    private Role role() {
        if (roleOverride != null) {
            return roleOverride;
        }
        String selected = AiConfig.getSelectedRole();
        for (Role role : AiConfig.getRoles()) {
            if (TextUtils.equals(role.getName(), selected)) {
                return role;
            }
        }
        for (app.exteraless.ai.data.Suggestions suggestion
                : app.exteraless.ai.data.Suggestions.values()) {
            if (TextUtils.equals(suggestion.getRole().getName(), selected)) {
                return suggestion.getRole();
            }
        }
        return null;
    }

    public void generate(String requestId, List<Message> messages, GenerationCallback callback) {
        final Service service = service();
        if (service == null || TextUtils.isEmpty(service.getUrl())
                || TextUtils.isEmpty(service.getModel())) {
            notifyError(requestId, callback, 0, "service is not configured");
            return;
        }
        if (TextUtils.isEmpty(service.getKey())) {
            notifyError(requestId, callback, 0, "api key is not set");
            return;
        }
        final boolean streaming = AiConfig.getResponseStreaming();
        final Request request;
        try {
            request = buildRequest(service, messages, streaming);
        } catch (Exception e) {
            notifyError(requestId, callback, 0, e.getMessage());
            return;
        }
        generating.set(true);
        Call call = httpClient.newCall(request);
        activeCalls.put(requestId, call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (call.isCanceled()) {
                    finish(requestId);
                    return;
                }
                notifyError(requestId, callback, 0, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    ResponseBody body = closeable.body();
                    if (!closeable.isSuccessful()) {
                        String detail = body == null ? null : readErrorMessage(body);
                        notifyError(requestId, callback, closeable.code(), detail);
                        return;
                    }
                    if (body == null) {
                        notifyError(requestId, callback, closeable.code(), "empty response");
                        return;
                    }
                    if (streaming) {
                        readStream(requestId, body, callback);
                    } else {
                        readSingle(requestId, body, callback);
                    }
                } catch (Exception e) {
                    if (!call.isCanceled()) {
                        notifyError(requestId, callback, 0, e.getMessage());
                    } else {
                        finish(requestId);
                    }
                }
            }
        });
    }

    private Request buildRequest(Service service, List<Message> messages, boolean streaming)
            throws Exception {
        String base = Service.normalizeUrl(service.getUrl());
        String url = base + (base.endsWith("/") ? "chat/completions" : "/chat/completions");

        JSONArray payload = new JSONArray();
        Role role = role();
        if (role != null && !TextUtils.isEmpty(role.getPrompt())) {
            payload.put(new JSONObject().put("role", "system").put("content", role.getPrompt()));
        }
        for (Message message : messages) {
            payload.put(encode(message));
        }

        JSONObject body = new JSONObject();
        body.put("model", service.getModel());
        body.put("messages", payload);
        body.put("stream", streaming);
        body.put("temperature", AiConfig.getTemperature() / 10.0f);
        int effort = service.getReasoningEffort();
        if (effort > 0) {
            body.put("reasoning_effort", effortName(effort));
        } else if (ModelsCatalog.supportsReasoning(service.getModel())) {
            body.put("reasoning_effort", "none");
        }

        return new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + service.getKey())
                .addHeader("HTTP-Referer", "https://github.com/exteraless/exteraless")
                .addHeader("X-Title", "exteraless")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
    }

    private static JSONObject encode(Message message) throws Exception {
        JSONObject encoded = new JSONObject();
        encoded.put("role", message.role());
        byte[] image = message.getImageData();
        if (image == null || image.length == 0) {
            encoded.put("content", message.content());
            return encoded;
        }
        JSONArray parts = new JSONArray();
        if (!TextUtils.isEmpty(message.content())) {
            parts.put(new JSONObject().put("type", "text").put("text", message.content()));
        }
        String data = "data:" + message.getMimeType() + ";base64,"
                + Base64.encodeToString(image, Base64.NO_WRAP);
        parts.put(new JSONObject().put("type", "image_url")
                .put("image_url", new JSONObject().put("url", data)));
        encoded.put("content", parts);
        return encoded;
    }

    private void readStream(String requestId, ResponseBody body, GenerationCallback callback)
            throws IOException {
        ReasoningFilter filter = new ReasoningFilter();
        StringBuilder full = new StringBuilder();
        boolean reasoningReported = false;
        BufferedSource source = body.source();
        while (!source.exhausted()) {
            String line = source.readUtf8LineStrict();
            if (line == null || !line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).trim();
            if (data.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(data)) {
                break;
            }
            String[] delta = extractDelta(data);
            if (delta == null) {
                continue;
            }
            if (delta[1] != null) {
                if (!reasoningReported) {
                    reasoningReported = true;
                    notifyThinking(callback);
                }
                notifyReasoning(callback, delta[1]);
                continue;
            }
            String visible = filter.filter(delta[0]);
            if (filter.consumeReasoningSignal() && !reasoningReported) {
                reasoningReported = true;
                notifyThinking(callback);
            }
            String thought = filter.consumeReasoning();
            if (thought != null) {
                notifyReasoning(callback, thought);
            }
            if (visible.isEmpty()) {
                continue;
            }
            full.append(visible);
            notifyChunk(callback, visible);
            if (full.length() >= STREAM_SYMBOLS_LIMIT) {
                break;
            }
        }
        String tail = filter.flush();
        if (!tail.isEmpty()) {
            full.append(tail);
            notifyChunk(callback, tail);
        }
        notifyResponse(requestId, callback, full.toString());
    }

    private void readSingle(String requestId, ResponseBody body, GenerationCallback callback)
            throws Exception {
        JSONObject parsed = new JSONObject(body.string());
        JSONArray choices = parsed.optJSONArray("choices");
        String content = null;
        if (choices != null && choices.length() > 0) {
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message != null) {
                content = text(message, "content");
            }
        }
        if (content == null) {
            notifyError(requestId, callback, 0, "empty response");
            return;
        }
        ReasoningFilter filter = new ReasoningFilter();
        String visible = filter.filter(content) + filter.flush();
        notifyResponse(requestId, callback, visible);
    }

    private static String text(JSONObject json, String key) {
        if (json == null || json.isNull(key)) {
            return null;
        }
        String value = json.optString(key, null);
        return TextUtils.isEmpty(value) ? null : value;
    }

    private static String[] extractDelta(String data) {
        try {
            JSONArray choices = new JSONObject(data).optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return null;
            }
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta == null) {
                return null;
            }
            String content = text(delta, "content");
            if (!TextUtils.isEmpty(content)) {
                return new String[]{content, null};
            }
            String reasoning = reasoningOf(delta);
            if (reasoning != null) {
                return new String[]{null, reasoning};
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String reasoningOf(JSONObject delta) {
        for (String field : REASONING_FIELDS) {
            Object value = delta.opt(field);
            if (value instanceof String && !((String) value).isEmpty()) {
                return (String) value;
            }
            if (value instanceof JSONObject) {
                String nested = text((JSONObject) value, "content");
                if (!TextUtils.isEmpty(nested)) {
                    return nested;
                }
            }
            if (value instanceof JSONArray) {
                JSONArray items = (JSONArray) value;
                for (int a = 0; a < items.length(); a++) {
                    JSONObject item = items.optJSONObject(a);
                    if (item == null) {
                        continue;
                    }
                    String entry = text(item, "text");
                    if (entry == null) {
                        entry = text(item, "summary");
                    }
                    if (entry != null) {
                        return entry;
                    }
                }
            }
        }
        return null;
    }

    private static String readErrorMessage(ResponseBody body) {
        try {
            String raw = body.string();
            JSONObject error = new JSONObject(raw).optJSONObject("error");
            if (error != null) {
                String message = text(error, "message");
                if (!TextUtils.isEmpty(message)) {
                    return message;
                }
            }
            return raw;
        } catch (Exception e) {
            return null;
        }
    }

    private void finish(String requestId) {
        activeCalls.remove(requestId);
        if (activeCalls.isEmpty()) {
            generating.set(false);
        }
    }

    private void notifyChunk(GenerationCallback callback, String chunk) {
        AndroidUtilities.runOnUIThread(() -> callback.onChunk(chunk));
    }

    private void notifyThinking(GenerationCallback callback) {
        AndroidUtilities.runOnUIThread(callback::onThinking);
    }

    private void notifyReasoning(GenerationCallback callback, String chunk) {
        AndroidUtilities.runOnUIThread(() -> callback.onReasoning(chunk));
    }

    private void notifyResponse(String requestId, GenerationCallback callback, String response) {
        finish(requestId);
        AndroidUtilities.runOnUIThread(() -> callback.onResponse(response));
    }

    private void notifyError(String requestId, GenerationCallback callback, int code,
                             String message) {
        finish(requestId);
        FileLog.e("AiClient: request failed, code " + code + ", " + message);
        AndroidUtilities.runOnUIThread(() -> callback.onError(code, message));
    }
}
