package com.tx.api.test.generator.openapi;

import com.tx.api.test.generator.catalog.SpecServer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenApiServerInspector {

    private OpenApiServerInspector() {
    }

    static List<SpecServer> inspect(OpenAPI openApi) {
        List<SpecServer> servers = new ArrayList<>();
        if (openApi == null || openApi.getServers() == null) {
            return servers;
        }
        for (Server server : openApi.getServers()) {
            if (server == null || StringUtils.isBlank(server.getUrl())) {
                continue;
            }
            Map<String, String> variables = defaults(server);
            String resolved = resolve(server.getUrl().trim(), variables);
            servers.add(new SpecServer(
                    server.getUrl().trim(),
                    StringUtils.defaultString(server.getDescription()),
                    resolved.replaceAll("/+$", ""),
                    variables
            ));
        }
        return servers;
    }

    static String resolve(String url, Map<String, String> variables) {
        String resolved = url;
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                resolved = resolved.replace("{" + entry.getKey() + "}", StringUtils.defaultString(entry.getValue()));
            }
        }
        return resolved;
    }

    private static Map<String, String> defaults(Server server) {
        Map<String, String> values = new LinkedHashMap<>();
        if (server.getVariables() == null) {
            return values;
        }
        for (Map.Entry<String, ServerVariable> entry : server.getVariables().entrySet()) {
            ServerVariable variable = entry.getValue();
            String value = variable == null ? "" : StringUtils.defaultIfBlank(variable.getDefault(),
                    variable.getEnum() == null || variable.getEnum().isEmpty() ? "" : variable.getEnum().get(0));
            values.put(entry.getKey(), value);
        }
        return values;
    }
}
