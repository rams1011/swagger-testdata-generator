package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.dto.CollectionImportResult;
import com.tx.api.test.generator.studio.model.CaseLifecycle;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
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
        StoredSpec spec = resolveSpec(storage, collection, targetSpecId);
        PlanResolution plan = resolvePlan(storage, spec, collection.plan());
        CollectionSnapshot snapshot = CollectionMapper.toSnapshot(collection);
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
            if (StringUtils.isNotBlank(environmentName)) {
                testCase.setEnvironments(EnvironmentScope.normalize(List.of(environmentName)));
            }
            if (!publishReady) {
                testCase.setLifecycle(CaseLifecycle.DRAFT);
            }
            if (storage.findTestCase(testCase.getId()).isPresent()) {
                updated++;
            } else {
                created++;
            }
            storage.saveTestCase(testCase);
            FileCounts fileCounts = importFiles(storage, item, binaries);
            importedFiles += fileCounts.imported();
            skippedFiles += fileCounts.skipped();
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
}
