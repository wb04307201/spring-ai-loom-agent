package cn.wubo.loom.http.core.profile;

import java.util.Base64;
import java.util.Map;

public final class AuthProvider {
    private AuthProvider() {}

    public static void apply(Profile.Auth auth, Map<String, String> headers, Map<String, String> query) {
        if (auth == null || auth.getType() == null) return;
        applyResolved(auth.getType(), auth.getToken(), auth.getUsername(), auth.getPassword(),
                auth.getKeyName(), auth.getValue(), headers, query);
    }

    /**
     * Apply auth using already-resolved primitive values. Use this when the
     * caller has run placeholder expansion on the profile's auth fields
     * (e.g. via {@link cn.wubo.loom.http.core.invoke.HeaderResolver#resolveValue})
     * and wants to write the resolved values into the outbound headers
     * without mutating the cached {@link Profile.Auth} object.
     */
    public static void applyResolved(String type, String token, String username, String password,
                                     String keyName, String value,
                                     Map<String, String> headers, Map<String, String> query) {
        if (type == null) return;
        switch (type) {
            case "bearer" -> headers.put("Authorization", "Bearer " + n(token));
            case "basic" -> headers.put("Authorization",
                "Basic " + Base64.getEncoder().encodeToString(
                    (n(username) + ":" + n(password)).getBytes()));
            case "apiKey-header" -> {
                if (keyName != null) headers.put(keyName, value);
            }
            case "apiKey-query" -> {
                if (keyName != null) query.put(keyName, value);
            }
            default -> { /* unknown type: ignore */ }
        }
    }

    private static String n(String s) { return s == null ? "" : s; }
}