package com.tx.api.test.generator.studio.service;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPathEvaluatorTest {

    @Test
    void parseAssertionsKeepsLeadingAndTrailingSpaces() {
        Map<String, String> assertions = JsonPathEvaluator.parseAssertions("$.name= hello |$.city= Dallas ");

        assertEquals(" hello ", assertions.get("$.name"));
        assertEquals(" Dallas ", assertions.get("$.city"));
    }

    @Test
    void matchesPaddedStringFromJson() {
        var actual = JsonParser.parseString("{\"name\":\" hello \"}").getAsJsonObject().get("name");

        assertTrue(JsonPathEvaluator.matches(" hello ", actual));
        assertEquals(" hello ", JsonPathEvaluator.formatValue(actual));
    }

    @Test
    void bareNameReadsTheSameFieldAsADollarPath() {
        var root = JsonParser.parseString("{\"id\":1}");

        assertEquals("1", JsonPathEvaluator.formatValue(JsonPathEvaluator.read(root, "id")));
        assertTrue(JsonPathEvaluator.matches("1.0", JsonPathEvaluator.read(root, "$.id")));
    }

    @Test
    void existsNonEmptyPatternAndRange() {
        var root = JsonParser.parseString("{\"id\":1,\"name\":\"Ada\",\"code\":\"a|b\",\"items\":[],\"note\":\"\"}");

        assertTrue(JsonPathEvaluator.matches("op:exists", JsonPathEvaluator.read(root, "$.note")));
        assertFalse(JsonPathEvaluator.matches("op:exists", JsonNull.INSTANCE));
        assertFalse(JsonPathEvaluator.matches("op:nonempty", JsonPathEvaluator.read(root, "$.items")));
        assertFalse(JsonPathEvaluator.matches("op:nonempty", JsonPathEvaluator.read(root, "$.note")));
        assertTrue(JsonPathEvaluator.matches("op:nonempty", JsonPathEvaluator.read(root, "$.id")));
        assertTrue(JsonPathEvaluator.matches("op:matches:A.*", JsonPathEvaluator.read(root, "$.name")));
        assertFalse(JsonPathEvaluator.matches("op:matches:[", JsonPathEvaluator.read(root, "$.name")));
        assertTrue(JsonPathEvaluator.matches("op:matches:a\\|b", JsonPathEvaluator.read(root, "$.code")));
        assertTrue(JsonPathEvaluator.matches("op:range:1..10", JsonPathEvaluator.read(root, "$.id")));
        assertFalse(JsonPathEvaluator.matches("op:range:2..", JsonPathEvaluator.read(root, "$.id")));
        assertTrue(JsonPathEvaluator.matches("op:range:..1", JsonPathEvaluator.read(root, "$.id")));
        assertEquals("exists", JsonPathEvaluator.describe("op:exists"));
        Map<String, String> parsed = JsonPathEvaluator.parseAssertions("$.code=op:matches:a\\\\|b|$.id=1");
        assertEquals("op:matches:a\\|b", parsed.get("$.code"));
        assertEquals("1", parsed.get("$.id"));
    }
}
