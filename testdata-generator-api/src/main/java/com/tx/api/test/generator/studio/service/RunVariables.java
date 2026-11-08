package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.collection.StudioVariable;
import com.tx.api.test.generator.studio.collection.StudioVariables;
import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import org.apache.commons.lang3.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Variables a run starts with, layered lowest to highest: environment values, global variables, collection
 * variables, environment variables. (Data variables, one row per iteration of a data-driven run, will sit above
 * environment variables.) Secret variables take their value from the caller for this run only; results never
 * echo it. Each layer is also reachable directly as {@code global:name}, {@code collection:name} or
 * {@code env:name} (environment values such as baseUrl count as {@code env:}), so a case can read a lower layer
 * that a higher one overrides.
 */
public final class RunVariables {

    static final String MASK = "******";

    private final Map<String, String> values;
    private final List<String> secretValues;

    private RunVariables(Map<String, String> values, List<String> secretValues) {
        this.values = values;
        this.secretValues = secretValues;
    }

    public static RunVariables empty() {
        return new RunVariables(Map.of(), List.of());
    }

    public static RunVariables of(Map<String, String> environmentValues, List<StudioVariable> globals,
                                  List<StudioVariable> environmentVariables, Map<String, String> secrets) {
        return of(environmentValues, globals, null, environmentVariables, secrets);
    }

    public static RunVariables of(Map<String, String> environmentValues, List<StudioVariable> globals,
                                  List<StudioVariable> collectionVariables,
                                  List<StudioVariable> environmentVariables, Map<String, String> secrets) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> environmentScoped = new LinkedHashMap<>();
        if (environmentValues != null) {
            environmentValues.forEach((key, value) -> {
                if (StringUtils.isNotBlank(key) && value != null) {
                    values.put(key, value);
                    environmentScoped.put(key, value);
                }
            });
        }
        Set<String> secretValues = new LinkedHashSet<>();
        Map<String, String> globalScoped = new LinkedHashMap<>();
        Map<String, String> collectionScoped = new LinkedHashMap<>();
        layer(values, globalScoped, secretValues, globals, secrets);
        layer(values, collectionScoped, secretValues, collectionVariables, secrets);
        layer(values, environmentScoped, secretValues, environmentVariables, secrets);
        globalScoped.forEach((key, value) -> values.put(CaseVariables.GLOBAL_PREFIX + key, value));
        collectionScoped.forEach((key, value) -> values.put(CaseVariables.COLLECTION_PREFIX + key, value));
        environmentScoped.forEach((key, value) -> values.put(CaseVariables.ENV_PREFIX + key, value));
        List<String> masked = new ArrayList<>();
        for (String secret : secretValues) {
            masked.add(secret);
            String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
            if (!encoded.equals(secret)) {
                masked.add(encoded);
            }
        }
        masked.sort(Comparator.comparingInt(String::length).reversed());
        return new RunVariables(Map.copyOf(values), List.copyOf(masked));
    }

    private static void layer(Map<String, String> values, Map<String, String> scoped, Set<String> secretValues,
                              List<StudioVariable> variables, Map<String, String> secrets) {
        Map<String, String> stored = StudioVariables.storedValues(variables);
        values.putAll(stored);
        scoped.putAll(stored);
        for (String key : StudioVariables.secretKeys(variables)) {
            String secret = secrets == null ? null : secrets.get(key);
            if (StringUtils.isEmpty(secret)) {
                values.remove(key);
                scoped.remove(key);
                continue;
            }
            values.put(key, secret);
            scoped.put(key, secret);
            secretValues.add(secret);
        }
    }

    /** A fresh, mutable copy so extracts written by one run never leak into the next. */
    public Map<String, String> newScope() {
        return new LinkedHashMap<>(values);
    }

    public Set<String> names() {
        return values.keySet();
    }

    public RunTestResult mask(RunTestResult result) {
        if (result == null || secretValues.isEmpty()) {
            return result;
        }
        List<AssertionCheck> assertions = null;
        if (result.assertions() != null) {
            assertions = new ArrayList<>(result.assertions().size());
            for (AssertionCheck check : result.assertions()) {
                assertions.add(check == null ? null : new AssertionCheck(mask(check.name()), mask(check.path()),
                        mask(check.expected()), mask(check.actual()), check.passed()));
            }
        }
        return new RunTestResult(result.passed(), result.skipped(), mask(result.skipReason()), result.caseId(),
                result.testName(), result.method(), mask(result.url()), result.expectedStatus(),
                result.actualStatus(), result.statusMatched(), result.elapsedMs(), result.maxResponseTimeMs(),
                result.timedOut(), mask(result.responseBody()), mask(result.responseHeaders()),
                mask(result.requestHeaders()), assertions, mask(result.error()), result.downloadPath(),
                mask(result.extracted()), mask(result.requestBody()), result.rowLabel(),
                result.iterations() == null ? null : result.iterations().stream().map(this::mask).toList());
    }

    public List<RunTestResult> mask(List<RunTestResult> results) {
        if (results == null || secretValues.isEmpty()) {
            return results;
        }
        return results.stream().map(this::mask).toList();
    }

    String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (String secret : secretValues) {
            out = out.replace(secret, MASK);
        }
        return out;
    }

    private Map<String, String> mask(Map<String, String> map) {
        if (map == null) {
            return null;
        }
        Map<String, String> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(key, mask(value)));
        return copy;
    }
}
