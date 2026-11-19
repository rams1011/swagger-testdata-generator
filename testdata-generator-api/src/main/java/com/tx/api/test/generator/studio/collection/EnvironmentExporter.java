package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.dto.EnvironmentExport;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.service.SecretNameRules;
import com.tx.api.test.generator.studio.storage.StudioStorage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class EnvironmentExporter {

    private EnvironmentExporter() {
    }

    public static EnvironmentExport export(StoredSpec spec, StoredEnvironment stored) {
        return export(spec, stored, List.of());
    }

    /**
     * Writes the environment file for CI. Workspace {@code globals} go in an optional top-level array (secrets as
     * {@code ${STUDIO_VAR_*}}) so the runner can resolve {@code {{name}}} and {@code {{global:name}}}; Studio import
     * ignores that array and never overwrites workspace globals.
     */
    public static EnvironmentExport export(StoredSpec spec, StoredEnvironment stored, List<StudioVariable> globals) {
        return export(spec, stored, globals, null);
    }

    /**
     * As above, plus the optional top-level {@code safety} block (production safety for api-automation, which makes
     * no call to SpecPact); null leaves it out. Import ignores it.
     */
    public static EnvironmentExport export(StoredSpec spec, StoredEnvironment stored, List<StudioVariable> globals,
                                           StudioEnvironmentSafety safety) {
        if (spec == null) {
            throw new CollectionFormatException("spec is required");
        }
        if (stored == null) {
            throw new CollectionFormatException("environment is required");
        }
        StudioEnvironment bare = EnvironmentMapper.withoutOverlays(
                CollectionDocuments.parseEnvironment(stored.getDocumentJson()));
        StudioEnvironment environment = new StudioEnvironment(bare.format(), bare.version(), bare.name(),
                bare.values(), bare.auth(), bare.cases(), StudioVariables.forExport(bare.variableList()),
                bare.network());
        String document = CollectionDocuments.toJson(environment);
        List<StudioVariable> exportedGlobals = StudioVariables.forExport(globals);
        if (!exportedGlobals.isEmpty() || safety != null) {
            com.google.gson.JsonObject tree = com.google.gson.JsonParser.parseString(document).getAsJsonObject();
            if (!exportedGlobals.isEmpty()) {
                tree.add("globals", com.google.gson.JsonParser.parseString(CollectionDocuments.toJson(exportedGlobals)));
            }
            if (safety != null) {
                tree.add("safety", com.google.gson.JsonParser.parseString(CollectionDocuments.toJson(safety)));
            }
            document = CollectionDocuments.toJson(tree);
        }
        byte[] json = document.getBytes(StandardCharsets.UTF_8);
        return new EnvironmentExport(
                CollectionFormat.environmentFileName(spec.getName(), environment.name()),
                json,
                CollectionFormat.JSON_MEDIA_TYPE,
                stored.getId(),
                environment.name()
        );
    }

    public static StoredEnvironment upsert(StudioStorage storage, String specId, StudioEnvironment environment) {
        java.util.Optional<StoredEnvironment> existing = storage.findEnvironmentByName(specId, environment.name());
        List<StudioVariable> previous = existing
                .map(found -> CollectionDocuments.parseEnvironment(found.getDocumentJson()))
                .map(StudioEnvironment::variableList)
                .orElse(List.of());
        SecretNameRules.requireForEnvironment(storage, specId, previous, environment.variableList());
        StoredEnvironment stored = existing.orElseGet(() -> {
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
