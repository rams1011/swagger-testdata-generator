package com.tx.api.test.generator.studio.storage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonMapsTest {

    @Test
    @SuppressWarnings("unchecked")
    void aStoredBodyKeepsWholeNumbersAndNulls() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("loyaltyPoints", 1250);
        body.put("weightKg", 63.2);
        body.put("middleName", null);
        body.put("tags", List.of(1, 2));

        Map<String, Object> read = (Map<String, Object>) JsonMaps.toObject(JsonMaps.toJson(body));

        assertEquals(1250L, read.get("loyaltyPoints"));
        assertEquals(63.2, read.get("weightKg"));
        assertTrue(read.containsKey("middleName") && read.get("middleName") == null);
        assertEquals(List.of(1L, 2L), read.get("tags"));
        assertEquals("{\"loyaltyPoints\":1250,\"weightKg\":63.2,\"middleName\":null,\"tags\":[1,2]}",
                JsonMaps.toJson(read));
    }
}
