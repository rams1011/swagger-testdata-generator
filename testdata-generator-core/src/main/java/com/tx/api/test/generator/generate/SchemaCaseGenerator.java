package com.tx.api.test.generator.generate;

import com.tx.api.test.generator.catalog.OperationDefaults;
import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.catalog.ParameterField;
import com.tx.api.test.generator.catalog.RequestBodySchema;
import com.tx.api.test.generator.catalog.ResponseSchema;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a bounded set of positive and negative test cases from an operation schema.
 */
public final class SchemaCaseGenerator {

    static final int MAX_REQUIRED_OMISSIONS = 5;
    static final int MAX_MAX_LENGTH = 256;
    private static final String NA = "NA";
    private static final String INVALID_ENUM = "__not_in_enum__";
    private static final String EXTRA_PROPERTY = "__unexpected";

    public List<GeneratedTestCase> generate(OperationPage operation) {
        List<GeneratedTestCase> cases = new ArrayList<>();
        if (operation == null) {
            return cases;
        }
        Draft happy = Draft.from(operation);
        cases.add(happyCase(operation, happy));
        addMissingRequiredBodyFields(cases, operation, happy);
        addMissingRequiredParameters(cases, operation, happy);
        addInvalidEnum(cases, operation, happy);
        addInvalidType(cases, operation, happy);
        addConstraintViolations(cases, operation, happy);
        addExtraProperty(cases, operation, happy);
        addNotFound(cases, operation, happy);
        addUnauthorized(cases, operation, happy);
        return cases;
    }

    private void addMissingRequiredBodyFields(List<GeneratedTestCase> cases, OperationPage operation, Draft happy) {
        BodyTarget target = BodyTarget.from(operation, happy.body);
        if (target == null) {
            return;
        }
        int added = 0;
        for (String field : JsonSchemaMaps.required(target.schema)) {
            if (added >= MAX_REQUIRED_OMISSIONS) {
                break;
            }
            if (!target.object.containsKey(field)) {
                continue;
            }
            Draft draft = happy.copy();
            BodyTarget mutated = BodyTarget.bind(operation, draft);
            if (mutated == null) {
                continue;
            }
            mutated.object.remove(field);
            cases.add(negativeCase(operation, "missing required body field " + field, draft));
            added++;
        }
    }

    private void addMissingRequiredParameters(List<GeneratedTestCase> cases, OperationPage operation, Draft happy) {
        if (operation.parameters() == null) {
            return;
        }
        int queryAdded = 0;
        int headerAdded = 0;
        for (ParameterField parameter : operation.parameters()) {
            if (parameter == null || !parameter.required() || StringUtils.isBlank(parameter.name())) {
                continue;
            }
            if ("path".equalsIgnoreCase(parameter.in())) {
                continue;
            }
            if ("query".equalsIgnoreCase(parameter.in())) {
                if (queryAdded >= MAX_REQUIRED_OMISSIONS || !happy.query.containsKey(parameter.name())) {
                    continue;
                }
                Draft draft = happy.copy();
                draft.query.remove(parameter.name());
                cases.add(negativeCase(operation, "missing required query " + parameter.name(), draft));
                queryAdded++;
            } else if ("header".equalsIgnoreCase(parameter.in())) {
                if (headerAdded >= MAX_REQUIRED_OMISSIONS || !happy.headers.containsKey(parameter.name())) {
                    continue;
                }
                Draft draft = happy.copy();
                draft.headers.remove(parameter.name());
                cases.add(negativeCase(operation, "missing required header " + parameter.name(), draft));
                headerAdded++;
            }
        }
    }

    private void addInvalidEnum(List<GeneratedTestCase> cases, OperationPage operation, Draft happy) {
        if (mutateFirstBodyProperty(operation, happy, cases, "invalid enum ", (name, schema, object) -> {
            if (JsonSchemaMaps.enums(schema).isEmpty()) {
                return false;
            }
            object.put(name, INVALID_ENUM);
            return true;
        })) {
            return;
        }
        mutateFirstParameter(operation, happy, cases, "invalid enum ", parameter -> {
            if (JsonSchemaMaps.enums(parameter.schema()).isEmpty()) {
                return null;
            }
            return INVALID_ENUM;
        });
    }

    private void addInvalidType(List<GeneratedTestCase> cases, OperationPage operation, Draft happy) {
        if (mutateFirstBodyProperty(operation, happy, cases, "invalid type ", (name, schema, object) -> {
            if (!JsonSchemaMaps.enums(schema).isEmpty()) {
                return false;
            }
            String invalid = invalidTypeValue(schema);
            if (invalid == null) {
                return false;
            }
            object.put(name, invalid);
            return true;
        })) {
            return;
        }
        mutateFirstParameter(operation, happy, cases, "invalid type ", parameter -> {
            if (!JsonSchemaMaps.enums(parameter.schema()).isEmpty()) {
                return null;
            }
            return invalidTypeValue(parameter.schema());
        });
    }

