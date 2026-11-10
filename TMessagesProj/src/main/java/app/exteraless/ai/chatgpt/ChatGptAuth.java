package app.exteraless.ai.chatgpt;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.FormBody;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** One installation-wide session owner, independent of Telegram accounts and API-key providers. */
public final class ChatGptAuth {
    private static final String ISSUER = ChatGptIdToken.ISSUER;
    private static final String AUTHORIZE = ISSUER + "/api/accounts/authorize";
    private static final String TOKEN = ISSUER + "/api/accounts/oauth/token";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String DYNAMIC_CLIENT = "dynamic_agent_client";
    private static final String PLAN_SCOPE = "chatgpt.tokens.use.direct";
    private static final String SCOPES = "openid profile email offline_access resource.invoke " + PLAN_SCOPE;
    public static final String USAGE_URL = "https://chatgpt.com/settings/usage";
    private static volatile ChatGptAuth instance;

    public static ChatGptAuth getInstance() {
        if (instance == null) {
            synchronized (ChatGptAuth.class) {
                if (instance == null) {
                    instance = new ChatGptAuth();
                }
            }
        }
        return instance;
    }

    public static final class Account {
        public final String clientId;
        public final String email;
        public final String label;
        public final boolean signedIn;
        public final boolean planEnabled;

        private Account(JSONObject record) {
            clientId = record.optString("client_id");
            email = record.optString("email");
            String name = record.optString("email", record.optString("name", "ChatGPT"));
            if (name.isEmpty()) name = "ChatGPT";
            label = name + " · …" + clientId.substring(Math.max(0, clientId.length() - 8));
            signedIn = !record.optString("id_token").isEmpty();
            planEnabled = signedIn && !record.optString("access_token").isEmpty()
                    && hasScope(record.optString("scope"), PLAN_SCOPE)
                    && hasScope(record.optString("scope"), "resource.invoke");
        }
    }

    public static final class Snapshot {
        public final List<Account> accounts;
        public final String activeClientId;
        public final String deviceName;
        public final boolean busy;
        public final boolean signingIn;
        public final boolean storageFailed;
        public final int error;

        private Snapshot(List<Account> accounts, String activeClientId, String deviceName,
                         boolean busy, boolean signingIn, boolean storageFailed, int error) {
            this.accounts = Collections.unmodifiableList(accounts);
            this.activeClientId = activeClientId;
            this.deviceName = deviceName;
            this.busy = busy;
            this.signingIn = signingIn;
            this.storageFailed = storageFailed;
            this.error = error;
        }

