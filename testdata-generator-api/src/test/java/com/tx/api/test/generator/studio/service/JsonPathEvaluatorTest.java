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

    private static final String ORDERS = """
            {"total":"42.50","status":"PAID","tags":["new","vip"],"meta":{},
             "items":[{"id":1,"name":"Pen","price":2.5,"qty":2},{"id":2,"name":"Book","price":12,"qty":1}]}""";

    @Test
    void wildcardDeepScanAndFilterPaths() {
        var root = JsonParser.parseString(ORDERS);

        assertEquals("[1,2]", JsonPathEvaluator.check(root, "$.items[*].id", "[1,2]").actual());
        assertTrue(JsonPathEvaluator.check(root, "$.items[*].id", "[1,2]").passed());
        assertTrue(JsonPathEvaluator.check(root, "$..name", "op:contains:Book").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items[?(@.id==2)].name", "Book").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items[?(@.price > 10)].name", "op:length:1").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items.length()", "2").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items[-1].name", "Book").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items[0:1].id", "1").passed());
    }

    @Test
    void manyMatchValueChecksRequireEveryMatch() {
        var root = JsonParser.parseString(ORDERS);

        assertTrue(JsonPathEvaluator.check(root, "$.items[*].price", "op:gt:0").passed());
        assertFalse(JsonPathEvaluator.check(root, "$.items[*].price", "op:gt:5").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items[*].name", "op:matches:[A-Z][a-z]+").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items[*]", "op:type:array").passed());
    }

    @Test
    void filterNoMatchIsMissingAndNotExistsPasses() {
        var root = JsonParser.parseString(ORDERS);

        JsonPathEvaluator.Check none = JsonPathEvaluator.check(root, "$.items[?(@.id==9)]", "op:exists");
        assertFalse(none.passed());
        assertEquals("(missing)", none.actual());
        assertTrue(JsonPathEvaluator.check(root, "$.items[?(@.id==9)]", "op:notExists").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.discount", "op:notExists").passed());
        assertFalse(JsonPathEvaluator.check(root, "$.status", "op:notExists").passed());
        assertFalse(JsonPathEvaluator.check(root, "$.discount", "op:ne:5").passed());
    }

    @Test
    void comparisonContainsTypeLengthAndEmptyOperators() {
        var root = JsonParser.parseString(ORDERS);

        assertTrue(JsonPathEvaluator.check(root, "$.status", "op:ne:VOID").passed());
        assertFalse(JsonPathEvaluator.check(root, "$.status", "op:ne:PAID").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.total", "op:gte:42.5").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.total", "op:lt:100").passed());
        assertFalse(JsonPathEvaluator.check(root, "$.total", "op:gt:42.5").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items[0].qty", "op:lte:2").passed());
        assertFalse(JsonPathEvaluator.check(root, "$.status", "op:gt:1").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.status", "op:contains:AI").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.tags", "op:contains:vip").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.tags", "op:notContains:old").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items[0]", "op:contains:price").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items[0].id", "op:type:integer").passed());
        assertFalse(JsonPathEvaluator.check(root, "$.items[0].price", "op:type:integer").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.items[0].price", "op:type:number").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.total", "op:type:string").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.meta", "op:type:object").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.tags", "op:length:2").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.status", "op:length:1..4").passed());
        assertFalse(JsonPathEvaluator.check(root, "$.status", "op:length:5..").passed());
        assertTrue(JsonPathEvaluator.check(root, "$.meta", "op:empty").passed());
        assertFalse(JsonPathEvaluator.check(root, "$.tags", "op:empty").passed());
        assertEquals("does not contain old", JsonPathEvaluator.describe("op:notContains:old"));
        assertEquals("length 1..4", JsonPathEvaluator.describe("op:length:1..4"));
        assertEquals(">= 42.5", JsonPathEvaluator.describe("op:gte:42.5"));
    }

    @Test
    void invalidPathFailsWithReason() {
        JsonPathEvaluator.Check check = JsonPathEvaluator.check(JsonParser.parseString(ORDERS), "$.items[?(@.id==", "1");

        assertFalse(check.passed());
        assertTrue(check.actual().startsWith("(invalid JSONPath"));
    }

    @Test
    void filterPathsKeepTheirEqualsAndBarsWhenParsed() {
        Map<String, String> parsed = JsonPathEvaluator.parseAssertions(
                "$.items[?(@.id==2 || @.name=='a=b')].name=Book|$.status=op:matches:PAID\\|VOID|$.total");

        assertEquals("Book", parsed.get("$.items[?(@.id==2 || @.name=='a=b')].name"));
        assertEquals("op:matches:PAID|VOID", parsed.get("$.status"));
        assertEquals("", parsed.get("$.total"));
        assertEquals(3, parsed.size());
    }

    @Test
    void extractUnwrapsASingleFilterMatch() {
        var root = JsonParser.parseString(ORDERS);

        assertEquals("2", JsonPathEvaluator.readText(root, "$.items[?(@.name=='Book')].id"));
        assertEquals("[1,2]", JsonPathEvaluator.readText(root, "$.items[*].id"));
        assertEquals(null, JsonPathEvaluator.readText(root, "$.missing"));
    }

    @Test
    void extractRulesKeepBarsInsideFilters() {
        Map<String, String> rules = JsonPathEvaluator.parseExtracts(
                "bookId=$.items[?(@.name=='Book' || @.id==2)].id|loc=header:Location|code=$.a\\|b");

        assertEquals("$.items[?(@.name=='Book' || @.id==2)].id", rules.get("bookId"));
        assertEquals("header:Location", rules.get("loc"));
        assertEquals("$.a|b", rules.get("code"));
        assertEquals(3, rules.size());
        assertTrue(JsonPathEvaluator.parseExtracts("NA").isEmpty());
        assertEquals("2", JsonPathEvaluator.readText(JsonParser.parseString(ORDERS), rules.get("bookId")));
    }
}
