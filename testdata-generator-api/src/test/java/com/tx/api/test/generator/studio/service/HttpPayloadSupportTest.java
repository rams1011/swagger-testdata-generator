package com.tx.api.test.generator.studio.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpPayloadSupportTest {

    @Test
    void blankQueryValuesAreLeftOffTheUrl() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("$.xgafv", "");
        query.put("access_token", "   ");
        query.put("pageSize", "10");
        query.put("filter", null);

        assertEquals(
                "https://logging.googleapis.com/v2/locations?pageSize=10",
                HttpPayloadSupport.appendQuery("https://logging.googleapis.com/v2/locations", query));
    }

    @Test
    void aFilledValueKeepsAnExistingQuery() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("alt", "");
        query.put("filter", "severity=ERROR");

        assertEquals(
                "https://logging.googleapis.com/v2/locations?existing=1&filter=severity%3DERROR",
                HttpPayloadSupport.appendQuery("https://logging.googleapis.com/v2/locations?existing=1", query));
    }
}
