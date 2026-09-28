package com.tx.api.test.generator.studio.storage;

import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.audit.CaseAudit;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioRun;
import com.tx.api.test.generator.studio.model.StudioRunResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class ExcelStudioStorage implements StudioStorage {

    private static final DataFormatter FORMATTER = new DataFormatter();
    private static final String SPECS = "Specs";
    private static final String PLANS = "Plans";
    private static final String CASES = "TestCases";
    private static final String PARAMS = "Params";
    private static final String ENVIRONMENTS = "Environments";
    private static final String[] SPEC_HEADERS = {
            "spec_id", "title", "version", "description", "base_url", "source_location",
            "source_content", "operation_count", "created_at", "updated_at", "name", "label"
    };
    private static final String[] PLAN_HEADERS = {
            "plan_id", "spec_id", "name", "description", "created_at", "updated_at"
    };
    private static final String[] CASE_HEADERS = {
            "test_case_id", "spec_id", "operation_key", "test_name", "http_method", "raw_path",
            "endpoint", "base_url", "content_type", "request_body_json", "response_schema_json",
            "status_code", "response_type", "expected_response", "enabled", "test_group",
            "json_path_assertions", "max_response_time_ms", "created_at", "updated_at", "plan_id", "extracts",
            "environment_names", "created_by_account_id", "created_by_label", "updated_by_account_id", "updated_by_label"
    };
    private static final String[] PARAM_HEADERS = {
            "test_case_id", "param_in", "param_name", "param_value"
    };
    private static final String[] ENVIRONMENT_HEADERS = {
            "environment_id", "spec_id", "name", "document_json", "created_at", "updated_at"
    };

    private final Path workbookPath;
    private final Object lock = new Object();

    public ExcelStudioStorage(StudioProperties properties) {
        Path path = Path.of(properties.getExcel().getFile()).toAbsolutePath().normalize();
        if ("TestData.xlsx".equalsIgnoreCase(path.getFileName().toString())) {
            throw new IllegalStateException(
                    "Studio Excel storage cannot use TestData.xlsx. That file is reserved for api-automation export "
                            + "(single TestData sheet). Set testdata.excel.file to an internal workbook such as "
                            + "studio-testdata.xlsx.");
        }
        this.workbookPath = path;
    }

    @Override
    public void saveSpec(StoredSpec spec) {
        synchronized (lock) {
            WorkbookState state = load();
            Instant now = Instant.now();
            StoredSpec existing = state.specs.get(spec.getSpecId());
            spec.setCreatedAt(existing != null && existing.getCreatedAt() != null ? existing.getCreatedAt() : now);
            spec.setUpdatedAt(now);
            spec.setSourceContent(persistSpecContent(spec));
            state.specs.put(spec.getSpecId(), spec);
            write(state);
        }
    }

    @Override
    public Optional<StoredSpec> findSpec(String specId) {
        synchronized (lock) {
            StoredSpec spec = load().specs.get(specId);
            if (spec != null) {
                spec.setSourceContent(readSpecContent(spec.getSourceContent()));
            }
            return Optional.ofNullable(spec);
        }
    }

    @Override
    public List<StoredSpec> listSpecs() {
        synchronized (lock) {
            return new ArrayList<>(load().specs.values());
        }
    }

    @Override
    public void deleteSpec(String specId) {
        synchronized (lock) {
            WorkbookState state = load();
            if (state.specs.remove(specId) == null) {
                throw new NoSuchElementException("Spec not found: " + specId);
            }
            List<String> removedCases = new ArrayList<>();
            state.plans.entrySet().removeIf(entry -> specId.equals(entry.getValue().getSpecId()));
            state.environments.entrySet().removeIf(entry -> specId.equals(entry.getValue().getSpecId()));
            state.cases.entrySet().removeIf(entry -> {
                if (specId.equals(entry.getValue().getSpecId())) {
                    removedCases.add(entry.getKey());
                    return true;
                }
                return false;
            });
            removedCases.forEach(id -> {
                state.caseCreatedAt.remove(id);
                state.caseUpdatedAt.remove(id);
            });
            write(state);
            removedCases.forEach(this::deleteRequestFilesForCaseUnlocked);
        }
    }

    @Override
    public void savePlan(StudioTestPlan plan) {
        synchronized (lock) {
            WorkbookState state = load();
            if (!state.specs.containsKey(plan.getSpecId())) {
                throw new IllegalStateException("Spec not found in Excel store: " + plan.getSpecId());
            }
            Instant now = Instant.now();
            StudioTestPlan existing = state.plans.get(plan.getId());
            plan.setCreatedAt(existing != null && existing.getCreatedAt() != null ? existing.getCreatedAt() : now);
            plan.setUpdatedAt(now);
            state.plans.put(plan.getId(), plan);
            write(state);
        }
    }

    @Override
    public void deletePlan(String planId) {
        synchronized (lock) {
            WorkbookState state = load();
            if (state.plans.remove(planId) == null) {
                throw new NoSuchElementException("Test plan not found: " + planId);
            }
            List<String> removed = new ArrayList<>();
            state.cases.entrySet().removeIf(entry -> {
                if (planId.equals(entry.getValue().getPlanId())) {
                    removed.add(entry.getKey());
                    return true;
                }
                return false;
            });
            removed.forEach(id -> {
                state.caseCreatedAt.remove(id);
                state.caseUpdatedAt.remove(id);
            });
            write(state);
            removed.forEach(this::deleteRequestFilesForCaseUnlocked);
        }
    }

    @Override
    public Optional<StudioTestPlan> findPlan(String planId) {
        synchronized (lock) {
            return Optional.ofNullable(load().plans.get(planId));
        }
    }

    @Override
    public List<StudioTestPlan> listPlans(String specId) {
        synchronized (lock) {
            return load().plans.values().stream()
                    .filter(plan -> specId == null || specId.equals(plan.getSpecId()))
                    .sorted((left, right) -> StringUtils.defaultString(left.getName())
                            .compareToIgnoreCase(StringUtils.defaultString(right.getName())))
                    .toList();
        }
    }

    @Override
    public List<StoredEnvironment> listEnvironments(String specId) {
        synchronized (lock) {
            return load().environments.values().stream()
                    .filter(environment -> specId == null || specId.equals(environment.getSpecId()))
                    .sorted(Comparator.comparing(environment -> StringUtils.defaultString(environment.getName()),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    @Override
    public Optional<StoredEnvironment> findEnvironment(String environmentId) {
        synchronized (lock) {
            return Optional.ofNullable(load().environments.get(environmentId));
        }
    }

    @Override
    public void saveEnvironment(StoredEnvironment environment) {
        if (environment == null || StringUtils.isBlank(environment.getId())
                || StringUtils.isBlank(environment.getSpecId())) {
            throw new IllegalArgumentException("Environment id and spec id are required");
        }
        synchronized (lock) {
            WorkbookState state = load();
            if (!state.specs.containsKey(environment.getSpecId())) {
                throw new IllegalStateException("Spec not found in Excel store: " + environment.getSpecId());
            }
            Instant now = Instant.now();
            StoredEnvironment existing = state.environments.get(environment.getId());
            environment.setCreatedAt(existing != null && existing.getCreatedAt() != null
                    ? existing.getCreatedAt() : now);
            environment.setUpdatedAt(now);
            state.environments.put(environment.getId(), environment);
            write(state);
        }
    }

    @Override
    public void deleteEnvironment(String environmentId) {
        synchronized (lock) {
            WorkbookState state = load();
            if (state.environments.remove(environmentId) == null) {
                throw new NoSuchElementException("Environment not found: " + environmentId);
            }
            write(state);
        }
    }

    @Override
    public void saveTestCase(StudioTestCase testCase) {
        synchronized (lock) {
            WorkbookState state = load();
            if (!state.specs.containsKey(testCase.getSpecId())) {
                throw new IllegalStateException("Spec not found in Excel store: " + testCase.getSpecId());
            }
            Instant now = Instant.now();
            StudioTestCase existing = state.cases.get(testCase.getId());
            if (existing != null) {
                testCase.setCreatedAt(existing.getCreatedAt());
                testCase.setCreatedByAccountId(existing.getCreatedByAccountId());
                testCase.setCreatedByLabel(existing.getCreatedByLabel());
                CaseAudit.stampUpdate(testCase, now);
            } else {
                CaseAudit.stampCreate(testCase, now);
            }
            state.caseCreatedAt.put(testCase.getId(), testCase.getCreatedAt());
            state.caseUpdatedAt.put(testCase.getId(), testCase.getUpdatedAt());
            state.cases.put(testCase.getId(), testCase);
            write(state);
        }
    }

    @Override
    public void deleteTestCase(String testCaseId) {
        synchronized (lock) {
            WorkbookState state = load();
            if (state.cases.remove(testCaseId) == null) {
                throw new NoSuchElementException("Test case not found: " + testCaseId);
            }
            state.caseCreatedAt.remove(testCaseId);
            state.caseUpdatedAt.remove(testCaseId);
            write(state);
            deleteRequestFilesForCaseUnlocked(testCaseId);
        }
    }

    @Override
    public Optional<StudioTestCase> findTestCase(String testCaseId) {
        synchronized (lock) {
            return Optional.ofNullable(load().cases.get(testCaseId));
        }
    }

    @Override
    public List<StudioTestCase> listTestCases(String specId, String planId, String operationKey) {
        synchronized (lock) {
            WorkbookState state = load();
            return state.cases.values().stream()
                    .filter(testCase -> specId == null || specId.equals(testCase.getSpecId()))
                    .filter(testCase -> planId == null || planId.equals(testCase.getPlanId()))
                    .filter(testCase -> operationKey == null || operationKey.equals(testCase.getOperationKey()))
                    .sorted((left, right) -> {
                        Instant createdLeft = Optional.ofNullable(state.caseCreatedAt.get(left.getId()))
                                .orElse(Instant.EPOCH);
                        Instant createdRight = Optional.ofNullable(state.caseCreatedAt.get(right.getId()))
                                .orElse(Instant.EPOCH);
                        int created = createdLeft.compareTo(createdRight);
                        if (created != 0) {
                            return created;
                        }
                        return StringUtils.defaultString(left.getTestName())
                                .compareToIgnoreCase(StringUtils.defaultString(right.getTestName()));
                    })
                    .toList();
        }
    }

    @Override
    public void saveRequestFile(StudioRequestFile file) {
        synchronized (lock) {
            try {
                Path dir = requestFileDir(file.getTestCaseId());
                Files.createDirectories(dir);
                Files.write(dir.resolve(file.getFileId() + ".bin"), file.getContent() == null ? new byte[0] : file.getContent());
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("fileId", file.getFileId());
                meta.put("testCaseId", file.getTestCaseId());
                meta.put("fileName", file.getFileName());
                meta.put("contentType", file.getContentType());
                meta.put("sizeBytes", file.getSizeBytes());
                meta.put("createdAt", formatInstant(file.getCreatedAt()));
                Files.writeString(dir.resolve(file.getFileId() + ".meta.json"), JsonMaps.toJson(meta), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to store request file " + file.getFileName(), exception);
            }
        }
    }

    @Override
    public Optional<StudioRequestFile> findRequestFile(String testCaseId, String fileId) {
        synchronized (lock) {
            Path metaFile = requestFileDir(testCaseId).resolve(fileId + ".meta.json");
            Path binFile = requestFileDir(testCaseId).resolve(fileId + ".bin");
            if (!Files.exists(metaFile) || !Files.exists(binFile)) {
                return Optional.empty();
            }
            try {
                StudioRequestFile file = fromMeta(JsonMaps.toObjectMap(Files.readString(metaFile, StandardCharsets.UTF_8)));
                file.setContent(Files.readAllBytes(binFile));
                return Optional.of(file);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read request file " + fileId, exception);
            }
        }
    }

    @Override
    public void deleteRequestFile(String testCaseId, String fileId) {
        synchronized (lock) {
            Path dir = requestFileDir(testCaseId);
            boolean deleted = false;
            try {
                deleted |= Files.deleteIfExists(dir.resolve(fileId + ".bin"));
                deleted |= Files.deleteIfExists(dir.resolve(fileId + ".meta.json"));
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to delete request file " + fileId, exception);
            }
            if (!deleted) {
                throw new NoSuchElementException("Request file not found: " + fileId);
            }
        }
    }

    @Override
    public List<StudioRequestFile> listRequestFiles(String testCaseId) {
        synchronized (lock) {
            Path dir = requestFileDir(testCaseId);
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            try (Stream<Path> files = Files.list(dir)) {
                return files
                        .filter(path -> path.getFileName().toString().endsWith(".meta.json"))
                        .map(path -> {
                            try {
                                return fromMeta(JsonMaps.toObjectMap(Files.readString(path, StandardCharsets.UTF_8)));
                            } catch (IOException exception) {
                                throw new IllegalStateException("Failed to read request file metadata: " + path, exception);
                            }
                        })
                        .toList();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to list request files for case " + testCaseId, exception);
            }
        }
    }

    @Override
    public void assignRequestFiles(String testCaseId, List<String> fileIds) {
        synchronized (lock) {
            Set<String> keep = new HashSet<>();
            if (fileIds != null) {
                fileIds.stream().filter(id -> id != null && !id.isBlank()).forEach(keep::add);
            }
            Path dir = requestFileDir(testCaseId);
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path metaPath : files.filter(path -> path.getFileName().toString().endsWith(".meta.json")).toList()) {
                    StudioRequestFile stored = fromMeta(JsonMaps.toObjectMap(
                            Files.readString(metaPath, StandardCharsets.UTF_8)));
                    if (keep.contains(stored.getFileId())) {
                        stored.setTestCaseId(testCaseId);
                        writeRequestFileMeta(dir, stored);
                    } else {
                        Files.deleteIfExists(dir.resolve(stored.getFileId() + ".bin"));
                        Files.deleteIfExists(metaPath);
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to assign request files for case " + testCaseId, exception);
            }
        }
    }

    @Override
    public void deleteRequestFilesForCase(String testCaseId) {
        synchronized (lock) {
            deleteRequestFilesForCaseUnlocked(testCaseId);
        }
    }

    @Override
    public void saveRun(StudioRun run) {
        // History is JDBC-only. Excel storage does not persist runs.
    }

    @Override
    public Optional<StudioRun> findRun(String runId) {
        return Optional.empty();
    }

    @Override
    public Optional<StudioRunResult> findRunResult(String runId, String resultId) {
        return Optional.empty();
    }

    @Override
    public List<StudioRun> listRuns(RunHistoryFilter filter) {
        return List.of();
    }

    @Override
    public void deleteRun(String runId) {
        throw new NoSuchElementException("Run not found: " + runId);
    }

    private WorkbookState load() {
        WorkbookState state = new WorkbookState();
        if (!Files.exists(workbookPath)) {
            return state;
        }
        try (InputStream input = Files.newInputStream(workbookPath);
             Workbook workbook = new XSSFWorkbook(input)) {
            readSpecs(workbook.getSheet(SPECS), state);
            readPlans(workbook.getSheet(PLANS), state);
            readCases(workbook.getSheet(CASES), state);
            readParams(workbook.getSheet(PARAMS), state);
            readEnvironments(workbook.getSheet(ENVIRONMENTS), state);
            return state;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read Excel store: " + workbookPath, exception);
        }
    }

    private void write(WorkbookState state) {
        try {
            Path parent = workbookPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Workbook workbook = new XSSFWorkbook()) {
                writeSpecs(workbook.createSheet(SPECS), state);
                writePlans(workbook.createSheet(PLANS), state);
                writeCases(workbook.createSheet(CASES), state);
                writeParams(workbook.createSheet(PARAMS), state);
                writeEnvironments(workbook.createSheet(ENVIRONMENTS), state);
                try (OutputStream output = Files.newOutputStream(workbookPath)) {
                    workbook.write(output);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write Excel store: " + workbookPath, exception);
        }
    }

    private void readSpecs(Sheet sheet, WorkbookState state) {
        if (sheet == null) {
            return;
        }
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || StringUtils.isBlank(cell(row, 0))) {
                continue;
            }
            StoredSpec spec = new StoredSpec();
            spec.setSpecId(cell(row, 0));
            spec.setTitle(cell(row, 1));
            spec.setVersion(cell(row, 2));
            spec.setDescription(cell(row, 3));
            spec.setBaseUrl(cell(row, 4));
            spec.setSourceLocation(cell(row, 5));
            spec.setSourceContent(cell(row, 6));
            spec.setOperationCount(parseInt(cell(row, 7), 0));
            spec.setCreatedAt(parseInstant(cell(row, 8)));
            spec.setUpdatedAt(parseInstant(cell(row, 9)));
            spec.setName(cell(row, 10));
            spec.setLabel(StringUtils.defaultIfBlank(cell(row, 11), StoredSpec.DEFAULT_LABEL));
            state.specs.put(spec.getSpecId(), spec);
        }
    }

    private void readPlans(Sheet sheet, WorkbookState state) {
        if (sheet == null) {
            return;
        }
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || StringUtils.isBlank(cell(row, 0))) {
                continue;
            }
            StudioTestPlan plan = new StudioTestPlan();
            plan.setId(cell(row, 0));
            plan.setSpecId(cell(row, 1));
            plan.setName(cell(row, 2));
            plan.setDescription(cell(row, 3));
            plan.setCreatedAt(parseInstant(cell(row, 4)));
            plan.setUpdatedAt(parseInstant(cell(row, 5)));
            state.plans.put(plan.getId(), plan);
        }
    }

    private void readCases(Sheet sheet, WorkbookState state) {
        if (sheet == null) {
            return;
        }
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || StringUtils.isBlank(cell(row, 0))) {
                continue;
            }
            StudioTestCase testCase = new StudioTestCase();
            testCase.setId(cell(row, 0));
            testCase.setSpecId(cell(row, 1));
            testCase.setOperationKey(cell(row, 2));
            testCase.setTestName(cell(row, 3));
            testCase.setMethod(cell(row, 4));
            testCase.setRawPath(cell(row, 5));
            testCase.setEndpoint(cell(row, 6));
            testCase.setBaseUrl(cell(row, 7));
            testCase.setContentType(cell(row, 8));
            testCase.setRequestBody(JsonMaps.toObject(cell(row, 9)));
            testCase.setResponseJsonSchema(JsonMaps.toObjectMap(cell(row, 10)));
            testCase.setStatusCode(parseInt(cell(row, 11), 200));
            testCase.setResponseType(cell(row, 12));
            testCase.setExpectedResponse(cell(row, 13));
            testCase.setEnabled(cell(row, 14));
            testCase.setTestGroup(cell(row, 15));
            testCase.setJsonPathAssertions(cell(row, 16));
            testCase.setMaxResponseTimeMs(parseLong(cell(row, 17), 3000L));
            testCase.setPlanId(cell(row, 20));
            testCase.setExtracts(cell(row, 21));
            testCase.setEnvironments(JsonMaps.toStringList(cell(row, 22)));
            testCase.setCreatedAt(parseInstant(cell(row, 18)));
            testCase.setUpdatedAt(parseInstant(cell(row, 19)));
            testCase.setCreatedByAccountId(cell(row, 23));
            testCase.setCreatedByLabel(cell(row, 24));
            testCase.setUpdatedByAccountId(cell(row, 25));
            testCase.setUpdatedByLabel(cell(row, 26));
            state.cases.put(testCase.getId(), testCase);
            state.caseCreatedAt.put(testCase.getId(), testCase.getCreatedAt());
            state.caseUpdatedAt.put(testCase.getId(), testCase.getUpdatedAt());
        }
    }

    private void readParams(Sheet sheet, WorkbookState state) {
        if (sheet == null) {
            return;
        }
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || StringUtils.isBlank(cell(row, 0))) {
                continue;
            }
            StudioTestCase testCase = state.cases.get(cell(row, 0));
            if (testCase == null) {
                continue;
            }
            String in = cell(row, 1);
            String name = cell(row, 2);
            String value = cell(row, 3);
            if ("path".equalsIgnoreCase(in)) {
                testCase.getPathParams().put(name, value);
            } else if ("header".equalsIgnoreCase(in)) {
                testCase.getHeaders().put(name, value);
            } else if ("cookie".equalsIgnoreCase(in)) {
                testCase.getCookieParams().put(name, value);
            } else {
                testCase.getQueryParams().put(name, value);
            }
        }
    }

    private void readEnvironments(Sheet sheet, WorkbookState state) {
        if (sheet == null) {
            return;
        }
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || StringUtils.isBlank(cell(row, 0))) {
                continue;
            }
            StoredEnvironment environment = new StoredEnvironment();
            environment.setId(cell(row, 0));
            environment.setSpecId(cell(row, 1));
            environment.setName(cell(row, 2));
            environment.setDocumentJson(cell(row, 3));
            environment.setCreatedAt(parseInstant(cell(row, 4)));
            environment.setUpdatedAt(parseInstant(cell(row, 5)));
            state.environments.put(environment.getId(), environment);
        }
    }

    private void writeEnvironments(Sheet sheet, WorkbookState state) {
        writeHeader(sheet, ENVIRONMENT_HEADERS);
        int rowIndex = 1;
        for (StoredEnvironment environment : state.environments.values()) {
            Row row = sheet.createRow(rowIndex++);
            set(row, 0, environment.getId());
            set(row, 1, environment.getSpecId());
            set(row, 2, environment.getName());
            set(row, 3, environment.getDocumentJson());
            set(row, 4, formatInstant(environment.getCreatedAt()));
            set(row, 5, formatInstant(environment.getUpdatedAt()));
        }
    }

    private void writeSpecs(Sheet sheet, WorkbookState state) {
        writeHeader(sheet, SPEC_HEADERS);
        int rowIndex = 1;
        for (StoredSpec spec : state.specs.values()) {
            Row row = sheet.createRow(rowIndex++);
            set(row, 0, spec.getSpecId());
            set(row, 1, spec.getTitle());
            set(row, 2, spec.getVersion());
            set(row, 3, spec.getDescription());
            set(row, 4, spec.getBaseUrl());
            set(row, 5, spec.getSourceLocation());
            set(row, 6, spec.getSourceContent());
            set(row, 7, String.valueOf(spec.getOperationCount()));
            set(row, 8, formatInstant(spec.getCreatedAt()));
            set(row, 9, formatInstant(spec.getUpdatedAt()));
            set(row, 10, spec.getName());
            set(row, 11, StringUtils.defaultIfBlank(spec.getLabel(), StoredSpec.DEFAULT_LABEL));
        }
    }

    private void writePlans(Sheet sheet, WorkbookState state) {
        writeHeader(sheet, PLAN_HEADERS);
        int rowIndex = 1;
        for (StudioTestPlan plan : state.plans.values()) {
            Row row = sheet.createRow(rowIndex++);
            set(row, 0, plan.getId());
            set(row, 1, plan.getSpecId());
            set(row, 2, plan.getName());
            set(row, 3, plan.getDescription());
            set(row, 4, formatInstant(plan.getCreatedAt()));
            set(row, 5, formatInstant(plan.getUpdatedAt()));
        }
    }

    private void writeCases(Sheet sheet, WorkbookState state) {
        writeHeader(sheet, CASE_HEADERS);
        int rowIndex = 1;
        for (StudioTestCase testCase : state.cases.values()) {
            Row row = sheet.createRow(rowIndex++);
            set(row, 0, testCase.getId());
            set(row, 1, testCase.getSpecId());
            set(row, 2, testCase.getOperationKey());
            set(row, 3, testCase.getTestName());
            set(row, 4, testCase.getMethod());
            set(row, 5, testCase.getRawPath());
            set(row, 6, testCase.getEndpoint());
            set(row, 7, testCase.getBaseUrl());
            set(row, 8, testCase.getContentType());
            set(row, 9, JsonMaps.toJson(testCase.getRequestBody()));
            set(row, 10, JsonMaps.toJson(testCase.getResponseJsonSchema()));
            set(row, 11, String.valueOf(testCase.getStatusCode()));
            set(row, 12, testCase.getResponseType());
            set(row, 13, testCase.getExpectedResponse());
            set(row, 14, testCase.getEnabled());
            set(row, 15, testCase.getTestGroup());
            set(row, 16, testCase.getJsonPathAssertions());
            set(row, 17, String.valueOf(testCase.getMaxResponseTimeMs()));
            set(row, 18, formatInstant(state.caseCreatedAt.get(testCase.getId())));
            set(row, 19, formatInstant(state.caseUpdatedAt.get(testCase.getId())));
            set(row, 20, testCase.getPlanId());
            set(row, 21, testCase.getExtracts());
            set(row, 22, JsonMaps.toJson(testCase.getEnvironments()));
            set(row, 23, testCase.getCreatedByAccountId());
            set(row, 24, testCase.getCreatedByLabel());
            set(row, 25, testCase.getUpdatedByAccountId());
            set(row, 26, testCase.getUpdatedByLabel());
        }
    }

    private void writeParams(Sheet sheet, WorkbookState state) {
        writeHeader(sheet, PARAM_HEADERS);
        int rowIndex = 1;
        for (StudioTestCase testCase : state.cases.values()) {
            rowIndex = writeParamGroup(sheet, rowIndex, testCase.getId(), "path", testCase.getPathParams());
            rowIndex = writeParamGroup(sheet, rowIndex, testCase.getId(), "query", testCase.getQueryParams());
            rowIndex = writeParamGroup(sheet, rowIndex, testCase.getId(), "header", testCase.getHeaders());
            rowIndex = writeParamGroup(sheet, rowIndex, testCase.getId(), "cookie", testCase.getCookieParams());
        }
    }

    private int writeParamGroup(Sheet sheet, int rowIndex, String testCaseId, String in, Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Row row = sheet.createRow(rowIndex++);
            set(row, 0, testCaseId);
            set(row, 1, in);
            set(row, 2, entry.getKey());
            set(row, 3, entry.getValue());
        }
        return rowIndex;
    }

    private void deleteRequestFilesForCaseUnlocked(String testCaseId) {
        if (StringUtils.isBlank(testCaseId)) {
            return;
        }
        Path dir = requestFileDir(testCaseId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup next to the Excel workbook
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete request files for case " + testCaseId, exception);
        }
    }

    private void writeRequestFileMeta(Path dir, StudioRequestFile file) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fileId", file.getFileId());
        meta.put("testCaseId", file.getTestCaseId());
        meta.put("fileName", file.getFileName());
        meta.put("contentType", file.getContentType());
        meta.put("sizeBytes", file.getSizeBytes());
        meta.put("createdAt", formatInstant(file.getCreatedAt()));
        Files.writeString(dir.resolve(file.getFileId() + ".meta.json"), JsonMaps.toJson(meta), StandardCharsets.UTF_8);
    }

    private Path requestFilesRoot() {
        Path parent = workbookPath.getParent() == null ? Path.of(".") : workbookPath.getParent();
        return parent.resolve("studio-files");
    }

    private Path requestFileDir(String testCaseId) {
        return requestFilesRoot().resolve(testCaseId);
    }

    private StudioRequestFile fromMeta(Map<String, Object> meta) {
        StudioRequestFile file = new StudioRequestFile();
        file.setFileId(asString(meta.get("fileId")));
        file.setTestCaseId(asString(meta.get("testCaseId")));
        file.setFileName(asString(meta.get("fileName")));
        file.setContentType(asString(meta.get("contentType")));
        file.setSizeBytes(asLong(meta.get("sizeBytes")));
        file.setCreatedAt(parseInstant(asString(meta.get("createdAt"))));
        return file;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.isNotBlank(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private String persistSpecContent(StoredSpec spec) {
        String content = spec.getSourceContent();
        if (StringUtils.isBlank(content)) {
            return spec.getSourceContent();
        }
        try {
            Path specDir = workbookPath.getParent() == null
                    ? Path.of("specs")
                    : workbookPath.getParent().resolve("specs");
            Files.createDirectories(specDir);
            Path file = specDir.resolve(spec.getSpecId() + ".openapi.txt");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return "file:" + file.getFileName();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store OpenAPI content for spec " + spec.getSpecId(), exception);
        }
    }

    private String readSpecContent(String stored) {
        if (stored == null || !stored.startsWith("file:")) {
            return stored;
        }
        Path parent = workbookPath.getParent() == null ? Path.of(".") : workbookPath.getParent();
        Path file = parent.resolve("specs").resolve(stored.substring("file:".length()));
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read stored OpenAPI content: " + file, exception);
        }
    }

    private void writeHeader(Sheet sheet, String[] headers) {
        Row header = sheet.createRow(0);
        for (int index = 0; index < headers.length; index++) {
            set(header, index, headers[index]);
        }
    }

    private String cell(Row row, int index) {
        Cell cell = row.getCell(index);
        if (cell == null) {
            return "";
        }
        return FORMATTER.formatCellValue(cell);
    }

    private void set(Row row, int index, String value) {
        row.createCell(index).setCellValue(value == null ? "" : value);
    }

    private int parseInt(String value, int fallback) {
        try {
            return StringUtils.isBlank(value) ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return StringUtils.isBlank(value) ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private Instant parseInstant(String value) {
        if (StringUtils.isBlank(value)) {
            return Instant.now();
        }
        try {
            return Instant.parse(value.trim());
        } catch (Exception exception) {
            return Instant.now();
        }
    }

    private String formatInstant(Instant instant) {
        return instant == null ? Instant.now().toString() : instant.toString();
    }

    private static final class WorkbookState {
        private final Map<String, StoredSpec> specs = new LinkedHashMap<>();
        private final Map<String, StudioTestPlan> plans = new LinkedHashMap<>();
        private final Map<String, StudioTestCase> cases = new LinkedHashMap<>();
        private final Map<String, StoredEnvironment> environments = new LinkedHashMap<>();
        private final Map<String, Instant> caseCreatedAt = new LinkedHashMap<>();
        private final Map<String, Instant> caseUpdatedAt = new LinkedHashMap<>();
    }
}