    private void addConstraintViolations(List<GeneratedTestCase> cases, OperationPage operation, Draft happy) {
        addFirstConstraint(cases, operation, happy, "below minLength ", (name, schema, object) -> {
            Integer minLength = JsonSchemaMaps.intConstraint(schema, "minLength");
            if (minLength == null || minLength <= 0 || !JsonSchemaMaps.isString(schema)) {
                return false;
            }
            object.put(name, "x".repeat(Math.max(0, minLength - 1)));
            return true;
        });
        addFirstConstraint(cases, operation, happy, "above maxLength ", (name, schema, object) -> {
            Integer maxLength = JsonSchemaMaps.intConstraint(schema, "maxLength");
            if (maxLength == null || maxLength < 0 || maxLength > MAX_MAX_LENGTH || !JsonSchemaMaps.isString(schema)) {
                return false;
            }
            object.put(name, "x".repeat(maxLength + 1));
            return true;
        });
        addFirstConstraint(cases, operation, happy, "below minimum ", (name, schema, object) -> {
            Number minimum = JsonSchemaMaps.numberConstraint(schema, "minimum");
            if (minimum == null || !JsonSchemaMaps.isNumber(schema)) {
                return false;
            }
            object.put(name, JsonSchemaMaps.isInteger(schema) ? minimum.longValue() - 1 : minimum.doubleValue() - 1);
            return true;
        });
        addFirstConstraint(cases, operation, happy, "above maximum ", (name, schema, object) -> {
            Number maximum = JsonSchemaMaps.numberConstraint(schema, "maximum");
            if (maximum == null || maximum.doubleValue() > 1_000_000_000d || !JsonSchemaMaps.isNumber(schema)) {
                return false;
            }
            object.put(name, JsonSchemaMaps.isInteger(schema) ? maximum.longValue() + 1 : maximum.doubleValue() + 1);
            return true;
        });
    }

    private void addFirstConstraint(List<GeneratedTestCase> cases, OperationPage operation, Draft happy,
                                    String suffixPrefix, BodyPropertyMutator mutator) {
        mutateFirstBodyProperty(operation, happy, cases, suffixPrefix, mutator);
    }

    private void addExtraProperty(List<GeneratedTestCase> cases, OperationPage operation, Draft happy) {
        BodyTarget target = BodyTarget.from(operation, happy.body);
        if (target == null || !JsonSchemaMaps.additionalPropertiesForbidden(target.schema)) {
            return;
        }
        Draft draft = happy.copy();
        BodyTarget mutated = BodyTarget.bind(operation, draft);
        if (mutated == null) {
            return;
        }
        String name = EXTRA_PROPERTY;
        int suffix = 1;
        while (mutated.object.containsKey(name)) {
            name = EXTRA_PROPERTY + "_" + suffix++;
        }
        mutated.object.put(name, "unexpected-value");
        cases.add(negativeCase(operation, "unexpected property", draft));
    }

    private void addNotFound(List<GeneratedTestCase> cases, OperationPage operation, Draft happy) {
        if (!statusCodes(operation).contains(404) || happy.path.isEmpty()) {
            return;
        }
        ParameterField pathParam = firstParameter(operation, "path");
        if (pathParam == null) {
            return;
        }
        Draft draft = happy.copy();
        draft.path.put(pathParam.name(), JsonSchemaMaps.isInteger(pathParam.schema()) ? "99999999" : "does-not-exist");
        cases.add(toCase(operation, opLabel(operation) + " — not found", false, draft, 404, "NONE", NA));
    }

    private void addUnauthorized(List<GeneratedTestCase> cases, OperationPage operation, Draft happy) {
        List<Integer> codes = statusCodes(operation);
        int status = codes.contains(401) ? 401 : codes.contains(403) ? 403 : 0;
        if (status == 0) {
            return;
        }
        Draft draft = happy.copy();
        draft.headers.put("Authorization", "Bearer invalid-token");
        cases.add(toCase(operation, opLabel(operation) + " — unauthorized", false, draft, status, "NONE", NA));
    }

