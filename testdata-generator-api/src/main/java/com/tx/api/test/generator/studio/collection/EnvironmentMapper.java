package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.dto.EnvironmentSummary;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class EnvironmentMapper {

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[A-Za-z_][A-Za-z0-9_]*}$");

    private EnvironmentMapper() {
    }

    public static StudioEnvironment fromSpec(StoredSpec spec, String name, String authType) {
        if (spec == null) {
            throw new CollectionFormatException("spec is required");
        }
        return fromInputs(name, spec.getBaseUrl(), authType);
    }

    public static StudioEnvironment fromInputs(String name, String baseUrl, String authType) {
        String environmentName = StringUtils.defaultIfBlank(name, "local").trim();
        Map<String, String> values = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(baseUrl)) {
            values.put("baseUrl", baseUrl.trim());
        }
        StudioEnvironment environment = new StudioEnvironment(
                CollectionFormat.ENVIRONMENT_FORMAT,
                CollectionFormat.VERSION,
                environmentName,
                values,
                placeholderAuth(authType),
                new LinkedHashMap<>()
        );
        CollectionMapper.validate(environment);
        rejectRawSecrets(environment);
        return environment;
    }

    public static StudioEnvironment merge(StudioEnvironment existing, String name, String baseUrl, String authType) {
        if (existing == null) {
            return fromInputs(name, baseUrl, authType);
        }
        String environmentName = StringUtils.defaultIfBlank(name, existing.name()).trim();
        Map<String, String> values = existing.values() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(existing.values());
        if (baseUrl == null) {
            // keep existing baseUrl
        } else if (StringUtils.isBlank(baseUrl)) {
            values.remove("baseUrl");
        } else {
            values.put("baseUrl", baseUrl.trim());
        }
        String nextType = StringUtils.defaultIfBlank(authType, authType(existing));
        StudioEnvironmentAuth auth = authType(existing).equalsIgnoreCase(nextType.trim())
                ? existing.auth()
                : placeholderAuth(nextType);
        StudioEnvironment environment = new StudioEnvironment(
                CollectionFormat.ENVIRONMENT_FORMAT,
                CollectionFormat.VERSION,
                environmentName,
                values,
                auth,
                existing.caseOverlays().isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(existing.caseOverlays())
        );
        CollectionMapper.validate(environment);
        rejectRawSecrets(environment);
        return environment;
    }

    public static String authType(StudioEnvironment environment) {
        if (environment == null || environment.auth() == null || StringUtils.isBlank(environment.auth().type())) {
            return "NONE";
        }
        return environment.auth().type().trim().toUpperCase(Locale.ROOT);
    }

    public static String copyName(String source, java.util.function.Predicate<String> taken) {
        String base = StringUtils.defaultIfBlank(source, "local").trim() + "-copy";
        if (taken == null || !taken.test(base)) {
            return base;
        }
        for (int index = 2; index < 100; index++) {
            String candidate = base + "-" + index;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        return base + "-" + System.currentTimeMillis();
    }

    public static EnvironmentSummary toSummary(StoredEnvironment stored) {
        StudioEnvironment environment = CollectionDocuments.parseEnvironment(stored.getDocumentJson());
        String baseUrl = environment.values() == null ? null : environment.values().get("baseUrl");
        return new EnvironmentSummary(stored.getId(), stored.getSpecId(), stored.getName(), baseUrl,
                authType(environment));
    }

    public static String baseUrl(StudioEnvironment environment) {
        if (environment == null || environment.values() == null) {
            return null;
        }
        String baseUrl = environment.values().get("baseUrl");
        return StringUtils.isBlank(baseUrl) ? null : baseUrl.trim();
    }

    public static void rejectRawSecrets(StudioEnvironment environment) {
        if (environment == null || environment.auth() == null) {
            return;
        }
        StudioEnvironmentAuth auth = environment.auth();
        requirePlaceholder("auth.token", auth.token());
        requirePlaceholder("auth.password", auth.password());
        requirePlaceholder("auth.apiKey", auth.apiKey());
        requirePlaceholder("auth.clientSecret", auth.clientSecret());
    }

    public static StudioEnvironmentAuth placeholderAuth(String authType) {
        String type = StringUtils.defaultIfBlank(authType, "NONE").trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "NONE", "" -> new StudioEnvironmentAuth("NONE", null, null, null, null, null, null, null, null, null, null);
            case "BEARER", "TOKEN" -> new StudioEnvironmentAuth(
                    "BEARER", "${AUTOMATION_BEARER_TOKEN}", null, null, null, null, null, null, null, null, null);
            case "BASIC", "CREDENTIALS" -> new StudioEnvironmentAuth(
                    "BASIC", null, "${AUTOMATION_USERNAME}", "${AUTOMATION_PASSWORD}",
                    null, null, null, null, null, null, null);
            case "API_KEY", "APIKEY", "API-KEY" -> new StudioEnvironmentAuth(
                    "API_KEY", null, null, null, "${AUTOMATION_API_KEY}", "X-API-Key", "header",
                    null, null, null, null);
            case "OAUTH2", "OAUTH" -> new StudioEnvironmentAuth(
                    "OAUTH2", null, null, null, null, null, null,
                    "${AUTOMATION_PING_IDENTITY_CLIENT_ID}",
                    "${AUTOMATION_PING_IDENTITY_CLIENT_SECRET}",
                    "${AUTOMATION_PING_IDENTITY_TOKEN_URL}",
                    "client_credentials");
            default -> throw new CollectionFormatException("Unsupported environment auth type: " + authType);
        };
    }

    private static void requirePlaceholder(String field, String value) {
        if (StringUtils.isBlank(value) || PLACEHOLDER.matcher(value.trim()).matches()) {
            return;
        }
        throw new CollectionFormatException(field + " must be a ${ENV_VAR} placeholder, not a secret");
    }
}
