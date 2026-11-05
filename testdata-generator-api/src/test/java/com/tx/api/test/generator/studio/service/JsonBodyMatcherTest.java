package com.tx.api.test.generator.studio.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonBodyMatcherTest {

    private static final JsonBodyMatcher.Mode STRICT = JsonBodyMatcher.Mode.STRICT;
    private static final JsonBodyMatcher.Mode IGNORE_ORDER = JsonBodyMatcher.Mode.IGNORE_ORDER;
    private static final JsonBodyMatcher.Mode LENIENT = JsonBodyMatcher.Mode.LENIENT;

    @Test
    void strictNamesTheFirstDifferenceIncludingNumberVersusString() {
        assertEquals("$.roleId: expected \"48\" but was 48",
                diff("{\"roleId\":\"48\",\"name\":\"Test\"}", "{\"roleId\":48,\"name\":\"Test\"}", STRICT));
        assertNull(diff("{\"a\":48,\"b\":[1,2]}", "{\"b\":[1,2],\"a\":48.0}", STRICT));
        assertEquals("$.b[0]: expected 1 but was 2", diff("{\"b\":[1,2]}", "{\"b\":[2,1]}", STRICT));
        assertEquals("$.extra: unexpected field (use lenient to allow extra fields)",
                diff("{\"a\":1}", "{\"a\":1,\"extra\":true}", STRICT));
        assertEquals("$.items: expected 2 items but was 3", diff("{\"items\":[1,2]}", "{\"items\":[1,2,3]}", STRICT));
    }

    @Test
    void ignoreOrderMatchesListsInAnyOrderButStillNeedsTheSameItems() {
        String expected = "{\"items\":[{\"code\":\"A\",\"n\":1},{\"code\":\"B\",\"n\":2}]}";
        assertNull(diff(expected, "{\"items\":[{\"n\":2,\"code\":\"B\"},{\"code\":\"A\",\"n\":1}]}", IGNORE_ORDER));
        assertEquals("$.items: no item matches expected[1] {\"code\":\"B\",\"n\":2}",
                diff(expected, "{\"items\":[{\"code\":\"A\",\"n\":1},{\"code\":\"B\",\"n\":3}]}", IGNORE_ORDER));
        assertEquals("$.items: no item matches expected[1] 1", diff("{\"items\":[1,1]}", "{\"items\":[1,2]}",
                IGNORE_ORDER));
        assertTrue(diff("{\"a\":1}", "{\"a\":1,\"b\":2}", IGNORE_ORDER).contains("unexpected field"));
    }

    @Test
    void lenientTreatsTheExpectedBodyAsASubset() {
        String actual = "{\"total\":3,\"items\":[{\"code\":\"A\",\"id\":7},{\"code\":\"B\",\"id\":8,\"tags\":[\"x\",\"y\"]},"
                + "{\"code\":\"C\",\"id\":9}]}";
        assertNull(diff("{\"items\":[{\"code\":\"B\",\"tags\":[\"y\"]},{\"code\":\"A\"}]}", actual, LENIENT));
        assertEquals("$.items: no item matches expected[0] {\"code\":\"Z\"}",
                diff("{\"items\":[{\"code\":\"Z\"}]}", actual, LENIENT));
        assertEquals("$.missing: missing", diff("{\"missing\":1}", actual, LENIENT));
        assertEquals("$.items: expected at least 4 items but was 3",
                diff("{\"items\":[{},{},{},{}]}", actual, LENIENT));
        assertNull(diff("[{\"id\":9}]", "[{\"id\":7},{\"id\":9}]", LENIENT));
    }

    @Test
    void modeNamesAndStoredForm() {
        assertEquals(IGNORE_ORDER, JsonBodyMatcher.mode("ignore_order"));
        assertEquals(IGNORE_ORDER, JsonBodyMatcher.mode("Ignore-Order"));
        assertEquals(LENIENT, JsonBodyMatcher.mode("LENIENT"));
        assertEquals(STRICT, JsonBodyMatcher.mode("whatever"));
        assertNull(JsonBodyMatcher.normalize("strict"));
        assertNull(JsonBodyMatcher.normalize(" "));
        assertEquals("ignoreOrder", JsonBodyMatcher.normalize("IGNOREORDER"));
    }

    @Test
    void containsObjectFindsAMatchingListItemAnywhere() {
        JsonElement root = JsonParser.parseString(
                "{\"items\":[{\"roleId\":47,\"code\":\"X\"},{\"roleId\":48,\"code\":\"TestCode\",\"name\":\"Test\"}]}");
        assertTrue(JsonPathEvaluator.check(root, "$.items",
                "op:containsObject:{\"code\":\"TestCode\",\"name\":\"Test\"}").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items", "op:containsObject:{\"roleId\":48}").passed());
        assertTrue(!JsonPathEvaluator.check(root, "$.items", "op:containsObject:{\"roleId\":\"48\"}").passed());
        assertTrue(!JsonPathEvaluator.check(root, "$.items", "op:containsObject:{\"code\":\"Nope\"}").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items", "op:notContainsObject:{\"code\":\"Nope\"}").passed());
        assertTrue(!JsonPathEvaluator.check(root, "$.items", "op:notContainsObject:{\"code\":\"X\"}").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items[?(@.code=='TestCode')]",
                "op:containsObject:{\"name\":\"Test\"}").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items[1]", "op:containsObject:{\"name\":\"Test\"}").passed());
        assertTrue(!JsonPathEvaluator.check(root, "$.items", "op:containsObject:not json").passed());
        assertEquals("contains object {\"a\":1}", JsonPathEvaluator.describe("op:containsObject:{\"a\":1}"));
    }

    @Test
    void ignorePathsParseAndRemoveValues() {
        assertEquals(java.util.List.of("$.a", "$..id"), JsonBodyMatcher.ignorePaths(" $.a \r\n\n$..id\n$.a"));
        assertEquals("$.a\n$..id", JsonBodyMatcher.normalizeIgnoreFields("$.a\n$..id"));
        assertNull(JsonBodyMatcher.normalizeIgnoreFields(" NA "));
        JsonElement trimmed = JsonPathEvaluator.withoutPaths(JsonParser.parseString(
                "{\"id\":1,\"meta\":{\"id\":2,\"at\":\"x\"},\"items\":[{\"id\":3,\"n\":1}]}"),
                java.util.List.of("$..id", "$.meta.at", "$.missing", "createdAt", "$"));
        assertEquals(JsonParser.parseString("{\"meta\":{},\"items\":[{\"n\":1}]}"), trimmed);
    }

    private static String diff(String expected, String actual, JsonBodyMatcher.Mode mode) {
        return JsonBodyMatcher.difference(JsonParser.parseString(expected), JsonParser.parseString(actual), mode);
    }
}
