package app.exteraless.ai.chatgpt;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ChatGptCredentialStore {
    private static final String KEY_ALIAS = "exteraless.chatgpt.credentials.v1";
    private static final byte[] AAD = KEY_ALIAS.getBytes(StandardCharsets.UTF_8);
    private final AtomicFile file;

    ChatGptCredentialStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "chatgpt-session.json"));
    }

    JSONObject read() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) {
            return new JSONObject();
        }
        JSONObject envelope = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        if (envelope.getInt("version") != 1) {
            throw new IllegalStateException();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false),
                new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
        cipher.updateAAD(AAD);
        byte[] plaintext = cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP));
        return new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
    }

    void write(JSONObject state) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(AAD);
        byte[] ciphertext = cipher.doFinal(state.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject envelope = new JSONObject()
                .put("version", 1)
                .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
        FileOutputStream output = file.startWrite();
        try {
            output.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception e) {
            file.failWrite(output);
            throw e;
        }
    }

    void discard() throws Exception {
        file.delete();
        if (file.getBaseFile().exists()) throw new IllegalStateException();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        if (!create) {
            throw new IllegalStateException();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
