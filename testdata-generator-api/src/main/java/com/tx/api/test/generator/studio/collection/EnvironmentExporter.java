package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.dto.EnvironmentExport;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class EnvironmentExporter {

    private EnvironmentExporter() {
    }

    public static EnvironmentExport export(StudioStorage storage, StoredSpec spec, String name, String authType) {
        StudioEnvironment environment = EnvironmentMapper.fromSpec(spec, name, authType);
        StoredEnvironment stored = upsert(storage, spec.getSpecId(), environment);
        byte[] json = CollectionDocuments.toJson(environment).getBytes(StandardCharsets.UTF_8);
        return new EnvironmentExport(
                CollectionFormat.environmentFileName(spec.getName(), environment.name()),
                json,
                CollectionFormat.JSON_MEDIA_TYPE,
                stored.getId(),
                environment.name()
        );
    }

    static StoredEnvironment upsert(StudioStorage storage, String specId, StudioEnvironment environment) {
        StoredEnvironment stored = storage.findEnvironmentByName(specId, environment.name()).orElseGet(() -> {
            StoredEnvironment created = new StoredEnvironment();
            created.setId(UUID.randomUUID().toString());
            created.setSpecId(specId);
            return created;
        });
        stored.setName(environment.name());
        stored.setDocumentJson(CollectionDocuments.toJson(environment));
        storage.saveEnvironment(stored);
        return stored;
    }
}
