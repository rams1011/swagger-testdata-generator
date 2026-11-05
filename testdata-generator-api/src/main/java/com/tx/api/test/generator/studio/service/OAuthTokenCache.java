package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.dto.RunAuth;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * OAuth access tokens kept in memory until shortly before they expire, so a plan run asks the token server once
 * instead of once per request. The key is a hash of the token URL, grant, client, user, scope and secrets, so a
 * changed secret gets a new token and no secret is kept in clear. A 401 from the API drops the cached token.
 */
final class OAuthTokenCache {

    /** Used when the token response has no {@code expires_in}. */
    static final long DEFAULT_LIFETIME_MS = 300_000L;
    /** Tokens are renewed this long before {@code expires_in} runs out. */
    static final long EARLY_RENEW_MS = 30_000L;
    private static final int MAX_ENTRIES = 500;

    record Token(String accessToken, long expiresAtMillis) {
    }

    interface Fetcher {
        /** Returns the access token and its lifetime in seconds ({@code null} when the server did not say). */
        Fetched fetch(RunAuth auth);
    }

    record Fetched(String accessToken, Long expiresInSeconds) {
    }

    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    OAuthTokenCache() {
        this(System::currentTimeMillis);
    }

    OAuthTokenCache(LongSupplier clock) {
        this.clock = clock;
    }

    /** A cached token that is still valid, or a new one from {@code fetcher}. */
    String token(RunAuth auth, Fetcher fetcher) {
        String key = key(auth);
        long now = clock.getAsLong();
        Token cached = tokens.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.accessToken();
        }
        Fetched fetched = fetcher.fetch(auth);
        long lifetime = fetched.expiresInSeconds() == null || fetched.expiresInSeconds() <= 0
                ? DEFAULT_LIFETIME_MS
                : Math.max(0L, fetched.expiresInSeconds() * 1000L - EARLY_RENEW_MS);
        if (lifetime > 0) {
            if (tokens.size() >= MAX_ENTRIES) {
                tokens.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
                if (tokens.size() >= MAX_ENTRIES) {
                    tokens.clear();
                }
            }
            tokens.put(key, new Token(fetched.accessToken(), now + lifetime));
        }
        return fetched.accessToken();
    }

    /** Drops the cached token for {@code auth} (after the API answered 401 with it). */
    boolean invalidate(RunAuth auth) {
        return auth != null && tokens.remove(key(auth)) != null;
    }

    int size() {
        return tokens.size();
    }

    static String key(RunAuth auth) {
        String raw = String.join("\u0000",
                StringUtils.trimToEmpty(auth.tokenUrl()),
                StringUtils.defaultIfBlank(auth.grantType(), "client_credentials").trim(),
                StringUtils.trimToEmpty(auth.clientId()),
                StringUtils.defaultString(auth.clientSecret()),
                StringUtils.trimToEmpty(auth.username()),
                StringUtils.defaultString(auth.password()),
                StringUtils.trimToEmpty(auth.scope()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
