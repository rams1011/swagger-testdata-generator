package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.tx.api.test.generator.studio.dto.RunAuth;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A case's own authentication, used instead of the run's (environment) auth: for example an admin and a user role in
 * one plan, or a public endpoint called with no auth. {@code null} means "use the run's auth"; type {@code NONE}
 * sends no auth.
 * <p>
 * The case is saved and exported, so secret fields (password, token, API key, client secret) must be a single
 * {@code {{variable}}}, normally a secret variable entered in the browser, never the value itself.
 */
public final class CaseAuth {

    public static final String INHERIT = "INHERIT";
    private static final Gson JSON = new Gson();
    private static final Pattern SINGLE_PLACEHOLDER = Pattern.compile("^\\{\\{[^{}]+}}$");

    private CaseAuth() {
    }

    /** Validated and trimmed copy, or {@code null} for inherit / blank. */
    public static RunAuth normalize(RunAuth auth) {
        if (auth == null || StringUtils.isBlank(auth.type()) || INHERIT.equalsIgnoreCase(auth.type().trim())) {
            return null;
        }
        String type = auth.normalizedType();
        if (RunAuth.NONE.equals(type)) {
            return new RunAuth(RunAuth.NONE, null, null, null, null, null, null, null, null, null, null, null);
        }
        requireVariable("password", auth.password());
        requireVariable("token", auth.token());
        requireVariable("API key", auth.apiKey());
        requireVariable("client secret", auth.clientSecret());
        String apiKeyIn = StringUtils.trimToNull(auth.apiKeyIn());
        if (apiKeyIn != null) {
            apiKeyIn = apiKeyIn.toLowerCase(Locale.ROOT);
            if (!apiKeyIn.equals("header") && !apiKeyIn.equals("query") && !apiKeyIn.equals("cookie")) {
                throw new IllegalArgumentException("Case auth API key location must be header, query or cookie");
            }
        }
        return new RunAuth(type,
                StringUtils.trimToNull(auth.username()),
                StringUtils.trimToNull(auth.password()),
                StringUtils.trimToNull(auth.token()),
                StringUtils.trimToNull(auth.apiKey()),
                StringUtils.trimToNull(auth.apiKeyName()),
                apiKeyIn,
                StringUtils.trimToNull(auth.clientId()),
                StringUtils.trimToNull(auth.clientSecret()),
                StringUtils.trimToNull(auth.tokenUrl()),
                StringUtils.trimToNull(auth.grantType()),
                StringUtils.trimToNull(auth.scope()));
    }

    private static void requireVariable(String field, String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        if (!SINGLE_PLACEHOLDER.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("Case auth " + field + " must be a variable such as {{"
                    + field.replace(" ", "") + "}} (a secret variable), not the value itself, so it is never saved"
                    + " or exported");
        }
    }

    public static String toJson(RunAuth auth) {
        return auth == null ? null : JSON.toJson(auth);
    }

    public static RunAuth fromJson(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return JSON.fromJson(json, RunAuth.class);
        } catch (JsonSyntaxException exception) {
            return null;
        }
    }

    /** Short label for logs and views, such as {@code BEARER} or {@code NONE}; null when inherited. */
    public static String label(RunAuth auth) {
        return auth == null ? null : auth.normalizedType();
    }
}
