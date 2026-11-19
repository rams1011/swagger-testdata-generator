package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.dto.CollectionImportResult;
import com.tx.api.test.generator.studio.model.CaseLifecycle;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import com.tx.api.test.generator.studio.service.InputRules;
import com.tx.api.test.generator.studio.service.SecretNameRules;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

public final class CollectionImporter {

    private CollectionImporter() {
    }

    public static CollectionImportResult importInto(StudioStorage storage, byte[] bytes, String fileName,
                                                    String targetSpecId) {
        return importInto(storage, bytes, fileName, targetSpecId, null);
    }

    public static CollectionImportResult importInto(StudioStorage storage, byte[] bytes, String fileName,
                                                    String targetSpecId, String environmentName) {
        return importInto(storage, bytes, fileName, targetSpecId, environmentName, true);
    }

    public static CollectionImportResult importInto(StudioStorage storage, byte[] bytes, String fileName,
                                                    String targetSpecId, String environmentName,
                                                    boolean publishReady) {
        CollectionArchive archive = CollectionPackage.parse(bytes, fileName);
        return importInto(storage, archive.collection(), archive.files(), targetSpecId, environmentName, publishReady);
    }

    public static CollectionImportResult importInto(StudioStorage storage, StudioCollection collection,
                                                    Map<String, byte[]> files, String targetSpecId) {
        return importInto(storage, collection, files, targetSpecId, null);
    }

    public static CollectionImportResult importInto(StudioStorage storage, StudioCollection collection,
                                                    Map<String, byte[]> files, String targetSpecId,
                                                    String environmentName) {
        return importInto(storage, collection, files, targetSpecId, environmentName, true);
    }

    public static CollectionImportResult importInto(StudioStorage storage, StudioCollection collection,
                                                    Map<String, byte[]> files, String targetSpecId,
                                                    String environmentName, boolean publishReady) {
        CollectionMapper.validate(collection);
        List<StudioVariable> variables = StudioVariables.normalize(collection.variables(), "Collection");
        SharedSettings settings = collection.settings() == null ? null : SharedSettings.normalize(collection.settings());
        CollectionSnapshot snapshot = CollectionMapper.toSnapshot(collection);
        // Check every case before writing anything, with the rules a save applies, so one bad case leaves the
        // collection as it was.
        try {
            InputRules.limitedText("Collection name", collection.spec().name().trim(), InputRules.MAX_NAME_LENGTH);
            InputRules.limitedText("Plan name", collection.plan().name().trim(), InputRules.MAX_NAME_LENGTH);
            for (StudioTestCase item : snapshot.cases()) {
                InputRules.requireStorableCase(item, "Case \"" + StringUtils.abbreviate(item.getTestName(), 60) + "\"");
            }
        } catch (IllegalArgumentException invalid) {
            throw new CollectionFormatException(invalid.getMessage());
        }
        StoredSpec spec = resolveSpec(storage, collection, targetSpecId);
        if (!variables.isEmpty()) {
            List<StudioVariable> current = storage.findCollectionVariables(spec.getSpecId())
                    .map(StudioVariables::fromDocument)
                    .orElse(List.of());
            try {
                SecretNameRules.requireForCollection(storage, spec.getSpecId(), current,
                        StudioVariables.merge(current, variables));
            } catch (IllegalArgumentException conflict) {
                throw new CollectionFormatException(conflict.getMessage());
            }
        }
        PlanResolution plan = resolvePlan(storage, spec, collection.plan());
        int created = 0;
        int updated = 0;
        int importedFiles = 0;
        int skippedFiles = 0;
        Map<String, byte[]> binaries = files == null ? Map.of() : files;
        for (int index = 0; index < snapshot.cases().size(); index++) {
            StudioTestCase testCase = snapshot.cases().get(index);
            StudioCollectionCase item = collection.cases().get(index);
            testCase.setSpecId(spec.getSpecId());
            testCase.setPlanId(plan.plan().getId());
            testCase.setBaseUrl(CollectionMapper.resolveStoredBaseUrl(
                    item.baseUrl(), collection.spec().baseUrl(), spec.getBaseUrl()));
            java.util.Optional<StudioTestCase> existing = storage.findTestCase(testCase.getId());
            if (StringUtils.isNotBlank(environmentName)) {
                testCase.setEnvironments(importedEnvironments(existing.orElse(null), environmentName));
            }
            if (!publishReady) {
                testCase.setLifecycle(CaseLifecycle.DRAFT);
            }
            ensureEnvironments(storage, spec.getSpecId(), testCase.getEnvironments());
            if (existing.isPresent()) {
                updated++;
            } else {
                created++;
            }
            boolean unchanged = existing.isPresent()
                    && storage.findCaseDraft(testCase.getId()).isEmpty()
                    && com.tx.api.test.generator.studio.service.CaseVersionDocument.sameCase(existing.get(), testCase);
            if (!unchanged) {
                storage.saveTestCase(testCase);
                storage.deleteCaseDraft(testCase.getId());
            }
            FileCounts fileCounts = importFiles(storage, item, binaries);
            importedFiles += fileCounts.imported();
            skippedFiles += fileCounts.skipped();
        }
        if (!variables.isEmpty()) {
            List<StudioVariable> current = storage.findCollectionVariables(spec.getSpecId())
                    .map(StudioVariables::fromDocument)
                    .orElse(List.of());
            storage.saveCollectionVariables(spec.getSpecId(),
                    StudioVariables.toDocument(StudioVariables.normalize(
                            StudioVariables.merge(current, variables), "Collection")));
        }
        if (settings != null && !settings.isEmpty()) {
            SharedSettings current = storage.findCollectionSettings(spec.getSpecId())
                    .map(SharedSettings::fromDocument)
                    .orElse(SharedSettings.empty());
            storage.saveCollectionSettings(spec.getSpecId(),
                    SharedSettings.normalize(SharedSettings.merge(current, settings)).toDocument());
        }
        return new CollectionImportResult(
                spec.getSpecId(),
                spec.getName(),
                plan.plan().getId(),
                plan.plan().getName(),
                plan.created(),
                created,
                updated,
                importedFiles,
                skippedFiles
        );
    }