    private boolean mutateFirstBodyProperty(OperationPage operation, Draft happy, List<GeneratedTestCase> cases,
                                            String suffixPrefix, BodyPropertyMutator mutator) {
        BodyTarget target = BodyTarget.from(operation, happy.body);
        if (target == null) {
            return false;
        }
        for (Map.Entry<String, Map<String, Object>> entry : JsonSchemaMaps.properties(target.schema).entrySet()) {
            Draft draft = happy.copy();
            BodyTarget mutated = BodyTarget.bind(operation, draft);
            if (mutated == null) {
                continue;
            }
            if (mutator.mutate(entry.getKey(), entry.getValue(), mutated.object)) {
                cases.add(negativeCase(operation, suffixPrefix + entry.getKey(), draft));
                return true;
            }
        }
        return false;
    }

    private void mutateFirstParameter(OperationPage operation, Draft happy, List<GeneratedTestCase> cases,
                                      String suffixPrefix, ParameterMutator mutator) {
        if (operation.parameters() == null) {
            return;
        }
        for (ParameterField parameter : operation.parameters()) {
            if (parameter == null || StringUtils.isBlank(parameter.name())) {
                continue;
            }
            String invalid = mutator.invalidValue(parameter);
            if (invalid == null) {
                continue;
            }
            Draft draft = happy.copy();
            Map<String, String> target = paramMap(draft, parameter.in());
            if (target == null) {
                continue;
            }
            target.put(parameter.name(), invalid);
            cases.add(negativeCase(operation, suffixPrefix + parameter.name(), draft));
            return;
        }
    }

    private GeneratedTestCase happyCase(OperationPage operation, Draft draft) {
        ResponseSchema response = operation.response();
        String responseType = response == null ? "NONE" : StringUtils.defaultIfBlank(response.responseType(), "NONE");
        String assertions = NA;
        if (!"JSON".equalsIgnoreCase(responseType)) {
            assertions = NA;
        }
        int status = successStatus(operation);
        return toCase(operation, opLabel(operation) + " — " + status, true, draft, status,
                responseType, assertions);
    }

    private GeneratedTestCase negativeCase(OperationPage operation, String suffix, Draft draft) {
        int status = clientErrorStatus(operation);
        return toCase(operation, opLabel(operation) + " — " + suffix + " — " + status, false, draft, status,
                "NONE", NA);
    }

    private GeneratedTestCase toCase(OperationPage operation, String testName, boolean positive, Draft draft,
                                     int statusCode, String responseType, String jsonPathAssertions) {
        OperationDefaults defaults = operation.defaults();
        return new GeneratedTestCase(
                operation.key(),
                testName,
                positive,
                defaults == null ? StringUtils.defaultIfBlank(operation.tag(), "generated")
                        : StringUtils.defaultIfBlank(defaults.testGroup(), operation.tag()),
                copyStrings(draft.path),
                copyStrings(draft.query),
                copyStrings(draft.headers),
                copyStrings(draft.cookies),
                operation.requestBody() == null ? null : JsonSchemaMaps.copy(draft.body),
                contentType(operation),
                statusCode,
                responseType,
                NA,
                jsonPathAssertions,
                defaults == null ? "Y" : StringUtils.defaultIfBlank(defaults.enabled(), "Y"),
                defaults == null ? 3000L : defaults.maxResponseTimeMs()
        );
    }

    private static String contentType(OperationPage operation) {
        if (operation.requestBody() != null) {
            return StringUtils.defaultIfBlank(operation.requestBody().mappedContentType(),
                    StringUtils.defaultIfBlank(operation.requestBody().contentType(), NA));
        }
        if (operation.defaults() != null) {
            return StringUtils.defaultIfBlank(operation.defaults().contentType(), NA);
        }
        return NA;
    }

    private static String opLabel(OperationPage operation) {
        return StringUtils.defaultIfBlank(operation.summary(), operation.key());
    }

    private static int successStatus(OperationPage operation) {
        for (Integer code : statusCodes(operation)) {
            if (code != null && code >= 200 && code < 300) {
                return code;
            }
        }
        if (operation.response() != null) {
            int code = operation.response().statusCode();
            if (code >= 200 && code < 300) {
                return code;
            }
        }
        return 200;
    }

    private static int clientErrorStatus(OperationPage operation) {
        List<Integer> codes = statusCodes(operation);
        for (int preferred : List.of(400, 422, 415, 409)) {
            if (codes.contains(preferred)) {
                return preferred;
            }
        }
        for (Integer code : codes) {
            if (code != null && code >= 400 && code < 500 && code != 401 && code != 403 && code != 404) {
                return code;
            }
        }
        return 400;
    }

    private static List<Integer> statusCodes(OperationPage operation) {
        return operation.statusCodes() == null ? List.of() : operation.statusCodes();
    }

