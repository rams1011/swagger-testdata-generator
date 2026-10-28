package com.tx.api.test.generator.studio.service;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
