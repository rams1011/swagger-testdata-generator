package com.tx.api.test.generator.studio.storage;

import com.tx.api.test.generator.studio.cache.StudioCacheDiagnostics;
import com.tx.api.test.generator.studio.cache.StudioRedisCache;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCachingStudioStorageTest {

    @Test
    void savingAnEnvironmentReplacesTheCachedDocument() {
        Map<String, String> redis = new HashMap<>();
        StudioStorage delegate = mock(StudioStorage.class);
        RedisCachingStudioStorage storage = new RedisCachingStudioStorage(delegate, cache(redis));

        StoredEnvironment before = environment("{\"variables\":[]}");
        StoredEnvironment after = environment(
                "{\"variables\":[{\"key\":\"roleId\",\"value\":\"48\",\"secret\":false}]}");
        when(delegate.findEnvironment("env-1")).thenReturn(Optional.of(before), Optional.of(after));

        assertEquals("{\"variables\":[]}", storage.findEnvironment("env-1").orElseThrow().getDocumentJson());
        assertEquals("{\"variables\":[]}", storage.findEnvironment("env-1").orElseThrow().getDocumentJson());
        verify(delegate, times(1)).findEnvironment("env-1");

        storage.saveEnvironment(after);

        assertEquals(after.getDocumentJson(), storage.findEnvironment("env-1").orElseThrow().getDocumentJson());
        // Saving reads the stored copy once (to see a rename), then the cache is read through again.
        verify(delegate, times(3)).findEnvironment("env-1");
    }

    @Test
    void renamingAnEnvironmentRefreshesTheCachedCasesThatNameIt() {
        Map<String, String> redis = new HashMap<>();
        StudioStorage delegate = mock(StudioStorage.class);
        RedisCachingStudioStorage storage = new RedisCachingStudioStorage(delegate, cache(redis));
        StudioTestCase before = scopedCase("Default");
        StudioTestCase after = scopedCase("Staging");
        when(delegate.listTestCases("spec-1", "plan-1", null)).thenReturn(List.of(before), List.of(after));
        when(delegate.findEnvironment("env-1")).thenReturn(Optional.of(environment("{}")));

        assertEquals(List.of("Default"), storage.listTestCases("spec-1", "plan-1", null).get(0).getEnvironments());
        StoredEnvironment unchanged = environment("{\"variables\":[]}");
        storage.saveEnvironment(unchanged);
        assertEquals(List.of("Default"), storage.listTestCases("spec-1", "plan-1", null).get(0).getEnvironments(),
                "saving without a rename leaves the case cache alone");
        verify(delegate, times(1)).listTestCases("spec-1", "plan-1", null);

        StoredEnvironment renamed = environment("{}");
        renamed.setName("Staging");
        storage.saveEnvironment(renamed);
        assertEquals(List.of("Staging"), storage.listTestCases("spec-1", "plan-1", null).get(0).getEnvironments(),
                "the cases follow the rename instead of dropping out of the environment");
        verify(delegate, times(2)).listTestCases("spec-1", "plan-1", null);
    }

    private static StudioTestCase scopedCase(String environmentName) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setId("case-1");
        testCase.setSpecId("spec-1");
        testCase.setPlanId("plan-1");
        testCase.setTestName("Scoped");
        testCase.setEnvironments(List.of(environmentName));
        return testCase;
    }

    private static StoredEnvironment environment(String document) {
        StoredEnvironment environment = new StoredEnvironment();
        environment.setId("env-1");
        environment.setSpecId("spec-1");
        environment.setName("Default");
        environment.setDocumentJson(document);
        return environment;
    }

    @SuppressWarnings("unchecked")
    private static StudioRedisCache cache(Map<String, String> redis) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(invocation -> redis.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            redis.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.increment(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long next = Long.parseLong(redis.getOrDefault(key, "0")) + 1;
            redis.put(key, Long.toString(next));
            return next;
        });
        return new StudioRedisCache(template, new StudioProperties(), new StudioCacheDiagnostics());
    }
}
