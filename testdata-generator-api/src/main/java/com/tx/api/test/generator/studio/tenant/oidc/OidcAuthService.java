package com.tx.api.test.generator.studio.tenant.oidc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.tenant.ForbiddenException;
import com.tx.api.test.generator.studio.tenant.TenantStore;
import com.tx.api.test.generator.studio.tenant.UnauthenticatedException;
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
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OidcAuthService {

    private static final Gson JSON = new Gson();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration EXCHANGE_TTL = Duration.ofMinutes(10);

    private final StudioProperties properties;
    private final TenantStore tenantStore;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, OidcIssuerMetadata> issuerCache = new ConcurrentHashMap<>();

    public record OidcUser(String issuer, String subject, String email, String displayName) {
    }

    private record OidcIssuerMetadata(String authorizationEndpoint, String tokenEndpoint, String jwksUri) {
    }

    public OidcAuthService(StudioProperties properties, TenantStore tenantStore) {
        this.properties = properties;
        this.tenantStore = tenantStore;
    }

    public boolean enabled() {
        return properties.isOidcEnabled();
    }

    public String authorizationUrl(String intent, String returnTo) {
    requireEnabled();
    String normalizedIntent = normalizeIntent(intent);
    String safeReturn = sanitizeReturnTo(returnTo);
    StudioProperties.Oidc oidc = properties.getAuth().getOidc();
    OidcIssuerMetadata metadata = issuerMetadata(oidc.resolvedIssuer());
    String state = randomToken();
    String verifier = randomToken();
    String challenge = pkceChallenge(verifier);
    String nonce = randomToken();
    tenantStore.saveOidcState(new OidcPendingState(
        state, verifier, nonce, normalizedIntent, safeReturn, Instant.now().plus(STATE_TTL)));
    Map<String, String> params = new LinkedHashMap<>();
    params.put("client_id", oidc.getClientId().trim());
    params.put("response_type", "code");
    params.put("scope", "openid email profile");
    params.put("redirect_uri", oidc.resolvedRedirectUri());
    params.put("state", state);
    params.put("nonce", nonce);
    params.put("code_challenge", challenge);
    params.put("code_challenge_method", "S256");
    if ("register".equals(normalizedIntent)) {
      params.put("prompt", "select_account");
    }
    StringBuilder url = new StringBuilder(metadata.authorizationEndpoint()).append('?');
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
    StudioProperties.Oidc oidc = properties.getAuth().getOidc();
    String uiBase = oidc.resolvedUiBase();
    if (StringUtils.isNotBlank(error)) {
      String message = StringUtils.defaultIfBlank(errorDescription, error);
      return uiBase + "/login/oidc?error=" + encode(message);
    }
    if (StringUtils.isBlank(code) || StringUtils.isBlank(state)) {
      return uiBase + "/login/oidc?error=" + encode("Sign-in was cancelled or incomplete");
    }
    OidcPendingState pending = tenantStore.consumeOidcState(state.trim())
        .orElseThrow(() -> new UnauthenticatedException("Sign-in expired — try again"));
    OidcUser user = exchangeCode(code.trim(), pending.codeVerifier(), pending.nonce());
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

    public Optional<OidcExchangeRecord> peekExchange(String exchangeId) {
        if (StringUtils.isBlank(exchangeId)) {
            return Optional.empty();
        }
        return tenantStore.findOidcExchange(exchangeId.trim());
    }

    public Optional<OidcExchangeRecord> consumeExchange(String exchangeId) {
        Optional<OidcExchangeRecord> row = peekExchange(exchangeId);
        if (row.isPresent()) {
            tenantStore.deleteOidcExchange(exchangeId.trim());
        }
        return row;
    }

  private OidcUser exchangeCode(String code, String verifier, String nonce) {
    StudioProperties.Oidc oidc = properties.getAuth().getOidc();
    OidcIssuerMetadata metadata = issuerMetadata(oidc.resolvedIssuer());
    String body = formBody(Map.of(
        "grant_type", "authorization_code",
        "code", code,
        "redirect_uri", oidc.resolvedRedirectUri(),
        "client_id", oidc.getClientId().trim(),
        "client_secret", oidc.getClientSecret().trim(),
        "code_verifier", verifier));
    JsonObject tokenResponse = postForm(metadata.tokenEndpoint(), body);
    String idToken = tokenResponse.has("id_token") ? tokenResponse.get("id_token").getAsString() : null;
    if (StringUtils.isBlank(idToken)) {
      throw new UnauthenticatedException("Provider did not return an id token");
    }
    JWTClaimsSet claims = verifyIdToken(idToken, metadata.jwksUri(), oidc.getClientId().trim(), nonce);
    String issuer = claims.getIssuer();
    String subject = claims.getSubject();
    String email = stringClaim(claims, "email");
    if (StringUtils.isBlank(email)) {
      throw new UnauthenticatedException("Provider did not share an email address");
    }
  email = email.trim().toLowerCase(Locale.ROOT);
    String name = stringClaim(claims, "name");
    if (StringUtils.isBlank(name)) {
      name = stringClaim(claims, "given_name");
    }
    return new OidcUser(issuer, subject, email, StringUtils.defaultIfBlank(name, email));
  }

  private boolean needsRegistration(OidcUser user, String intent) {
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
    return properties.getAuth().getOidc().isAllowRegistration();
  }

  private JWTClaimsSet verifyIdToken(String idToken, String jwksUri, String clientId, String nonce) {
    try {
      URI jwks = URI.create(jwksUri);
      ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
      JWKSource<SecurityContext> jwkSource = JWKSourceBuilder.create(jwks.toURL())
          .build();
      processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
      JWTClaimsSet claims = processor.process(idToken, null);
            if (!properties.getAuth().getOidc().resolvedIssuer().equals(claims.getIssuer())) {
                throw new UnauthenticatedException("Unexpected token issuer");
            }
      if (!claims.getAudience().contains(clientId)) {
        throw new UnauthenticatedException("Unexpected token audience");
      }
      if (!nonce.equals(claims.getStringClaim("nonce"))) {
        throw new UnauthenticatedException("Sign-in nonce mismatch");
      }
      Instant now = Instant.now();
      if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().isBefore(now)) {
        throw new UnauthenticatedException("Sign-in token expired");
      }
      return claims;
    } catch (UnauthenticatedException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new UnauthenticatedException("Unable to validate sign-in token");
    }
  }

  private OidcIssuerMetadata issuerMetadata(String issuer) {
    return issuerCache.computeIfAbsent(issuer, this::loadIssuerMetadata);
  }

  private OidcIssuerMetadata loadIssuerMetadata(String issuer) {
    String discovery = issuer.endsWith("/")
        ? issuer + ".well-known/openid-configuration"
        : issuer + "/.well-known/openid-configuration";
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(discovery))
          .timeout(HTTP_TIMEOUT)
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("OpenID discovery failed (" + response.statusCode() + ")");
      }
      JsonObject doc = JSON.fromJson(response.body(), JsonObject.class);
      String authorize = text(doc, "authorization_endpoint");
      String token = text(doc, "token_endpoint");
      String jwks = text(doc, "jwks_uri");
      return new OidcIssuerMetadata(authorize, token, jwks);
    } catch (IllegalStateException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to load OpenID configuration for " + issuer, exception);
    }
  }

  private JsonObject postForm(String url, String body) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .timeout(HTTP_TIMEOUT)
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      JsonObject parsed = JSON.fromJson(response.body(), JsonObject.class);
      if (response.statusCode() >= 400) {
        String message = parsed != null && parsed.has("error_description")
            ? parsed.get("error_description").getAsString()
            : "Provider sign-in failed";
        throw new UnauthenticatedException(message);
      }
      return parsed == null ? new JsonObject() : parsed;
    } catch (UnauthenticatedException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new UnauthenticatedException("Provider sign-in failed");
    }
  }

  private void requireEnabled() {
    if (!enabled()) {
      throw new ForbiddenException("Google sign-in is not enabled");
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
    String uiBase = properties.getAuth().getOidc().resolvedUiBase();
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

  private static String text(JsonObject object, String name) {
    if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
      throw new IllegalStateException("OpenID discovery missing " + name);
    }
    return object.get(name).getAsString();
  }

  private static String stringClaim(JWTClaimsSet claims, String name) {
    try {
      return claims.getStringClaim(name);
    } catch (Exception exception) {
      return null;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
  }
}
