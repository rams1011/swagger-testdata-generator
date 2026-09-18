package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.model.StoredEnvironment;
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
        StudioEnvironment environment = CollectionDocuments.parseEnvironment(new String(bytes, StandardCharsets.UTF_8));
        EnvironmentMapper.rejectRawSecrets(environment);
        if (StringUtils.isBlank(specId)) {
            throw new CollectionFormatException("specId is required to import an environment");
        }
        return EnvironmentExporter.upsert(storage, specId, environment);
    }
}
