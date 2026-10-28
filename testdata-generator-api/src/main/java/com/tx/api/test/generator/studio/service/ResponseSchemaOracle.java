package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.studio.model.LoadedSpec;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * The loaded operation response schema is the body oracle. A schema saved on the case
 * is only a copy for CI, and it is replaced when the operation is still in the spec.
 */
final class ResponseSchemaOracle {

    private static final Gson JSON = new Gson();

    private ResponseSchemaOracle() {
    }

    record LiveSchema(boolean known, Map<String, Object> schema) {
    }

    static LiveSchema lookup(LoadedSpec spec, String operationKey) {
        if (spec == null || StringUtils.isBlank(operationKey)) {
            return new LiveSchema(false, null);
        }
        OperationPage operation = spec.getOperation(operationKey);
        if (operation == null || operation.response() == null) {
            return new LiveSchema(false, null);
        }
        Map<String, Object> schema = operation.response().jsonSchema();
        if (schema == null || schema.isEmpty()) {
            return new LiveSchema(true, null);
        }
        return new LiveSchema(true, schema);
    }

    static void apply(StudioTestCase testCase, LoadedSpec spec) {
        if (testCase == null) {
            return;
        }
        LiveSchema live = lookup(spec, testCase.getOperationKey());
        if (!live.known()) {
            return;
        }
        testCase.setResponseJsonSchema(live.schema());
    }

    static boolean unchanged(Map<String, Object> stored, LiveSchema live) {
        return live == null || !live.known() || same(stored, live.schema());
    }

    static boolean same(Map<String, Object> left, Map<String, Object> right) {
        if (isEmpty(left) && isEmpty(right)) {
            return true;
        }
        if (isEmpty(left) || isEmpty(right)) {
            return false;
        }
        return JsonParser.parseString(JSON.toJson(left)).equals(JsonParser.parseString(JSON.toJson(right)));
    }

    private static boolean isEmpty(Map<String, Object> schema) {
        return schema == null || schema.isEmpty();
    }
}
