package com.tx.api.test.generator.studio.safety;

import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whether a request reads or changes data, worked out from the request as it will be sent. GET, HEAD and OPTIONS
 * read; every other method (TRACE and custom methods included) writes. A method override wins when it asks for a
 * write: the {@code X-HTTP-Method-Override}, {@code X-HTTP-Method} and {@code X-Method-Override} headers, a
 * {@code _method} query parameter or form field, and a {@code _method} part in a multipart body. GraphQL and gRPC
 * are classified as REST for now, so a GraphQL query sent by POST counts as a write until their own rules arrive.
 */
public final class RequestClassifier {

    static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    static final List<String> OVERRIDE_HEADERS = List.of("X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override");
    private static final String METHOD_FIELD = "_method";

    private RequestClassifier() {
    }

    /** {@code effectiveMethod} is the method the server will act on: the override when there is one. */
    public record Classification(boolean write, String method, String effectiveMethod, String reason) {
    }

    public static Classification classify(String method, String url, Map<String, String> headers, String body,
                                          String contentType) {
        String sent = StringUtils.defaultIfBlank(method, "GET").trim().toUpperCase(Locale.ROOT);
        String override = null;
        String source = null;
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                for (String name : OVERRIDE_HEADERS) {
                    if (name.equalsIgnoreCase(header.getKey()) && StringUtils.isNotBlank(header.getValue())) {
                        override = header.getValue().trim().toUpperCase(Locale.ROOT);
                        source = "the " + name + " header";
                    }
                }
            }
        }
        String fromQuery = field(query(url));
        if (fromQuery != null) {
            override = fromQuery;
            source = "the " + METHOD_FIELD + " query parameter";
        }
        String type = StringUtils.defaultString(contentType).toLowerCase(Locale.ROOT);
        if (body != null && type.contains("application/x-www-form-urlencoded")) {
            String fromForm = field(body);
            if (fromForm != null) {
                override = fromForm;
                source = "the " + METHOD_FIELD + " form field";
            }
        } else if (body != null && (body.contains("name=\"" + METHOD_FIELD + "\"")
                || body.contains("\"" + METHOD_FIELD + "\""))) {
            // A multipart part or JSON key named _method (frameworks read both): assume it asks for a write.
            override = "UNKNOWN";
            source = "a " + METHOD_FIELD + " field in the body";
        }
        boolean sentWrites = !READ_METHODS.contains(sent);
        boolean overrideWrites = override != null && !READ_METHODS.contains(override);
        String effective = override != null ? override : sent;
        if (sentWrites) {
            return new Classification(true, sent, effective, sent + " changes data");
        }
        if (overrideWrites) {
            return new Classification(true, sent, effective, sent + " with " + source + " asking for " + override);
        }
        return new Classification(false, sent, effective, sent + " only reads");
    }

    private static String query(String url) {
        if (url == null) {
            return null;
        }
        try {
            return URI.create(url).getRawQuery();
        } catch (IllegalArgumentException invalid) {
            int mark = url.indexOf('?');
            return mark < 0 ? null : url.substring(mark + 1);
        }
    }

    /** The value of {@code _method} in {@code a=1&_method=DELETE}, upper case, or null. */
    private static String field(String encoded) {
        if (encoded == null || !encoded.contains(METHOD_FIELD)) {
            return null;
        }
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            if (METHOD_FIELD.equals(name.trim()) && equals >= 0) {
                String value = decode(pair.substring(equals + 1)).trim();
                if (!value.isEmpty()) {
                    return value.toUpperCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            return text;
        }
    }
}