    private static ParameterField firstParameter(OperationPage operation, String in) {
        if (operation.parameters() == null) {
            return null;
        }
        for (ParameterField parameter : operation.parameters()) {
            if (parameter != null && in.equalsIgnoreCase(parameter.in()) && StringUtils.isNotBlank(parameter.name())) {
                return parameter;
            }
        }
        return null;
    }

    private static Map<String, String> paramMap(Draft draft, String in) {
        if ("path".equalsIgnoreCase(in)) {
            return draft.path;
        }
        if ("query".equalsIgnoreCase(in)) {
            return draft.query;
        }
        if ("header".equalsIgnoreCase(in)) {
            return draft.headers;
        }
        if ("cookie".equalsIgnoreCase(in)) {
            return draft.cookies;
        }
        return null;
    }

    private static String invalidTypeValue(Map<String, Object> schema) {
        if (schema == null) {
            return null;
        }
        if (JsonSchemaMaps.isNumber(schema)) {
            return "not-a-number";
        }
        if (JsonSchemaMaps.isBoolean(schema)) {
            return "not-a-boolean";
        }
        return null;
    }

    private static Map<String, String> copyStrings(Map<String, String> source) {
        return new LinkedHashMap<>(source);
    }

    @FunctionalInterface
    private interface BodyPropertyMutator {
        boolean mutate(String name, Map<String, Object> schema, Map<String, Object> object);
    }

    @FunctionalInterface
    private interface ParameterMutator {
        String invalidValue(ParameterField parameter);
    }

    private static final class Draft {
        private final Map<String, String> path;
        private final Map<String, String> query;
        private final Map<String, String> headers;
        private final Map<String, String> cookies;
        private Object body;

        private Draft(Map<String, String> path, Map<String, String> query, Map<String, String> headers,
                      Map<String, String> cookies, Object body) {
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.cookies = cookies;
            this.body = body;
        }

        static Draft from(OperationPage operation) {
            Map<String, String> path = new LinkedHashMap<>();
            Map<String, String> query = new LinkedHashMap<>();
            Map<String, String> headers = new LinkedHashMap<>();
            Map<String, String> cookies = new LinkedHashMap<>();
            if (operation.parameters() != null) {
                for (ParameterField parameter : operation.parameters()) {
                    if (parameter == null || StringUtils.isBlank(parameter.name())) {
                        continue;
                    }
                    String sample = parameter.sample() == null ? "" : parameter.sample();
                    if ("path".equalsIgnoreCase(parameter.in())) {
                        path.put(parameter.name(), sample);
                    } else if ("query".equalsIgnoreCase(parameter.in())) {
                        query.put(parameter.name(), sample);
                    } else if ("header".equalsIgnoreCase(parameter.in())) {
                        headers.put(parameter.name(), sample);
                    } else if ("cookie".equalsIgnoreCase(parameter.in())) {
                        cookies.put(parameter.name(), sample);
                    }
                }
            }
            Object body = null;
            if (operation.requestBody() != null) {
                body = JsonSchemaMaps.copy(operation.requestBody().sample());
                if (body == null && JsonSchemaMaps.isObject(operation.requestBody().jsonSchema())) {
                    body = new LinkedHashMap<String, Object>();
                }
            }
            return new Draft(path, query, headers, cookies, body);
        }

        Draft copy() {
            return new Draft(new LinkedHashMap<>(path), new LinkedHashMap<>(query), new LinkedHashMap<>(headers),
                    new LinkedHashMap<>(cookies), JsonSchemaMaps.copy(body));
        }
    }

    private static final class BodyTarget {
        private final Map<String, Object> object;
        private final Map<String, Object> schema;

        private BodyTarget(Map<String, Object> object, Map<String, Object> schema) {
            this.object = object;
            this.schema = schema;
        }

        static BodyTarget bind(OperationPage operation, Draft draft) {
            if (operation.requestBody() == null) {
                return null;
            }
            if (draft.body == null && JsonSchemaMaps.isObject(operation.requestBody().jsonSchema())) {
                draft.body = new LinkedHashMap<>();
            }
            return from(operation, draft.body);
        }

        static BodyTarget from(OperationPage operation, Object sample) {
            RequestBodySchema body = operation.requestBody();
            if (body == null) {
                return null;
            }
            Map<String, Object> schema = body.jsonSchema();
            if (sample instanceof List<?> list) {
                if (list.isEmpty()) {
                    return null;
                }
                Map<String, Object> first = JsonSchemaMaps.asObject(list.get(0));
                return first == null ? null : new BodyTarget(first, JsonSchemaMaps.items(schema));
            }
            Map<String, Object> object = JsonSchemaMaps.asObject(sample);
            return object == null ? null : new BodyTarget(object, schema);
        }
    }
}
