package app.exteraless.ai.chatgpt;

import android.net.Uri;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

final class ChatGptLoopback implements AutoCloseable {
    interface Callback {
        void received(Uri uri);
        void failed();
    }

    private final ServerSocket server;
    private final String state;
    private final String message;
    private final Callback callback;
    private volatile boolean closed;

    ChatGptLoopback(String state, String message, Callback callback) throws IOException {
        this.state = state;
        this.message = message;
        this.callback = callback;
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(1000);
    }

    String redirectUri() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback";
    }

    void start() {
        Thread thread = new Thread(this::listen, "ChatGPT OAuth callback");
        thread.setDaemon(true);
        thread.start();
    }

    private void listen() {
        long deadline = SystemClock.elapsedRealtime() + 10 * 60 * 1000;
        try {
            while (!closed && SystemClock.elapsedRealtime() < deadline) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    Uri uri;
                    try {
                        uri = readCallback(socket);
                    } catch (Exception invalid) {
                        reply(socket, false);
                        continue;
                    }
                    if (uri == null) {
                        reply(socket, false);
                        continue;
                    }
                    try {
                        reply(socket, true);
                    } catch (IOException ignored) {
                        // A browser closing its tab must not discard a verified callback.
                    }
                    if (!closed) {
                        callback.received(uri);
                    }
                    return;
                } catch (SocketTimeoutException ignored) {
                } catch (IOException e) {
                    if (closed) {
                        return;
                    }
                }
            }
            if (!closed) {
                callback.failed();
            }
        } finally {
            close();
        }
    }

    private Uri readCallback(Socket socket) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long deadline = SystemClock.elapsedRealtime() + 3000;
        int ending = 0;
        while (bytes.size() < 16384 && SystemClock.elapsedRealtime() < deadline) {
            int b = socket.getInputStream().read();
            if (b < 0) {
                return null;
            }
            bytes.write(b);
            ending = (ending << 8) | b;
            if (ending == 0x0d0a0d0a) {
                break;
            }
        }
        if (ending != 0x0d0a0d0a) {
            return null;
        }
        String[] lines = bytes.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length != 3 || !"GET".equals(request[0]) || !request[1].startsWith("/auth/callback?")) {
            return null;
        }
        String host = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0 && "host".equalsIgnoreCase(lines[i].substring(0, colon))) {
                if (host != null) {
                    return null;
                }
                host = lines[i].substring(colon + 1).trim();
            }
        }
        if (!("127.0.0.1:" + server.getLocalPort()).equals(host)) {
            return null;
        }
        Uri uri = Uri.parse(redirectUri() + request[1].substring("/auth/callback".length()));
        if (uri.getFragment() != null || !ChatGptIdToken.constantEquals(state, uri.getQueryParameter("state"))) {
            return null;
        }
        for (String key : Set.of("state", "code", "client_id", "error")) {
            if (uri.getQueryParameters(key).size() > 1) {
                return null;
            }
        }
        return uri;
    }

    private void reply(Socket socket, boolean accepted) throws IOException {
        String text = accepted ? message : "Invalid sign-in callback.";
        String html = "<!doctype html><meta charset=\"utf-8\"><title>exteraless</title><p>"
                + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</p>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + (accepted ? "200 OK" : "400 Bad Request") + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n"
                + "Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }
}
