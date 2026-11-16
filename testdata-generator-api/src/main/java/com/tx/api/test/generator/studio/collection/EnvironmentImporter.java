package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.service.InputRules;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;

public final class EnvironmentImporter {

    private EnvironmentImporter() {
    }

    public static StoredEnvironment importInto(StudioStorage storage, String specId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new CollectionFormatException("Environment JSON is required");
        }
        StudioEnvironment parsed = CollectionDocuments.parseEnvironment(new String(bytes, StandardCharsets.UTF_8));
        EnvironmentMapper.rejectRawSecrets(parsed);
        // The same rules as saving an environment in the editor, so a file can't bring in what the form refuses.
        try {
            InputRules.limitedText("Environment name", parsed.name().trim(), InputRules.MAX_NAME_LENGTH);
            String baseUrl = parsed.values() == null ? null : parsed.values().get("baseUrl");
            if (StringUtils.isNotBlank(baseUrl)) {
                InputRules.requireHttpBaseUrl("Base URL", baseUrl);
            }
        } catch (IllegalArgumentException invalid) {
            throw new CollectionFormatException(invalid.getMessage());
        }
        StudioEnvironment environment = new StudioEnvironment(parsed.format(), parsed.version(), parsed.name(),
                parsed.values(), parsed.auth(), parsed.cases(),
                StudioVariables.normalize(parsed.variables(), "Environment"),
                StudioEnvironmentNetwork.normalize(parsed.network()));
        if (StringUtils.isBlank(specId)) {
            throw new CollectionFormatException("specId is required to import an environment");
        }
        return EnvironmentExporter.upsert(storage, specId, environment);
    }
}
