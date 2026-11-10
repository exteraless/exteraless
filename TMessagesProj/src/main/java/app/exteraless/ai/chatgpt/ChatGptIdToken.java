package app.exteraless.ai.chatgpt;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;

final class ChatGptIdToken {
    static final String ISSUER = "https://auth.openai.com";

    static JSONObject verify(String token, JSONObject jwks, String clientId, String nonce,
                             String subject, String accessToken) throws Exception {
        if (token.length() > 131072) {
            throw new IllegalArgumentException();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException();
        }
        JSONObject header = json(parts[0]);
        if (!"RS256".equals(header.getString("alg")) || header.has("crit")) {
            throw new IllegalArgumentException();
        }
        String kid = header.getString("kid");
        JSONArray keys = jwks.getJSONArray("keys");
        JSONObject key = null;
        for (int i = 0; i < keys.length(); i++) {
            JSONObject candidate = keys.getJSONObject(i);
            if (kid.equals(candidate.optString("kid")) && "RSA".equals(candidate.optString("kty"))
                    && "sig".equals(candidate.optString("use", "sig"))
                    && "RS256".equals(candidate.optString("alg", "RS256"))) {
                if (candidate.has("key_ops") && !contains(candidate.getJSONArray("key_ops"), "verify")) {
                    continue;
                }
                key = candidate;
                break;
            }
        }
        if (key == null) {
            throw new IllegalArgumentException();
        }
        BigInteger modulus = new BigInteger(1, decode(key.getString("n")));
        BigInteger exponent = new BigInteger(1, decode(key.getString("e")));
        if (modulus.bitLength() < 2048) {
            throw new IllegalArgumentException();
        }
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)));
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(decode(parts[2]))) {
            throw new IllegalArgumentException();
        }
        JSONObject claims = json(parts[1]);
        Object audience = claims.get("aud");
        boolean validAudience = clientId.equals(audience)
                || audience instanceof JSONArray && contains((JSONArray) audience, clientId);
        long now = System.currentTimeMillis() / 1000;
        if (!ISSUER.equals(claims.getString("iss")) || !validAudience
                || claims.getLong("exp") <= now - 5 || claims.getLong("iat") > now + 5
                || claims.optLong("nbf", 0) > now + 5
                || claims.getString("sub").isEmpty()
                || claims.has("azp") && !clientId.equals(claims.getString("azp"))
                || audience instanceof JSONArray && ((JSONArray) audience).length() > 1 && !claims.has("azp")
                || subject != null && !subject.equals(claims.getString("sub"))
                || nonce != null && !nonce.equals(claims.optString("nonce"))) {
            throw new IllegalArgumentException();
        }
        if (claims.has("at_hash")) {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
            if (!constantEquals(claims.getString("at_hash"), encode(Arrays.copyOf(hash, hash.length / 2)))) {
                throw new IllegalArgumentException();
            }
        }
        return claims;
    }

    static boolean constantEquals(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static JSONObject json(String value) throws Exception {
        return new JSONObject(new String(decode(value), StandardCharsets.UTF_8));
    }

    private static boolean contains(JSONArray values, String value) {
        for (int i = 0; i < values.length(); i++) {
            if (value.equals(values.optString(i))) {
                return true;
            }
        }
        return false;
    }
}
