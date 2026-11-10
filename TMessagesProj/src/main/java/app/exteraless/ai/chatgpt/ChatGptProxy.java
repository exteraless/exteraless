package app.exteraless.ai.chatgpt;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import app.exteraless.ai.data.ModelInfo;
import app.exteraless.ai.network.GenerationCallback;

/** Private Chat Completions bridge for the application's existing AI and translator clients. */
public final class ChatGptProxy {
    public static final String DEFAULT_MODEL = "chatgpt-default";
    public static final int REQUEST_TIMEOUT_SECONDS = 330;
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final AtomicLong IDS = new AtomicLong();
    private static final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(8, 8, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable, "ChatGPT localhost request");
                thread.setDaemon(true);
                return thread;
            });
    private static final ScheduledThreadPoolExecutor TIMERS = new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread thread = new Thread(runnable, "ChatGPT localhost deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private static final ThreadPoolExecutor OVERFLOW = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable, "ChatGPT localhost overload replies");
                thread.setDaemon(true);
                return thread;
            });
    static {
        WORKERS.allowCoreThreadTimeOut(true);
        OVERFLOW.allowCoreThreadTimeOut(true);
        TIMERS.setRemoveOnCancelPolicy(true);
    }
    private static volatile String address;
    private static volatile String secret;
    private static ServerSocket listener;
    private static Thread acceptor;

    private ChatGptProxy() {}

    public static String baseUrl() { return address; }
    public static String credential() { return address == null ? null : secret; }
    public static boolean isLocalRequest(String url) {
        String base = address;
        return base != null && (base.equals(url) || url.startsWith(base + "/"));
    }
    public static String defaultModel() {
        String model = ChatGptClient.defaultModel();
        return model.isEmpty() ? DEFAULT_MODEL : model;
    }

    // Called only by the authentication worker before publishing a connected account.
    static synchronized void start() throws IOException {
        if (listener != null && !listener.isClosed() && acceptor != null && acceptor.isAlive()) return;
        if (listener != null) {
            listener.close();
            cancelRequests();
        }
        ServerSocket server = new ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"));
        listener = server;
        secret = random();
        address = "http://127.0.0.1:" + server.getLocalPort() + "/v1";
        acceptor = new Thread(() -> accept(server), "ChatGPT localhost listener");
        acceptor.setDaemon(true);
        acceptor.start();
        FileLog.d("ChatGPT bridge started");
    }

    static synchronized void cancelRequests() {
        secret = random();
        for (Connection connection : connections) connection.cancel();
        connections.clear();
    }

    static synchronized void stop() {
        address = null;
        cancelRequests();
        if (listener != null) {
            try { listener.close(); } catch (IOException ignored) {}
            listener = null;
        }
        acceptor = null;
    }

    private static String random() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return ChatGptIdToken.encode(value);
    }

    private static void accept(ServerSocket server) {
        int failures = 0;
        while (!server.isClosed()) {
            Socket socket = null;
            try {
                socket = server.accept();
                failures = 0;
                Connection rejected = null;
                synchronized (ChatGptProxy.class) {
                    if (listener != server || server.isClosed()) {
                        socket.close();
                        continue;
                    }
                    Connection connection = new Connection(socket);
                    connections.add(connection);
                    try { connection.submit(); }
                    catch (RejectedExecutionException busy) { rejected = connection; }
                    catch (RuntimeException e) { connection.cancel(); throw e; }
                }
                if (rejected != null) rejected.rejectBusy("queue_full");
            } catch (IOException | RuntimeException e) {
                if (socket != null) try { socket.close(); } catch (IOException ignored) {}
                if (server.isClosed()) return;
                // A failed connection must not terminate the listener or spin on repeated accept errors.
                if (failures++ == 0) FileLog.w("ChatGPT bridge accept failure: " + e.getClass().getSimpleName());
                try { Thread.sleep(Math.min(1000, failures * 50L)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private static final class Connection implements Runnable {
        private final Socket socket;
        private final ChatGptClient.Operation operation = new ChatGptClient.Operation();
        private final Object lifecycle = new Object();
        private final long id = IDS.incrementAndGet();
        private final long accepted = System.nanoTime();
        private ScheduledFuture<?> queueTimeout;
        private ScheduledFuture<?> deadline;
        private Runnable rejection;
        private Thread runner;
        private volatile boolean cancelled;
        private volatile boolean finished;
        private volatile boolean streaming;
        private volatile boolean headersSent;
        private final StringBuilder forwarded = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final String responseId = "chatcmpl-" + UUID.randomUUID();
        private String route = "headers";
        private volatile String outcome = "ok";
        private int responseStatus;

        Connection(Socket socket) { this.socket = socket; }

        void submit() {
            queueTimeout = TIMERS.schedule(() -> {
                if (WORKERS.remove(this)) rejectBusy("queue_timeout");
            }, 120, TimeUnit.SECONDS);
            deadline = TIMERS.schedule(() -> {
                outcome = "deadline";
                cancel();
            }, REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            WORKERS.execute(this);
        }

        void rejectBusy(String reason) {
            outcome = reason;
            if (queueTimeout != null) queueTimeout.cancel(false);
            rejection = this::readAndReject;
            try { OVERFLOW.execute(rejection); }
            catch (RejectedExecutionException full) { cancel(); }
        }

        private void readAndReject() {
            synchronized (lifecycle) {
                if (cancelled || finished) return;
                runner = Thread.currentThread();
                if (deadline != null) deadline.cancel(false);
                deadline = TIMERS.schedule(this::cancel, 2, TimeUnit.SECONDS);
            }
            try {
                // Closing before the sender finishes writing turns an HTTP 503 into Broken pipe.
                socket.setSoTimeout(1000);
                readRequest(new BufferedInputStream(socket.getInputStream()));
                reply(503, error(LocaleController.getString(R.string.OEChatGptBusy), "chatgpt_bridge_busy"));
                socket.shutdownOutput();
            } catch (Exception ignored) {
            } finally { finish(); }
        }

        void cancel() {
            boolean running;
            synchronized (lifecycle) {
                if (finished) return;
                cancelled = true;
                running = runner != null;
                if (runner != null && runner != Thread.currentThread()) runner.interrupt();
            }
            WORKERS.remove(this);
            if (rejection != null) OVERFLOW.remove(rejection);
            operation.cancel();
            try { socket.close(); } catch (IOException ignored) {}
            cancelTimers();
            connections.remove(this);
            if (!running) finish();
        }

        private void cancelTimers() {
            if (queueTimeout != null) queueTimeout.cancel(false);
            if (deadline != null) deadline.cancel(false);
        }

        private void finish() {
            synchronized (lifecycle) {
                if (finished) return;
                finished = true;
                runner = null;
            }
            cancelTimers();
            connections.remove(this);
            operation.cancel();
            try { socket.close(); } catch (IOException ignored) {}
            FileLog.d("ChatGPT bridge #" + id + " route=" + route + " status=" + responseStatus
                    + " outcome=" + (cancelled && "ok".equals(outcome) ? "cancelled" : outcome)
                    + " ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - accepted)
                    + " active=" + WORKERS.getActiveCount() + " queued=" + WORKERS.getQueue().size());
        }

        @Override
        public void run() {
            synchronized (lifecycle) {
                if (cancelled || finished) return;
                runner = Thread.currentThread();
                if (queueTimeout != null) queueTimeout.cancel(false);
            }
            try {
                socket.setSoTimeout(10000);
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                HttpRequest request = readRequest(input);
                if (!request.headers.getOrDefault("host", "").equals("127.0.0.1:" + socket.getLocalPort())) {
                    reply(400, error("Invalid localhost host", "invalid_request"));
                    return;
                }
                String token = secret;
                if (token == null || !ChatGptIdToken.constantEquals("Bearer " + token, request.headers.get("authorization"))) {
                    reply(401, error(LocaleController.getString(R.string.OEChatGptSessionChanged), "invalid_local_session"));
                    return;
                }
                if (request.headers.containsKey("origin")) {
                    reply(403, error("Browser requests are not supported", "invalid_origin"));
                    return;
                }
                watchDisconnect(input);
                if ("GET".equals(request.method) && "/v1/models".equals(request.path)) {
                    route = "models";
                    List<ModelInfo> models = ChatGptClient.models(operation);
                    JSONArray data = new JSONArray();
                    for (ModelInfo model : models) data.put(new JSONObject().put("id", model.getId())
                            .put("object", "model").put("display_name", model.getName()).put("context_window", model.getContext()));
                    reply(200, new JSONObject().put("object", "list").put("data", data));
                } else if ("POST".equals(request.method) && "/v1/chat/completions".equals(request.path)) {
                    route = "chat";
                    JSONObject body = new JSONObject(new String(request.body, StandardCharsets.UTF_8));
                    streaming = body.optBoolean("stream", false);
                    ChatGptClient.Round result = ChatGptClient.chat(operation, body, new GenerationCallback() {
                        public void onChunk(String text) {
                            if (streaming) writeDelta("content", text);
                        }
                        public void onReasoning(String text) {
                            reasoning.append(text);
                            if (streaming) writeDelta("reasoning_content", text);
                        }
                        public void onResponse(String text) {}
                        public void onError(int code, String message) {}
                    });
                    JSONObject completion = completion(result, body.optString("model"), responseId, reasoning.toString());
                    if (streaming) {
                        String visible = forwarded.toString();
                        if (!result.text.startsWith(visible)) throw new IOException("Inconsistent ChatGPT stream");
                        if (result.text.length() > visible.length()) writeDelta("content", result.text.substring(visible.length()));
                        JSONObject message = completion.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                        JSONArray calls = message.optJSONArray("tool_calls");
                        if (calls != null && calls.length() > 0) {
                            sse(new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                                    .put("delta", new JSONObject().put("tool_calls", calls)))).toString());
                        }
                        sse("[DONE]");
                    } else reply(200, completion);
                } else reply(404, error("Unsupported localhost route", "not_found"));
            } catch (Exception e) {
                String code = e instanceof ChatGptClient.Failure ? ((ChatGptClient.Failure) e).code : "chatgpt_request_failed";
                if (!code.matches("[a-z][a-z0-9_]{0,99}")) code = "chatgpt_request_failed";
                if (!cancelled) outcome = code + ":" + e.getClass().getSimpleName();
                if (!socket.isClosed()) {
                    try {
                        String message = ChatGptClient.safeError(e);
                        JSONObject failure = error(message, code);
                        if (headersSent) sse(failure.toString());
                        else reply(status(e), failure);
                    } catch (Exception ignored) {}
                }
            } finally {
                finish();
            }
        }

        private void watchDisconnect(BufferedInputStream input) throws IOException {
            socket.setSoTimeout(1000);
            Thread watcher = new Thread(() -> {
                int idle = 0;
                while (!finished && !socket.isClosed()) {
                    try {
                        if (input.read() == -1 || !finished) cancel();
                        return;
                    } catch (SocketTimeoutException ignored) {
                        if (streaming && ++idle >= 10) {
                            try { heartbeat(); idle = 0; }
                            catch (IOException e) { cancel(); return; }
                        }
                    } catch (IOException e) {
                        if (!finished) cancel();
                        return;
                    }
                }
            }, "ChatGPT localhost cancellation");
            watcher.setDaemon(true);
            watcher.start();
        }

        private void writeDelta(String field, String text) {
            try {
                sse(new JSONObject().put("id", responseId).put("object", "chat.completion.chunk")
                        .put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                                .put("delta", new JSONObject().put(field, text)))).toString());
                if ("content".equals(field)) forwarded.append(text);
            } catch (Exception e) {
                cancel();
                throw new java.util.concurrent.CancellationException();
            }
        }

        private synchronized void sse(String data) throws IOException {
            streamHeaders();
            write("data: " + data + "\n\n");
        }

        private synchronized void heartbeat() throws IOException {
            if (finished) return;
            streamHeaders();
            write(": keepalive\n\n");
        }

        private void streamHeaders() throws IOException {
            if (!headersSent) {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n"
                        + "Cache-Control: no-store\r\nConnection: close\r\n\r\n");
                headersSent = true;
                responseStatus = 200;
            }
        }

        private synchronized void reply(int status, JSONObject json) throws IOException {
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            write("HTTP/1.1 " + status + (status == 200 ? " OK" : " Error")
                    + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + bytes.length
                    + "\r\nCache-Control: no-store\r\nConnection: close\r\n"
                    + (status == 503 ? "Retry-After: 2\r\n" : "") + "\r\n");
            headersSent = true;
            responseStatus = status;
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        }

        private void write(String value) throws IOException {
            socket.getOutputStream().write(value.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }
    }

    private record HttpRequest(String method, String path, HashMap<String, String> headers, byte[] body) {}

    private static HttpRequest readRequest(BufferedInputStream input) throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int ending = 0;
        while (header.size() < 16384 && ending != 0x0d0a0d0a) {
            int next = input.read();
            if (next < 0) throw new IOException();
            header.write(next);
            ending = (ending << 8) | next;
        }
        if (ending != 0x0d0a0d0a) throw new IOException();
        String[] lines = header.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
        String[] start = lines[0].split(" ");
        if (start.length != 3) throw new IOException();
        HashMap<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int separator = lines[i].indexOf(':');
            if (separator <= 0) throw new IOException();
            String name = lines[i].substring(0, separator).trim().toLowerCase(Locale.ROOT);
            if (headers.put(name, lines[i].substring(separator + 1).trim()) != null) throw new IOException();
        }
        if (headers.containsKey("transfer-encoding")) throw new IOException();
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        if (length < 0 || length > MAX_BODY) throw new IOException();
        byte[] body = new byte[length];
        int received = 0;
        while (received < length) {
            int count = input.read(body, received, length - received);
            if (count < 0) throw new IOException();
            received += count;
        }
        return new HttpRequest(start[0], start[1].split("\\?", 2)[0], headers, body);
    }

    static JSONObject completion(ChatGptClient.Round result, String model, String id, String reasoning) throws Exception {
        JSONArray calls = new JSONArray();
        for (int i = 0; i < result.output.length(); i++) {
            JSONObject item = result.output.getJSONObject(i);
            if (!"function_call".equals(item.optString("type"))) continue;
            String name = item.getString("name");
            if (name.startsWith("exteraless.")) name = name.substring("exteraless.".length());
            JSONObject call = new JSONObject().put("id", item.getString("call_id")).put("type", "function")
                    .put("function", new JSONObject().put("name", name).put("arguments", item.optString("arguments", "{}")));
            if (calls.length() == 0) call.put("chatgpt_output", result.output);
            calls.put(call);
        }
        JSONObject message = new JSONObject().put("role", "assistant")
                .put("content", calls.length() > 0 && result.text.isEmpty() ? JSONObject.NULL : result.text);
        if (calls.length() > 0) message.put("tool_calls", calls);
        if (!reasoning.isEmpty()) message.put("reasoning_content", reasoning);
        return new JSONObject().put("id", id).put("object", "chat.completion").put("model", model)
                .put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("message", message)
                        .put("finish_reason", calls.length() == 0 ? "stop" : "tool_calls")));
    }

    private static int status(Exception error) {
        if (error instanceof ChatGptAuth.Unavailable) return 401;
        if (error instanceof ChatGptClient.Failure failure) {
            if (failure.status >= 400) return failure.status;
            if (failure.code.contains("usage_limit") || failure.code.contains("usage_unavailable")) return 429;
        }
        return 502;
    }

    private static JSONObject error(String message, String code) throws Exception {
        return new JSONObject().put("error", new JSONObject().put("message", message).put("code", code));
    }
}