        public Account activeAccount() {
            for (Account account : accounts) {
                if (account.clientId.equals(activeClientId)) return account;
            }
            return null;
        }
    }

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ChatGPT sessions");
        thread.setDaemon(true);
        return thread;
    });
    private final OkHttpClient http = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).build();
    private final ChatGptCredentialStore store = new ChatGptCredentialStore(ApplicationLoader.applicationContext);
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong authEpoch = new AtomicLong();
    private final AtomicLong sessionVersion = new AtomicLong();
    private final java.util.Set<Call> requests = ConcurrentHashMap.newKeySet();
    private final String deviceName = deviceName();
    private volatile Pending pending;
    private volatile Snapshot snapshot = new Snapshot(new ArrayList<>(), "", deviceName, true, false, false, 0);
    // All persisted state and refresh work belong to worker; only pending cancellation crosses threads.
    private JSONObject state;
    private JSONObject discovery;
    private ScheduledFuture<?> refreshTask;
    private int refreshFailures;
    private boolean working;
    private boolean storageFailed;
    private int error;

    private ChatGptAuth() {
        worker.execute(() -> {
            try {
                state = store.read();
                if (!state.has("host_id")) {
                    state.put("host_id", "urn:uuid:" + UUID.randomUUID());
                    state.put("accounts", new JSONArray());
                    store.write(state);
                }
                if (!state.getString("host_id").matches("urn:uuid:[0-9a-fA-F-]{36}")) throw new IllegalStateException();
                JSONArray accounts = state.getJSONArray("accounts");
                JSONObject cleaned = state;
                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject account = accounts.getJSONObject(i);
                    if (!validClientId(account.getString("client_id"))
                            || account.getString("subject").isEmpty() || !ISSUER.equals(account.getString("issuer"))) {
                        throw new IllegalStateException();
                    }
                    // Earlier versions retained personal profile data after explicit sign-out.
                    if (account.optString("id_token").isEmpty() && account.optString("access_token").isEmpty()
                            && account.optString("refresh_token").isEmpty()) {
                        cleaned = withoutAccount(cleaned, account.getString("client_id"));
                    }
                }
                if (cleaned != state) save(cleaned);
                publish();
                scheduleRefresh(0);
            } catch (Exception e) {
                storageFailed = true;
                error = R.string.OEChatGptStorageError;
                publish();
            }
        });
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public boolean isSignedIn() {
        Account account = snapshot.activeAccount();
        return !snapshot.storageFailed && account != null && account.signedIn;
    }

    public boolean canUsePlan() {
        Account account = snapshot.activeAccount();
        return isSignedIn() && account.planEnabled;
    }

    static final class Session {
        final String clientId;
        final String accessToken;
        final long version;

        Session(String clientId, String accessToken, long version) {
            this.clientId = clientId;
            this.accessToken = accessToken;
            this.version = version;
        }
    }

    static final class Unavailable extends IOException {
        Unavailable(int resource) { super(LocaleController.getString(resource)); }
    }

    Session session(String expectedClientId, Session rejected) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("ChatGPT network work requires a background thread");
        try {
            return worker.submit(() -> {
                if (storageFailed || state == null) throw new Unavailable(R.string.OEChatGptStorageError);
                JSONObject account = find(state.optString("active_client_id"));
                if (account == null || expectedClientId != null && !expectedClientId.equals(account.optString("client_id"))) {
                    throw new Unavailable(R.string.OEChatGptSessionChanged);
                }
                if (rejected != null && !current(rejected)) throw new Unavailable(R.string.OEChatGptSessionChanged);
                boolean force = rejected != null && rejected.accessToken.equals(account.optString("access_token"));
                refresh(false, force);
                account = find(state.optString("active_client_id"));
                if (storageFailed || account == null || account.optString("id_token").isEmpty()
                        || account.optString("access_token").isEmpty() || account.optLong("expires_at") <= System.currentTimeMillis()) {
                    throw new Unavailable(error == 0 ? R.string.OEChatGptSessionExpired : error);
                }
                if (!hasScope(account.optString("scope"), PLAN_SCOPE) || !hasScope(account.optString("scope"), "resource.invoke")) {
                    throw new Unavailable(R.string.OEChatGptPlanRequired);
                }
                return new Session(account.getString("client_id"), account.getString("access_token"), sessionVersion.get());
            }).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw new IOException(LocaleController.getString(R.string.OEChatGptNetworkError));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException();
        }
    }

    synchronized boolean current(Session session) {
        return session.version == sessionVersion.get() && session.clientId.equals(snapshot.activeClientId) && isSignedIn();
    }

    synchronized void register(Session session, Call call) throws IOException {
        if (!current(session)) throw new Unavailable(R.string.OEChatGptSessionChanged);
        requests.add(call);
    }

    void unregister(Call call) {
        requests.remove(call);
    }

    private synchronized void stopRequests() {
        sessionVersion.incrementAndGet();
        ChatGptClient.clearCatalog();
        ChatGptProxy.cancelRequests();
        for (Call call : requests) call.cancel();
        requests.clear();
    }

    public void addListener(Runnable listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public void signIn(Activity activity, String clientId) {
        cancelSignIn();
        long epoch = authEpoch.get();
        WeakReference<Activity> activityRef = new WeakReference<>(activity);
        worker.execute(() -> {
            if (storageFailed || epoch != authEpoch.get()) return;
            error = 0;
            Pending attempt = new Pending(epoch);
            try {
                JSONObject selected = find(clientId);
                if (clientId != null && selected == null) throw new Failure(R.string.OEChatGptSignInError);
                attempt.previous = selected == null ? null : copy(selected);
                attempt.clientId = selected == null ? state.optString("pending_client_id", DYNAMIC_CLIENT) : clientId;
                attempt.nonce = random();
                attempt.verifier = random();
                String oauthState = random();
                attempt.listener = new ChatGptLoopback(oauthState,
                        LocaleController.getString(R.string.OEChatGptBrowserReturn), new ChatGptLoopback.Callback() {
                    @Override
                    public void received(Uri uri) {
                        worker.execute(() -> exchange(attempt, uri));
                    }

                    @Override
                    public void failed() {
                        worker.execute(() -> finish(attempt, R.string.OEChatGptTimeout));
                    }
                });
                attempt.redirectUri = attempt.listener.redirectUri();
                Uri.Builder uri = Uri.parse(AUTHORIZE).buildUpon()
                        .appendQueryParameter("client_id", attempt.clientId)
                        .appendQueryParameter("ext_agent_host_id", state.getString("host_id"))
                        .appendQueryParameter("response_type", "code")
                        .appendQueryParameter("redirect_uri", attempt.redirectUri)
                        .appendQueryParameter("scope", SCOPES)
                        .appendQueryParameter("resource", RESOURCE)
                        .appendQueryParameter("state", oauthState)
                        .appendQueryParameter("nonce", attempt.nonce)
                        .appendQueryParameter("code_challenge_method", "S256")
                        .appendQueryParameter("code_challenge", ChatGptIdToken.encode(MessageDigest.getInstance("SHA-256")
                                .digest(attempt.verifier.getBytes(StandardCharsets.US_ASCII))));
                if (DYNAMIC_CLIENT.equals(attempt.clientId)) {
                    uri.appendQueryParameter("agent_name_hint", "exteraless");
                } else if (selected != null) {
                    if (!selected.optString("id_token").isEmpty()) {
                        uri.appendQueryParameter("id_token_hint", selected.getString("id_token"));
                    }
                    if (!selected.optString("email").isEmpty()) {
                        uri.appendQueryParameter("login_hint", selected.getString("email"));
                    }
                    if (!hasScope(selected.optString("scope"), PLAN_SCOPE)) {
                        uri.appendQueryParameter("prompt", "consent");
                    }
                }
                synchronized (this) {
                    if (epoch != authEpoch.get()) {
                        attempt.listener.close();
                        return;
                    }
                    pending = attempt;
                }
                attempt.listener.start();
                publish();
                Uri authorizationUri = uri.build();
                AndroidUtilities.runOnUIThread(() -> {
                    if (!current(attempt)) return;
                    Activity owner = activityRef.get();
                    try {
                        if (owner == null || owner.isFinishing() || owner.isDestroyed()) throw new IllegalStateException();
                        // Do not use Browser.openUrl: authorization URLs contain a retained ID-token hint.
                        owner.startActivity(new Intent(Intent.ACTION_VIEW, authorizationUri).addCategory(Intent.CATEGORY_BROWSABLE));
                    } catch (Exception e) {
                        worker.execute(() -> finish(attempt, R.string.OEChatGptBrowserError));
                    }
                });
            } catch (Exception e) {
                if (attempt.listener != null) attempt.listener.close();
                error = failureMessage(e, R.string.OEChatGptSignInError);
                publish();
            }
        });
    }

    public synchronized void cancelSignIn() {
        authEpoch.incrementAndGet();
        Pending attempt = pending;
        pending = null;
        if (attempt != null) attempt.listener.close();
        worker.execute(this::publish);
    }

    public void selectAccount(String clientId) {
        stopRequests();
        cancelSignIn();
        worker.execute(() -> {
            if (storageFailed || find(clientId) == null) return;
            try {
                stopRequests();
                save(copy(state).put("active_client_id", clientId));
                error = 0;
                refreshFailures = 0;
                scheduleRefresh(0);
            } catch (Exception e) {
                error = R.string.OEChatGptStorageError;
            }
            publish();
        });
    }

    public void refreshSession() {
        worker.execute(() -> refresh(true));
    }

    public void signOut(String clientId) {
        stopRequests();
        cancelSignIn();
        worker.execute(() -> {
            JSONObject account = find(clientId);
            if (storageFailed || account == null) return;
            stopRequests();
            working = true;
            error = 0;
            try {
                save(withoutAccount(state, clientId));
                refreshFailures = 0;
                scheduleRefresh(-1);
            } catch (Exception e) {
                working = false;
                error = R.string.OEChatGptStorageError;
                publish();
                return;
            }
            publish();
            boolean revoked = account.optString("refresh_token").isEmpty() && account.optString("access_token").isEmpty();
            if (!account.optString("refresh_token").isEmpty()) {
                for (int attempt = 0; attempt < 3 && !revoked; attempt++) {
                    try {
                        Request request = new Request.Builder().url(endpoint("revocation_endpoint"))
                                .post(new FormBody.Builder().add("token", account.getString("refresh_token"))
                                        .add("token_type_hint", "refresh_token").add("client_id", clientId).build()).build();
                        try (Response response = http.newCall(request).execute()) {
                            revoked = response.code() == 200;
                            if (!revoked && response.code() < 500 && response.code() != 429) break;
                        }
                    } catch (Exception ignored) {
                    }
                    if (!revoked && attempt < 2) {
                        try {
                            Thread.sleep(500L << attempt);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            error = revoked ? 0 : R.string.OEChatGptRevocationUnconfirmed;
            working = false;
            scheduleRefresh(-1);
            publish();
        });
    }

    public void resetUnreadableStorage() {
        worker.execute(() -> {
            if (!storageFailed) return;
            try {
                store.discard();
                JSONObject fresh = new JSONObject().put("host_id", "urn:uuid:" + UUID.randomUUID())
                        .put("accounts", new JSONArray());
                save(fresh);
                storageFailed = false;
                error = 0;
            } catch (Exception e) {
                error = R.string.OEChatGptStorageError;
            }
            publish();
        });
    }

    private void exchange(Pending attempt, Uri uri) {
        if (!current(attempt)) return;
        try {
            if (uri.getQueryParameter("error") != null) {
                throw new Failure("access_denied".equals(uri.getQueryParameter("error"))
                        ? R.string.OEChatGptConsentDenied : R.string.OEChatGptSignInError);
            }
            String returnedClient = uri.getQueryParameter("client_id");
            String code = uri.getQueryParameter("code");
            if (DYNAMIC_CLIENT.equals(attempt.clientId)) {
                if (!validClientId(returnedClient)) {
                    throw new Failure(R.string.OEChatGptInvalidCallback);
                }
                attempt.clientId = returnedClient;
                // Retain an issued registration even if its single-use code expires during exchange.
                save(copy(state).put("pending_client_id", returnedClient));
            } else if (returnedClient != null && !attempt.clientId.equals(returnedClient)) {
                throw new Failure(R.string.OEChatGptInvalidCallback);
            }
            if (code == null || code.isEmpty()) throw new Failure(R.string.OEChatGptInvalidCallback);
            JSONObject tokens = jsonRequest(new Request.Builder().url(TOKEN).post(new FormBody.Builder()
                    .add("grant_type", "authorization_code").add("client_id", attempt.clientId)
                    .add("code", code).add("code_verifier", attempt.verifier)
                    .add("redirect_uri", attempt.redirectUri).add("resource", RESOURCE).build()).build());
            JSONObject claims = verify(tokens.getString("id_token"), attempt.clientId, attempt.nonce,
                    attempt.previous == null ? null : attempt.previous.getString("subject"), tokens.optString("access_token"));
            JSONObject existing = find(attempt.clientId);
            if (existing != null && !existing.getString("subject").equals(claims.getString("sub"))) {
                throw new Failure(R.string.OEChatGptIdentityMismatch);
            }
            JSONObject record = new JSONObject().put("client_id", attempt.clientId)
                    .put("subject", claims.getString("sub")).put("issuer", ISSUER)
                    .put("email", claims.optString("email")).put("name", claims.optString("name"))
                    .put("nonce", attempt.nonce);
            applyTokens(record, tokens, false);
            synchronized (this) {
                if (!current(attempt)) return;
                stopRequests();
                replace(record, true);
                pending = null;
            }
            error = 0;
            refreshFailures = 0;
            scheduleRefresh(-1);
            publish();
        } catch (Exception e) {
            finish(attempt, failureMessage(e, R.string.OEChatGptSignInError));
        }
    }

    private void refresh(boolean explicit) {
        refresh(explicit, false);
    }

    private void refresh(boolean explicit, boolean force) {
        if (storageFailed) return;
        JSONObject account = find(state.optString("active_client_id"));
        if (account == null || account.optString("id_token").isEmpty()) return;
        if (!force && account.optLong("expires_at") > System.currentTimeMillis() + 60000) {
            if (explicit) {
                error = 0;
                publish();
            }
            scheduleRefresh(-1);
            return;
        }
        if (pending != null) {
            scheduleRefresh(30000);
            return;
        }
        working = true;
        error = 0;
        publish();
        try {
            if (account.optString("refresh_token").isEmpty()) {
                throw new Failure(R.string.OEChatGptSessionExpired, true);
            }
            JSONObject tokens = jsonRequest(new Request.Builder().url(TOKEN).post(new FormBody.Builder()
                    .add("grant_type", "refresh_token").add("client_id", account.getString("client_id"))
                    .add("refresh_token", account.getString("refresh_token")).add("resource", RESOURCE).build()).build());
            JSONObject renewed = copy(account);
            if (tokens.has("id_token")) {
                JSONObject claims = verify(tokens.getString("id_token"), account.getString("client_id"), null,
                        account.getString("subject"), tokens.getString("access_token"));
                if (claims.has("nonce") && !ChatGptIdToken.constantEquals(account.optString("nonce"), claims.getString("nonce"))) {
                    throw new Failure(R.string.OEChatGptIdentityMismatch);
                }
                renewed.put("email", claims.optString("email", account.optString("email")))
                        .put("name", claims.optString("name", account.optString("name")));
            }
            applyTokens(renewed, tokens, true);
            replace(renewed, false);
            refreshFailures = 0;
            scheduleRefresh(-1);
        } catch (Exception e) {
            error = failureMessage(e, R.string.OEChatGptNetworkError);
            if (e instanceof Failure && ((Failure) e).terminal) {
                try {
                    stopRequests();
                    replace(clearTokens(copy(account)), false);
                } catch (Exception storageError) {
                    error = R.string.OEChatGptStorageError;
                }
            } else {
                scheduleRefresh(Math.min(900000L, 30000L << Math.min(refreshFailures++, 5)));
            }
        } finally {
            working = false;
            publish();
        }
    }

    private void scheduleRefresh(long delay) {
        if (refreshTask != null) refreshTask.cancel(false);
        refreshTask = null;
        if (state == null || storageFailed) return;
        JSONObject account = find(state.optString("active_client_id"));
        if (account == null || account.optString("id_token").isEmpty()) return;
        long wait = delay >= 0 ? delay : Math.max(1000, account.optLong("expires_at") - System.currentTimeMillis() - 60000);
        refreshTask = worker.schedule(() -> refresh(false), wait, TimeUnit.MILLISECONDS);
    }

    private void applyTokens(JSONObject record, JSONObject tokens, boolean refresh) throws Exception {
        for (String key : new String[]{"access_token", "refresh_token", "id_token", "scope"}) {
            if (tokens.has(key) && !(tokens.get(key) instanceof String)) throw new Failure(R.string.OEChatGptSignInError);
        }
        if (!tokens.optString("access_token").isEmpty() && !"Bearer".equalsIgnoreCase(tokens.optString("token_type"))) {
            throw new Failure(R.string.OEChatGptSignInError);
        }
        long expiry = tokens.optLong("expires_in", 3600);
        if (expiry < 1 || expiry > 604800) throw new Failure(R.string.OEChatGptSignInError);
        if (refresh && tokens.optString("access_token").isEmpty()) throw new Failure(R.string.OEChatGptSignInError);
        for (String key : new String[]{"access_token", "refresh_token", "id_token", "scope", "earliest_refresh_at"}) {
            if (tokens.has(key)) record.put(key, tokens.get(key));
            else if (!refresh) record.remove(key);
        }
        if (refresh && record.optString("access_token").isEmpty()) throw new Failure(R.string.OEChatGptSignInError);
        record.put("expires_at", System.currentTimeMillis() + expiry * 1000);
    }

    private JSONObject verify(String token, String clientId, String nonce, String subject, String accessToken) throws Exception {
        JSONObject jwks = jsonRequest(new Request.Builder().url(endpoint("jwks_uri")).build());
        try {
            return ChatGptIdToken.verify(token, jwks, clientId, nonce, subject, accessToken);
        } catch (Exception e) {
            throw new Failure(subject == null ? R.string.OEChatGptInvalidIdentity : R.string.OEChatGptIdentityMismatch);
        }
    }

    private String endpoint(String key) throws Exception {
        if (discovery == null) {
            JSONObject config = jsonRequest(new Request.Builder().url(ISSUER + "/.well-known/openid-configuration").build());
            if (!ISSUER.equals(config.getString("issuer"))) throw new Failure(R.string.OEChatGptInvalidIdentity);
            discovery = config;
        }
        String value = discovery.getString(key);
        Uri uri = Uri.parse(value);
        if (!"https".equals(uri.getScheme()) || !"auth.openai.com".equals(uri.getHost())
                || uri.getPort() != -1 && uri.getPort() != 443 || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getQuery() != null) {
            throw new Failure(R.string.OEChatGptInvalidIdentity);
        }
        return value;
    }

    private JSONObject jsonRequest(Request request) throws Exception {
        try (Response response = http.newCall(request).execute()) {
            if (response.body() == null) throw new IOException();
            var source = response.body().source();
            source.request(1048577);
            if (source.getBuffer().size() > 1048576) throw new IOException();
            String body = source.readUtf8();
            JSONObject json;
            try {
                json = new JSONObject(body);
            } catch (Exception invalid) {
                throw new Failure(R.string.OEChatGptNetworkError);
            }
            if (!response.isSuccessful()) {
                Object rawError = json.opt("error");
                String code = rawError instanceof JSONObject ? ((JSONObject) rawError).optString("code") : json.optString("error");
                boolean terminal = List.of("invalid_grant", "invalid_refresh_token", "token_expired",
                        "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused").contains(code);
                throw new Failure(terminal ? R.string.OEChatGptSessionExpired : R.string.OEChatGptNetworkError, terminal);
            }
            return json;
        }
    }

    private JSONObject find(String clientId) {
        if (state == null || clientId == null) return null;
        JSONArray accounts = state.optJSONArray("accounts");
        if (accounts == null) return null;
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject record = accounts.optJSONObject(i);
            if (record != null && clientId.equals(record.optString("client_id"))) return record;
        }
        return null;
    }

    private void replace(JSONObject record, boolean activate) throws Exception {
        JSONObject next = copy(state);
        JSONArray accounts = next.getJSONArray("accounts");
        String id = record.getString("client_id");
        boolean found = false;
        for (int i = 0; i < accounts.length(); i++) {
            if (id.equals(accounts.getJSONObject(i).getString("client_id"))) {
                accounts.put(i, record);
                found = true;
                break;
            }
        }
        if (!found) accounts.put(record);
        if (activate) {
            next.put("active_client_id", id);
            next.remove("pending_client_id");
        }
        save(next);
    }

    private void save(JSONObject next) throws Exception {
        try {
            store.write(next);
        } catch (Exception e) {
            storageFailed = true;
            throw new Failure(R.string.OEChatGptStorageError);
        }
        state = next;
    }

    static JSONObject withoutAccount(JSONObject state, String clientId) throws Exception {
        JSONObject next = copy(state);
        JSONArray kept = new JSONArray();
        JSONArray accounts = next.getJSONArray("accounts");
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.getJSONObject(i);
            if (!clientId.equals(account.optString("client_id"))) kept.put(account);
        }
        next.put("accounts", kept);
        if (clientId.equals(next.optString("active_client_id"))) next.remove("active_client_id");
        if (clientId.equals(next.optString("pending_client_id"))) next.remove("pending_client_id");
        return next;
    }

    private static JSONObject clearTokens(JSONObject record) {
        for (String key : new String[]{"access_token", "refresh_token", "id_token", "nonce", "scope", "expires_at", "earliest_refresh_at"}) {
            record.remove(key);
        }
        return record;
    }

    private synchronized boolean current(Pending attempt) {
        return pending == attempt && attempt.epoch == authEpoch.get();
    }

    private void finish(Pending attempt, int failure) {
        synchronized (this) {
            if (!current(attempt)) return;
            attempt.listener.close();
            pending = null;
        }
        error = failure;
        publish();
    }

    private void publish() {
        ArrayList<Account> accounts = new ArrayList<>();
        if (state != null && !storageFailed) {
            JSONArray records = state.optJSONArray("accounts");
            if (records != null) {
                for (int i = 0; i < records.length(); i++) {
                    JSONObject record = records.optJSONObject(i);
                    if (record != null) accounts.add(new Account(record));
                }
            }
        }
        String activeId = state == null ? "" : state.optString("active_client_id");
        boolean connected = false;
        for (Account account : accounts) if (account.clientId.equals(activeId) && account.signedIn) connected = true;
        if (connected) {
            try { ChatGptProxy.start(); }
            catch (IOException e) { error = R.string.OEChatGptNetworkError; }
        } else ChatGptProxy.stop();
        snapshot = new Snapshot(accounts, activeId,
                deviceName, working || pending != null, pending != null, storageFailed, error);
        AndroidUtilities.runOnUIThread(() -> {
            for (Runnable listener : listeners) listener.run();
        });
    }

    private static JSONObject copy(JSONObject value) throws Exception {
        return new JSONObject(value.toString());
    }

    private static boolean hasScope(String scopes, String scope) {
        for (String part : scopes.split("\\s+")) {
            if (scope.equals(part)) return true;
        }
        return false;
    }

    private static boolean validClientId(String value) {
        return value != null && !value.trim().isEmpty() && value.length() <= 512 && !DYNAMIC_CLIENT.equals(value);
    }

    private static String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return ChatGptIdToken.encode(bytes);
    }

    private static int failureMessage(Exception error, int fallback) {
        return error instanceof Failure ? ((Failure) error).message : error instanceof IOException ? R.string.OEChatGptNetworkError : fallback;
    }

    private static String deviceName() {
        try {
            String value = Settings.Global.getString(ApplicationLoader.applicationContext.getContentResolver(), "device_name");
            if (value != null && !value.trim().isEmpty()) return value.trim();
        } catch (Exception ignored) {
        }
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        return model.regionMatches(true, 0, manufacturer, 0, manufacturer.length()) ? model : (manufacturer + " " + model).trim();
    }

    private static final class Pending {
        final long epoch;
        String clientId;
        String nonce;
        String verifier;
        String redirectUri;
        JSONObject previous;
        ChatGptLoopback listener;

        Pending(long epoch) {
            this.epoch = epoch;
        }
    }

    private static final class Failure extends Exception {
        final int message;
        final boolean terminal;

        Failure(int message) {
            this(message, false);
        }

        Failure(int message, boolean terminal) {
            this.message = message;
            this.terminal = terminal;
        }
    }
}
