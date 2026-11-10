package com.tx.api.test.generator.studio.tenant.github;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.tenant.ForbiddenException;
import com.tx.api.test.generator.studio.tenant.TenantStore;
import com.tx.api.test.generator.studio.tenant.UnauthenticatedException;
import com.tx.api.test.generator.studio.tenant.oidc.OidcAuthService;
import com.tx.api.test.generator.studio.tenant.oidc.OidcExchangeRecord;
import com.tx.api.test.generator.studio.tenant.oidc.OidcPendingState;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class GitHubAuthService {

    public static final String ISSUER = "https://github.com";
    private static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_URL = "https://api.github.com/user";
    private static final String EMAILS_URL = "https://api.github.com/user/emails";
    private static final String SCOPE = "read:user user:email";

    private static final Gson JSON = new Gson();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration EXCHANGE_TTL = Duration.ofMinutes(10);

    private final StudioProperties properties;
    private final TenantStore tenantStore;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    private final SecureRandom random = new SecureRandom();

    public GitHubAuthService(StudioProperties properties, TenantStore tenantStore) {
        this.properties = properties;
        this.tenantStore = tenantStore;
    }

    public boolean enabled() {
        return properties.isGithubEnabled();
    }

    public String authorizationUrl(String intent, String returnTo) {
        requireEnabled();
        String normalizedIntent = normalizeIntent(intent);
        String safeReturn = sanitizeReturnTo(returnTo);
        StudioProperties.Github github = properties.getAuth().getGithub();
        String state = randomToken();
        String verifier = randomToken();
        String challenge = pkceChallenge(verifier);
        tenantStore.saveOidcState(new OidcPendingState(
                state, verifier, randomToken(), normalizedIntent, safeReturn, Instant.now().plus(STATE_TTL)));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", github.getClientId().trim());
        params.put("redirect_uri", github.resolvedRedirectUri());
        params.put("scope", SCOPE);
        params.put("state", state);
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");
        StringBuilder url = new StringBuilder(AUTHORIZE_URL).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                url.append('&');
            }
            first = false;
            url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            url.append('=');
            url.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    public String completeCallback(String code, String state, String error, String errorDescription) {
        requireEnabled();
        StudioProperties.Github github = properties.getAuth().getGithub();
        String uiBase = github.resolvedUiBase();
        if (StringUtils.isNotBlank(error)) {
            String message = StringUtils.defaultIfBlank(errorDescription, error);
            return uiBase + "/login/oidc?error=" + encode(message);
        }
        if (StringUtils.isBlank(code) || StringUtils.isBlank(state)) {
            return uiBase + "/login/oidc?error=" + encode("Sign-in was cancelled or incomplete");
        }
        OidcPendingState pending = tenantStore.consumeOidcState(state.trim())
                .orElseThrow(() -> new UnauthenticatedException("Sign-in expired — try again"));
        OidcAuthService.OidcUser user = exchangeCode(code.trim(), pending.codeVerifier());
        boolean needsRegistration = needsRegistration(user, pending.intent());
        String exchangeId = UUID.randomUUID().toString();
        tenantStore.saveOidcExchange(new OidcExchangeRecord(
                exchangeId,
                pending.intent(),
                user.email(),
                user.displayName(),
                user.issuer(),
                user.subject(),
                needsRegistration,
                Instant.now().plus(EXCHANGE_TTL)));
        String redirect = uiBase + "/login/oidc?exchange=" + encode(exchangeId);
        if (StringUtils.isNotBlank(pending.returnTo())) {
            redirect += "&returnTo=" + encode(pending.returnTo());
        }
        return redirect;
    }

    private OidcAuthService.OidcUser exchangeCode(String code, String verifier) {
        StudioProperties.Github github = properties.getAuth().getGithub();
        String body = formBody(Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", github.resolvedRedirectUri(),
                "client_id", github.getClientId().trim(),
                "client_secret", github.getClientSecret().trim(),
                "code_verifier", verifier));
        JsonObject tokenResponse = postForm(TOKEN_URL, body);
        String accessToken = textOrNull(tokenResponse, "access_token");
        if (StringUtils.isBlank(accessToken)) {
            throw new UnauthenticatedException("GitHub did not return an access token");
        }
        JsonObject profile = getJson(USER_URL, accessToken);
        long id = profile.get("id").getAsLong();
        String subject = Long.toString(id);
        String email = textOrNull(profile, "email");
        if (StringUtils.isBlank(email)) {
            email = primaryVerifiedEmail(accessToken);
        }
        if (StringUtils.isBlank(email)) {
            throw new UnauthenticatedException(
                    "GitHub did not share an email address. Allow email access for this OAuth app or make a primary email public.");
        }
        email = email.trim().toLowerCase(Locale.ROOT);
        String name = textOrNull(profile, "name");
        if (StringUtils.isBlank(name)) {
            name = textOrNull(profile, "login");
        }
        return new OidcAuthService.OidcUser(ISSUER, subject, email, StringUtils.defaultIfBlank(name, email));
    }

    private String primaryVerifiedEmail(String accessToken) {
        JsonArray emails = getJsonArray(EMAILS_URL, accessToken);
        if (emails == null) {
            return null;
        }
        String fallback = null;
        for (JsonElement element : emails) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            if (row.has("verified") && !row.get("verified").getAsBoolean()) {
                continue;
            }
            String address = textOrNull(row, "email");
            if (StringUtils.isBlank(address)) {
                continue;
            }
            if (row.has("primary") && row.get("primary").getAsBoolean()) {
                return address;
            }
            if (fallback == null) {
                fallback = address;
            }
        }
        return fallback;
    }

    private boolean needsRegistration(OidcAuthService.OidcUser user, String intent) {
        Optional<com.tx.api.test.generator.studio.tenant.Account> existing =
                tenantStore.findAccountByOidc(user.issuer(), user.subject());
        if (existing.isEmpty()) {
            existing = tenantStore.findAccountByEmail(user.email());
        }
        if (existing.isPresent()) {
            return false;
        }
        if (!properties.isBillingEnabled()) {
            return false;
        }
        if ("register".equals(intent)) {
            return true;
        }
        return properties.getAuth().getGithub().isAllowRegistration();
    }

    private JsonObject postForm(String url, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject parsed = JSON.fromJson(response.body(), JsonObject.class);
            if (response.statusCode() >= 400) {
                String message = parsed != null && parsed.has("error_description")
                        ? parsed.get("error_description").getAsString()
                        : parsed != null && parsed.has("error")
                        ? parsed.get("error").getAsString()
                        : "GitHub sign-in failed";
                throw new UnauthenticatedException(message);
            }
            return parsed == null ? new JsonObject() : parsed;
        } catch (UnauthenticatedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UnauthenticatedException("GitHub sign-in failed");
        }
    }

    private JsonObject getJson(String url, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new UnauthenticatedException("Unable to load GitHub profile");
            }
            JsonObject parsed = JSON.fromJson(response.body(), JsonObject.class);
            return parsed == null ? new JsonObject() : parsed;
        } catch (UnauthenticatedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UnauthenticatedException("Unable to load GitHub profile");
        }
    }

    private JsonArray getJsonArray(String url, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return null;
            }
            return JSON.fromJson(response.body(), JsonArray.class);
        } catch (Exception exception) {
            return null;
        }
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new ForbiddenException("GitHub sign-in is not enabled");
        }
    }

    private static String normalizeIntent(String intent) {
        if ("register".equalsIgnoreCase(StringUtils.trimToEmpty(intent))) {
            return "register";
        }
        return "login";
    }

    private String sanitizeReturnTo(String returnTo) {
        if (StringUtils.isBlank(returnTo)) {
            return null;
        }
        String uiBase = properties.getAuth().getGithub().resolvedUiBase();
        String trimmed = returnTo.trim();
        if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
            return trimmed;
        }
        if (trimmed.startsWith(uiBase)) {
            String suffix = trimmed.substring(uiBase.length());
            return suffix.isBlank() ? "/" : suffix.startsWith("/") ? suffix : "/" + suffix;
        }
        return null;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to build PKCE challenge", exception);
        }
    }

    private static String formBody(Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                builder.append('&');
            }
            first = false;
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            builder.append('=');
            builder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static String textOrNull(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        return object.get(name).getAsString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
    }
}
