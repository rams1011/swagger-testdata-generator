package com.tx.api.test.generator.openapi;

import com.tx.api.test.generator.catalog.OperationAuth;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;

final class OpenApiSecurityInspector {

    private OpenApiSecurityInspector() {
    }

    static OperationAuth inspect(OpenAPI openApi, Operation operation) {
        if (openApi == null) {
            return OperationAuth.none();
        }
        String schemeName = firstSchemeName(operation == null ? null : operation.getSecurity(), openApi.getSecurity());
        if (StringUtils.isBlank(schemeName) || openApi.getComponents() == null
                || openApi.getComponents().getSecuritySchemes() == null) {
            return OperationAuth.none();
        }
        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get(schemeName);
        if (scheme == null) {
            return OperationAuth.none();
        }
        return toAuth(schemeName, scheme);
    }

    private static String firstSchemeName(List<SecurityRequirement> operation, List<SecurityRequirement> global) {
        String fromOperation = firstName(operation);
        if (fromOperation != null) {
            return fromOperation;
        }
        return firstName(global);
    }

    private static String firstName(List<SecurityRequirement> requirements) {
        if (requirements == null) {
            return null;
        }
        for (SecurityRequirement requirement : requirements) {
            if (requirement == null || requirement.isEmpty()) {
                continue;
            }
            for (String name : requirement.keySet()) {
                if (StringUtils.isNotBlank(name)) {
                    return name;
                }
            }
        }
        return null;
    }

    private static OperationAuth toAuth(String schemeName, SecurityScheme scheme) {
        SecurityScheme.Type type = scheme.getType();
        if (type == SecurityScheme.Type.HTTP) {
            String http = StringUtils.defaultString(scheme.getScheme()).toLowerCase(Locale.ROOT);
            if (http.contains("basic")) {
                return new OperationAuth(OperationAuth.BASIC, schemeName, null, null, null, null, scheme.getDescription());
            }
            return new OperationAuth(OperationAuth.BEARER, schemeName, null, null, null, null, scheme.getDescription());
        }
        if (type == SecurityScheme.Type.APIKEY) {
            String in = scheme.getIn() == null ? "header" : scheme.getIn().toString().toLowerCase(Locale.ROOT);
            String name = StringUtils.defaultIfBlank(scheme.getName(), "X-API-Key");
            return new OperationAuth(OperationAuth.API_KEY, schemeName, name, in, null, null, scheme.getDescription());
        }
        if (type == SecurityScheme.Type.OAUTH2 || type == SecurityScheme.Type.OPENIDCONNECT) {
            OAuthFlow flow = pickFlow(scheme.getFlows());
            String tokenUrl = flow == null ? null : flow.getTokenUrl();
            if (StringUtils.isBlank(tokenUrl) && type == SecurityScheme.Type.OPENIDCONNECT) {
                tokenUrl = scheme.getOpenIdConnectUrl();
            }
            String grant = grantType(scheme.getFlows(), flow);
            return new OperationAuth(OperationAuth.OAUTH2, schemeName, null, null, tokenUrl, grant, scheme.getDescription());
        }
        return OperationAuth.none();
    }

    private static OAuthFlow pickFlow(OAuthFlows flows) {
        if (flows == null) {
            return null;
        }
        if (flows.getClientCredentials() != null) {
            return flows.getClientCredentials();
        }
        if (flows.getPassword() != null) {
            return flows.getPassword();
        }
        if (flows.getAuthorizationCode() != null) {
            return flows.getAuthorizationCode();
        }
        return flows.getImplicit();
    }

    private static String grantType(OAuthFlows flows, OAuthFlow flow) {
        if (flows == null || flow == null) {
            return "client_credentials";
        }
        if (flow == flows.getPassword()) {
            return "password";
        }
        if (flow == flows.getAuthorizationCode()) {
            return "authorization_code";
        }
        return "client_credentials";
    }
}
