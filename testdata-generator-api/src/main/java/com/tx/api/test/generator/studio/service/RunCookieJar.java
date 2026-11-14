package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang3.StringUtils;

import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cookies a run keeps between its requests, as a browser or Postman's cookie jar does: a response's Set-Cookie is
 * sent on later requests to the same site (domain, path, Secure and expiry rules), for the rest of that run. The jar
 * lives in the run's variable map under a key no {@code {{name}}} can reach, so it follows the run through Run plan,
 * prerequisites and data rows without new plumbing, and ends with the run. A case's own cookie of the same name, or
 * its own Cookie header, wins.
 */
final class RunCookieJar {

    /** The NUL keeps this key apart from any {@code {{name}}}, like {@link CaseVariables#JSON_TYPE_PREFIX}. */
    static final String KEY = "\u0000cookie-jar";
    private static final int MAX_COOKIES = 200;
    private static final Gson JSON = new Gson();

    record Stored(String name, String value, String domain, boolean hostOnly, String path, Long expiresAt,
                  boolean secure) {
    }

    private RunCookieJar() {
    }

    /** Cookies for a request to {@code url}: name → value, longest path first, skipping expired ones. */
    static Map<String, String> cookiesFor(Map<String, String> variables, String url, long now) {
        Map<String, String> sent = new LinkedHashMap<>();
        URI uri = uri(url);
        if (variables == null || uri == null || uri.getHost() == null) {
            return sent;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = StringUtils.defaultIfBlank(uri.getRawPath(), "/");
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        read(variables).stream()
                .filter(cookie -> cookie.expiresAt() == null || cookie.expiresAt() > now)
                .filter(cookie -> !cookie.secure() || https)
                .filter(cookie -> cookie.hostOnly() ? host.equals(cookie.domain()) : domainMatches(host, cookie.domain()))
                .filter(cookie -> pathMatches(path, cookie.path()))
                .sorted((left, right) -> Integer.compare(right.path().length(), left.path().length()))
                .forEach(cookie -> sent.putIfAbsent(cookie.name(), cookie.value()));
        return sent;
    }

    /** Remembers the Set-Cookie headers of a response from {@code url}; Max-Age=0 or a past Expires removes one. */
    static void remember(Map<String, String> variables, String url, List<String> setCookieHeaders, long now) {
        URI uri = uri(url);
        if (variables == null || uri == null || uri.getHost() == null || setCookieHeaders == null
                || setCookieHeaders.isEmpty()) {
            return;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        List<Stored> jar = new ArrayList<>(read(variables));
        for (String header : setCookieHeaders) {
            List<HttpCookie> parsed;
            try {
                parsed = HttpCookie.parse(header.regionMatches(true, 0, "set-cookie", 0, 10) ? header : "Set-Cookie: " + header);
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            for (HttpCookie cookie : parsed) {
                String domain = StringUtils.removeStart(StringUtils.trimToEmpty(cookie.getDomain()), ".")
                        .toLowerCase(Locale.ROOT);
                boolean hostOnly = domain.isEmpty();
                if (hostOnly) {
                    domain = host;
                } else if (!domainMatches(host, domain)) {
                    continue; // a site may only set cookies for itself or a parent domain
                }
                String path = StringUtils.isBlank(cookie.getPath()) ? defaultPath(uri.getRawPath()) : cookie.getPath();
                String key = cookie.getName() + "\n" + domain + "\n" + path;
                jar.removeIf(stored -> key.equals(stored.name() + "\n" + stored.domain() + "\n" + stored.path()));
                long maxAge = cookie.getMaxAge();
                if (maxAge == 0) {
                    continue; // removal
                }
                Long expiresAt = maxAge < 0 ? null : now + maxAge * 1000L;
                jar.add(new Stored(cookie.getName(), cookie.getValue(), domain, hostOnly, path, expiresAt,
                        cookie.getSecure()));
            }
        }
        while (jar.size() > MAX_COOKIES) {
            jar.remove(0);
        }
        variables.put(KEY, JSON.toJson(jar));
    }

    /**
     * The Cookie header to send: the case's own ({@code a=1; b=2}, or null) followed by jar cookies whose names it
     * doesn't already send.
     */
    static String merge(String ownHeader, Map<String, String> jar) {
        List<String> parts = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        if (StringUtils.isNotBlank(ownHeader)) {
            for (String pair : ownHeader.split(";")) {
                String trimmed = pair.trim();
                if (!trimmed.isEmpty()) {
                    parts.add(trimmed);
                    int equals = trimmed.indexOf('=');
                    names.add((equals < 0 ? trimmed : trimmed.substring(0, equals)).trim());
                }
            }
        }
        if (jar != null) {
            jar.forEach((name, value) -> {
                if (!names.contains(name)) {
                    parts.add(name + "=" + StringUtils.defaultString(value));
                }
            });
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private static List<Stored> read(Map<String, String> variables) {
        String stored = variables.get(KEY);
        if (StringUtils.isBlank(stored)) {
            return List.of();
        }
        try {
            List<Stored> jar = JSON.fromJson(stored, new TypeToken<List<Stored>>() { }.getType());
            return jar == null ? List.of() : jar;
        } catch (RuntimeException corrupt) {
            return List.of();
        }
    }

    private static URI uri(String url) {
        try {
            return StringUtils.isBlank(url) ? null : URI.create(url);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static boolean domainMatches(String host, String domain) {
        return host.equals(domain) || (host.endsWith("." + domain) && !host.matches("[0-9.]+"));
    }

    static boolean pathMatches(String requestPath, String cookiePath) {
        if (requestPath.equals(cookiePath)) {
            return true;
        }
        return requestPath.startsWith(cookiePath)
                && (cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/');
    }

    /** RFC 6265: the request path up to, not including, its last slash; "/" when there's nothing before it. */
    static String defaultPath(String requestPath) {
        if (StringUtils.isBlank(requestPath) || !requestPath.startsWith("/")) {
            return "/";
        }
        int last = requestPath.lastIndexOf('/');
        return last <= 0 ? "/" : requestPath.substring(0, last);
    }
}