    /**
     * Where an imported case belongs: a new case goes to the environment it was imported into. A case that already
     * exists keeps the environments it is in and gains that one, so importing with another environment selected never
     * takes a case out of the environments it was in. A case in every environment (no list) stays there.
     */
    static List<String> importedEnvironments(StudioTestCase existing, String environmentName) {
        if (existing == null) {
            return EnvironmentScope.normalize(List.of(environmentName));
        }
        List<String> current = existing.getEnvironments();
        if (current == null || current.isEmpty()) {
            return current;
        }
        List<String> merged = new ArrayList<>(current);
        if (merged.stream().noneMatch(name -> EnvironmentScope.matchesIgnoreCase(name, environmentName))) {
            merged.add(environmentName);
        }
        return EnvironmentScope.normalize(merged);
    }

    private static StoredSpec resolveSpec(StudioStorage storage, StudioCollection collection, String targetSpecId) {
        if (StringUtils.isNotBlank(targetSpecId)) {
            return storage.findSpec(targetSpecId)
                    .orElseThrow(() -> new NoSuchElementException("Spec not found: " + targetSpecId));
        }
        StoredSpec byId = storage.findSpec(collection.spec().id()).orElse(null);
        if (byId != null) {
            return byId;
        }
        String wanted = collection.spec().name().trim();
        List<StoredSpec> matches = new ArrayList<>();
        for (StoredSpec spec : storage.listSpecs()) {
            if (spec.getName() != null && wanted.equalsIgnoreCase(spec.getName().trim())) {
                matches.add(spec);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            throw new CollectionFormatException(
                    "Multiple specs named '" + wanted + "'. Open one and import there, or pass specId.");
        }
        throw new CollectionFormatException("Load spec '" + wanted + "' before importing this collection");
    }

    private static PlanResolution resolvePlan(StudioStorage storage, StoredSpec spec, StudioCollectionPlan wanted) {
        StudioTestPlan byId = storage.findPlan(wanted.id()).orElse(null);
        if (byId != null && spec.getSpecId().equals(byId.getSpecId())) {
            byId.setName(wanted.name());
            byId.setDescription(wanted.description());
            storage.savePlan(byId);
            return new PlanResolution(byId, false);
        }
        for (StudioTestPlan existing : storage.listPlans(spec.getSpecId())) {
            if (wanted.name().equalsIgnoreCase(StringUtils.defaultString(existing.getName()))) {
                existing.setDescription(wanted.description());
                storage.savePlan(existing);
                return new PlanResolution(existing, false);
            }
        }
        StudioTestPlan created = new StudioTestPlan();
        created.setId(byId == null ? wanted.id() : UUID.randomUUID().toString());
        created.setSpecId(spec.getSpecId());
        created.setName(wanted.name());
        created.setDescription(wanted.description());
        storage.savePlan(created);
        return new PlanResolution(created, true);
    }

    private static FileCounts importFiles(StudioStorage storage, StudioCollectionCase item, Map<String, byte[]> files) {
        if (item.files() == null) {
            return new FileCounts(0, 0);
        }
        if (item.files().isEmpty()) {
            storage.assignRequestFiles(item.id(), List.of());
            return new FileCounts(0, 0);
        }
        List<String> kept = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        for (StudioCollectionFile file : item.files()) {
            byte[] content = files.get(file.path());
            if (content == null) {
                skipped++;
                continue;
            }
            if (storage.findRequestFile(item.id(), file.fileId()).isPresent()) {
                storage.deleteRequestFile(item.id(), file.fileId());
            }
            StudioRequestFile stored = new StudioRequestFile();
            stored.setFileId(file.fileId());
            stored.setTestCaseId(item.id());
            stored.setFileName(file.fileName());
            stored.setContentType(file.contentType());
            stored.setContent(content);
            stored.setSizeBytes(content.length);
            stored.setCreatedAt(Instant.now());
            storage.saveRequestFile(stored);
            kept.add(file.fileId());
            imported++;
        }
        if (imported > 0) {
            storage.assignRequestFiles(item.id(), kept);
        }
        return new FileCounts(imported, skipped);
    }

    private record PlanResolution(StudioTestPlan plan, boolean created) {
    }

    private record FileCounts(int imported, int skipped) {
    }
    /**
     * Cases link to environments by ID, so a name the collection uses gets an empty environment of that name if
     * there isn't one. Importing the environment file afterwards fills it in, matched by name.
     */
    static void ensureEnvironments(StudioStorage storage, String specId, List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (com.tx.api.test.generator.studio.model.StoredEnvironment stored : storage.listEnvironments(specId)) {
            existing.add(stored.getName().trim().toLowerCase(java.util.Locale.ROOT));
        }
        for (String name : names) {
            if (StringUtils.isBlank(name) || !existing.add(name.trim().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            com.tx.api.test.generator.studio.model.StoredEnvironment created =
                    new com.tx.api.test.generator.studio.model.StoredEnvironment();
            created.setId(java.util.UUID.randomUUID().toString());
            created.setSpecId(specId);
            created.setName(name.trim());
            created.setDocumentJson(CollectionDocuments.toJson(
                    EnvironmentMapper.fromInputs(name.trim(), null, "NONE", List.of())));
            storage.saveEnvironment(created);
        }
    }
}
