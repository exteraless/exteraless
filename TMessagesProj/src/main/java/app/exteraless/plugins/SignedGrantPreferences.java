package app.exteraless.plugins;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.telegram.messenger.FileLog;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

final class SignedGrantPreferences implements SharedPreferences {

    private static final String KEY_ALIAS = "exteraless_plugin_grants_v1";
    private static final String SIG_PREFIX = "sig:";
    private static final String KEY_SEALED = "sealed_v1";

    private final SharedPreferences delegate;
    private final Predicate<String> grantKeyFilter;
    private final Map<String, Boolean> verified = new ConcurrentHashMap<>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private volatile SecretKey key;
    private volatile boolean unavailable;

    private SignedGrantPreferences(SharedPreferences delegate, Predicate<String> grantKeyFilter) {
        this.delegate = delegate;
        this.grantKeyFilter = grantKeyFilter;
    }

    static SharedPreferences wrap(SharedPreferences store, Predicate<String> isGrantKey) {
        return new SignedGrantPreferences(store, isGrantKey);
    }

    static void warmUp(SharedPreferences store) {
        if (store instanceof SignedGrantPreferences) {
            store.getAll();
        }
    }

    private SecretKey key() {
        SecretKey current = key;
        if (current != null || unavailable) {
            return current;
        }
        synchronized (this) {
            if (key != null || unavailable) {
                return key;
            }
            try {
                KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);
                boolean existed = keyStore.containsAlias(KEY_ALIAS);
                key = existed ? (SecretKey) keyStore.getKey(KEY_ALIAS, null) : createKey();
                if (!existed) {
                    seal(delegate.getBoolean(KEY_SEALED, false));
                }
            } catch (Throwable t) {
                unavailable = true;
                FileLog.e("SignedGrantPreferences: keystore unavailable, grants are denied", t);
            }
            return key;
        }
    }

    private static SecretKey createKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build());
        return generator.generateKey();
    }

    private void seal(boolean wasSealed) {
        Editor editor = delegate.edit();
        int count = 0;
        for (Map.Entry<String, ?> entry : delegate.getAll().entrySet()) {
            String name = entry.getKey();
            if (name == null || name.startsWith(SIG_PREFIX) || !isGrant(name)) {
                continue;
            }
            if (wasSealed) {
                editor.remove(name).remove(SIG_PREFIX + name);
            } else {
                String signature = sign(name, entry.getValue());
                if (signature != null) {
                    editor.putString(SIG_PREFIX + name, signature);
                }
            }
            count++;
        }
        editor.putBoolean(KEY_SEALED, true).commit();
        if (wasSealed) {
            FileLog.e("SignedGrantPreferences: signing key is gone, dropped " + count + " plugin grant records");
        } else {
            FileLog.d("SignedGrantPreferences: signed " + count + " plugin grant records");
        }
    }

    private boolean isGrant(String name) {
        return grantKeyFilter.test(name);
    }

    private static String payload(String name, Object value) {
        String type = value instanceof Boolean ? "b" : value instanceof Integer ? "i"
                : value instanceof Long ? "l" : value instanceof Float ? "f" : "s";
        return name + '\u0000' + type + '\u0000' + value;
    }

    private String sign(String name, Object value) {
        try {
            SecretKey secret = key();
            if (secret == null) {
                return null;
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secret);
            byte[] digest = mac.doFinal(payload(name, value).getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(digest, Base64.NO_WRAP);
        } catch (Throwable t) {
            FileLog.e("SignedGrantPreferences: cannot sign " + name, t);
            return null;
        }
    }

    private boolean valid(String name, Object value) {
        if (value == null || !isGrant(name)) {
            return true;
        }
        if (key() == null) {
            report(name);
            return false;
        }
        String signature = delegate.getString(SIG_PREFIX + name, null);
        if (signature == null) {
            report(name);
            return false;
        }
        String cacheKey = payload(name, value) + '\u0000' + signature;
        Boolean cached = verified.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String expected = sign(name, value);
        boolean ok = expected != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        verified.put(cacheKey, ok);
        if (!ok) {
            report(name);
        }
        return ok;
    }

    private void report(String name) {
        if (reported.add(name)) {
            FileLog.e("SignedGrantPreferences: ignoring unsigned or tampered record " + name);
        }
    }

    @Override
    public Map<String, ?> getAll() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, ?> entry : delegate.getAll().entrySet()) {
            String name = entry.getKey();
            if (name == null || name.startsWith(SIG_PREFIX)) {
                continue;
            }
            if (valid(name, entry.getValue())) {
                out.put(name, entry.getValue());
            }
        }
        return out;
    }

    @Override
    public String getString(String name, String defValue) {
        String value = delegate.getString(name, null);
        return value != null && valid(name, value) ? value : defValue;
    }

    @Override
    public Set<String> getStringSet(String name, Set<String> defValues) {
        return isGrant(name) ? defValues : delegate.getStringSet(name, defValues);
    }

    @Override
    public int getInt(String name, int defValue) {
        if (!delegate.contains(name)) {
            return defValue;
        }
        int value = delegate.getInt(name, defValue);
        return valid(name, value) ? value : defValue;
    }

    @Override
    public long getLong(String name, long defValue) {
        if (!delegate.contains(name)) {
            return defValue;
        }
        long value = delegate.getLong(name, defValue);
        return valid(name, value) ? value : defValue;
    }

    @Override
    public float getFloat(String name, float defValue) {
        if (!delegate.contains(name)) {
            return defValue;
        }
        float value = delegate.getFloat(name, defValue);
        return valid(name, value) ? value : defValue;
    }

    @Override
    public boolean getBoolean(String name, boolean defValue) {
        if (!delegate.contains(name)) {
            return defValue;
        }
        boolean value = delegate.getBoolean(name, defValue);
        return valid(name, value) ? value : defValue;
    }

    @Override
    public boolean contains(String name) {
        if (!delegate.contains(name)) {
            return false;
        }
        if (!isGrant(name)) {
            return true;
        }
        Object value = delegate.getAll().get(name);
        return valid(name, value);
    }

    @Override
    public Editor edit() {
        return new SignedEditor(delegate.edit());
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        delegate.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        delegate.unregisterOnSharedPreferenceChangeListener(listener);
    }

    private final class SignedEditor implements Editor {
        private final Editor editor;

        SignedEditor(Editor editor) {
            this.editor = editor;
        }

        private Editor signed(String name, Object value) {
            if (isGrant(name)) {
                String signature = sign(name, value);
                if (signature != null) {
                    editor.putString(SIG_PREFIX + name, signature);
                    verified.put(payload(name, value) + '\u0000' + signature, true);
                } else {
                    editor.remove(name).remove(SIG_PREFIX + name);
                }
            }
            return this;
        }

        @Override
        public Editor putString(String name, String value) {
            editor.putString(name, value);
            return value == null ? remove(name) : signed(name, value);
        }

        @Override
        public Editor putStringSet(String name, Set<String> values) {
            if (!isGrant(name)) {
                editor.putStringSet(name, values);
            }
            return this;
        }

        @Override
        public Editor putInt(String name, int value) {
            editor.putInt(name, value);
            return signed(name, value);
        }

        @Override
        public Editor putLong(String name, long value) {
            editor.putLong(name, value);
            return signed(name, value);
        }

        @Override
        public Editor putFloat(String name, float value) {
            editor.putFloat(name, value);
            return signed(name, value);
        }

        @Override
        public Editor putBoolean(String name, boolean value) {
            editor.putBoolean(name, value);
            return signed(name, value);
        }

        @Override
        public Editor remove(String name) {
            editor.remove(name);
            if (isGrant(name)) {
                editor.remove(SIG_PREFIX + name);
            }
            return this;
        }

        @Override
        public Editor clear() {
            editor.clear();
            editor.putBoolean(KEY_SEALED, true);
            return this;
        }

        @Override
        public boolean commit() {
            return editor.commit();
        }

        @Override
        public void apply() {
            editor.apply();
        }
    }
}
