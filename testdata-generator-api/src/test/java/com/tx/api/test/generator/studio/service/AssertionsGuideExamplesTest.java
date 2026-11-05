package com.tx.api.test.generator.studio.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Keeps the examples in the UI repo's docs/ASSERTIONS.md true. */
class AssertionsGuideExamplesTest {

    static final String RESPONSE = """
            { "total": 3, "page": 1, "items": [
              { "roleId": 48, "code": "TestCode", "name": "Test", "active": true,
                "permissions": ["READ", "WRITE"], "createdAt": "2026-11-04T10:00:00Z" },
              { "roleId": 12, "code": "Admin", "name": "Admin", "active": true,
                "permissions": ["READ", "WRITE", "DELETE"] },
              { "roleId": 7, "code": "Guest", "name": "Guest", "active": false, "permissions": [] } ] }
            """;

    private final JsonElement root = JsonParser.parseString(RESPONSE);

    @Test
    void pathSyntaxTable() {
        List<String> wrong = new ArrayList<>();
        read(wrong, "$.total", "3");
        read(wrong, "total", "3");
        read(wrong, "$.items[0].name", "Test");
        read(wrong, "$.items[-1].code", "Guest");
        read(wrong, "$.items[*].code", "[\"TestCode\",\"Admin\",\"Guest\"]");
        read(wrong, "$..roleId", "[48,12,7]");
        read(wrong, "$.items[0:2].code", "[\"TestCode\",\"Admin\"]");
        read(wrong, "$.items.length()", "3");
        read(wrong, "$.items[?(@.code=='TestCode')].name", "[\"Test\"]");
        read(wrong, "$.items[?(@.roleId > 10 && @.active == true)].code", "[\"TestCode\",\"Admin\"]");
        read(wrong, "$.items[?(@.code in ['Admin','Guest'])].roleId", "[12,7]");
        read(wrong, "$.items[?(@.name =~ /te.*/i)].code", "[\"TestCode\"]");
        read(wrong, "$.items[?(@.permissions contains 'DELETE')].code", "[\"Admin\"]");
        read(wrong, "$.items[?(@.permissions empty true)].code", "[\"Guest\"]");
        read(wrong, "$.items[?(@.createdAt)].code", "[\"TestCode\"]");
        read(wrong, "$['items'][0]['code']", "TestCode");
        assertEquals(List.of(), wrong);
    }

    @Test
    void checkExamplesPass() {
        List<String> wrong = new ArrayList<>();
        pass(wrong, "$.items[*].code", "[\"TestCode\",\"Admin\",\"Guest\"]");
        pass(wrong, "$.items[0].name", "Test");
        pass(wrong, "$.items[0].roleId", "48");
        pass(wrong, "$.items[0].code", "op:ne:Admin");
        pass(wrong, "$.items[*].code", "op:contains:TestCode");
        pass(wrong, "$.items[*].code", "op:notContains:Deleted");
        pass(wrong, "$.items", "op:containsObject:{\"code\":\"TestCode\",\"name\":\"Test\"}");
        pass(wrong, "$.items", "op:containsObject:{\"permissions\":[\"DELETE\"]}");
        pass(wrong, "$.items", "op:notContainsObject:{\"code\":\"Deleted\"}");
        pass(wrong, "$.total", "op:gt:0");
        pass(wrong, "$.page", "op:gte:1");
        pass(wrong, "$.items[*].roleId", "op:lt:1000");
        pass(wrong, "$.items.length()", "op:lte:50");
        pass(wrong, "$.total", "op:range:1..100");
        pass(wrong, "$.items[0].createdAt", "op:matches:\\d{4}-\\d{2}-\\d{2}T.*Z");
        pass(wrong, "$.items[0].roleId", "op:type:integer");
        pass(wrong, "$.items", "op:length:3");
        pass(wrong, "$.items[0].code", "op:length:1..20");
        pass(wrong, "$.items[0].createdAt", "op:exists");
        pass(wrong, "$.items[?(@.code=='Deleted')]", "op:notExists");
        pass(wrong, "$.items", "op:nonempty");
        pass(wrong, "$.items[2].permissions", "op:empty");
        pass(wrong, "$.items[?(@.code=='TestCode')].name", "Test");
        pass(wrong, "$.items[?(@.code=='TestCode')].active", "true");
        pass(wrong, "$.items[?(@.code=='TestCode')].permissions[*]", "op:contains:WRITE");
        pass(wrong, "$.items[?(@.roleId==48)].name", "Test");
        pass(wrong, "$.items", "op:containsObject:{\"roleId\":48,\"code\":\"TestCode\"}");
        pass(wrong, "$.items", "op:containsObject:{\"code\":\"TestCode\",\"name\":\"Test\",\"active\":true}");
        pass(wrong, "$.items", "op:notContainsObject:{\"code\":\"DeletedCode\"}");
        pass(wrong, "$.items[?(@.code=='TestCode')]", "op:length:1");
        pass(wrong, "$.items[?(@.code=='TestCode')]", "op:nonempty");
        pass(wrong, "$.items[*].code", "op:notContains:DeletedCode");
        pass(wrong, "$.items[*].code", "op:matches:[A-Za-z]+");
        pass(wrong, "$.items[*].roleId", "op:gt:0");
        pass(wrong, "$.items[*].roleId", "op:matches:\\d+");
        pass(wrong, "$.items", "op:length:1..50");
        pass(wrong, "$.items[?(@.active == false)].permissions[*]", "op:notExists");
        pass(wrong, "$.page", "1");
        pass(wrong, "$.items", "op:length:1..20");
        pass(wrong, "$.items[0].name", "op:matches:.*es.*");
        assertEquals(List.of(), wrong);
    }

    @Test
    void checkExamplesFail() {
        List<String> wrong = new ArrayList<>();
        fail(wrong, "$.items[?(@.code=='Nope')].name", "Test");
        fail(wrong, "$.items", "op:containsObject:{\"roleId\":\"48\"}");
        fail(wrong, "$.items[0].name", "op:matches:es");
        fail(wrong, "$.missing", "op:ne:x");
        fail(wrong, "$.items[?(@.code=='TestCode')].permissions", "op:contains:WRITE");
        fail(wrong, "$.items[*].roleId", "op:type:integer");
        assertEquals(List.of(), wrong);
    }

    @Test
    void bodyExamples() {
        JsonElement strict = JsonParser.parseString(RESPONSE);
        assertEquals(null, JsonBodyMatcher.difference(strict, root, JsonBodyMatcher.Mode.STRICT));
        JsonElement reordered = JsonParser.parseString("""
                { "total": 3, "page": 1, "items": [
                  { "roleId": 12, "code": "Admin", "name": "Admin", "active": true,
                    "permissions": ["DELETE", "READ", "WRITE"] },
                  { "roleId": 48, "code": "TestCode", "name": "Test", "active": true,
                    "permissions": ["WRITE", "READ"], "createdAt": "2026-11-04T10:00:00Z" },
                  { "roleId": 7, "code": "Guest", "name": "Guest", "active": false, "permissions": [] } ] }
                """);
        assertEquals("$.items[0].roleId: expected 48 but was 12",
                JsonBodyMatcher.difference(strict, reordered, JsonBodyMatcher.Mode.STRICT));
        assertEquals(null, JsonBodyMatcher.difference(strict, reordered, JsonBodyMatcher.Mode.IGNORE_ORDER));
        assertEquals(null, lenient("{ \"items\": [ { \"code\": \"Guest\", \"active\": false }, "
                + "{ \"code\": \"TestCode\", \"name\": \"Test\" } ] }"));
        assertEquals("$.items: no item matches expected[0] {\"code\":\"Nope\"}", lenient("{\"items\":[{\"code\":\"Nope\"}]}"));
        assertEquals("$.total: expected 4 but was 3", lenient("{\"total\":4}"));
        assertEquals("$.owner: missing", lenient("{\"owner\":\"x\"}"));
        assertEquals("$.items: expected at least 4 items but was 3", lenient("{\"items\":[{},{},{},{}]}"));
        assertEquals(null, lenient("{\"items\":[{\"permissions\":[\"DELETE\"]}]}"));
        assertEquals("$.roleId: expected \"48\" but was 48", JsonBodyMatcher.difference(
                JsonParser.parseString("{\"roleId\":\"48\"}"), JsonParser.parseString("{\"roleId\":48}"),
                JsonBodyMatcher.Mode.STRICT));
    }

    @Test
    void ignoreFieldsExamples() {
        JsonElement expected = JsonParser.parseString("""
                { "page": 1, "items": [ { "code": "TestCode", "name": "Test", "active": true, "permissions": ["READ", "WRITE"],
                  "createdAt": "2026-11-04T10:00:00Z" }, { "code": "Admin", "name": "Admin", "active": true,
                  "permissions": ["READ", "WRITE", "DELETE"] }, { "code": "Guest", "name": "Guest", "active": false,
                  "permissions": [] } ] }
                """);
        List<String> ignored = List.of("$.total", "$.items[*].roleId");
        assertEquals(null, JsonBodyMatcher.difference(JsonPathEvaluator.withoutPaths(expected, ignored),
                JsonPathEvaluator.withoutPaths(root, ignored), JsonBodyMatcher.Mode.STRICT));
        JsonElement withoutGuest = JsonPathEvaluator.withoutPaths(root, List.of("$.items[?(@.code=='Guest')]"));
        assertEquals(2, withoutGuest.getAsJsonObject().getAsJsonArray("items").size());
        JsonElement noDates = JsonPathEvaluator.withoutPaths(root, List.of("$..createdAt", "$.createdAt"));
        assertEquals(false, noDates.toString().contains("createdAt"));
    }

    @Test
    void xpathExamples() {
        String xml = XPathEvaluatorTest.ROLES;
        List<String> wrong = new ArrayList<>();
        String[][] rows = {
                {"xpath://role[code='TestCode']/name", "Test"},
                {"xpath://role[code='TestCode']/@id", "48"},
                {"xpath:/roles/@total", "op:gte:3"},
                {"xpath:count(//role)", "3"},
                {"xpath:count(//role[active='true'])", "op:range:1..2"},
                {"xpath://role/code", "op:contains:Admin"},
                {"xpath://role/code", "op:length:3"},
                {"xpath://role/@id", "op:gt:0"},
                {"xpath://role[code='Deleted']", "op:notExists"},
                {"xpath:count(//*[local-name()='role'])", "3"},
                {"xpath://role[@id='48']/name", "Test"}};
        for (String[] row : rows) {
            if (!XPathEvaluator.check(xml, row[0], row[1]).passed()) {
                wrong.add(row[0] + " " + row[1] + " actual=" + XPathEvaluator.check(xml, row[0], row[1]).actual());
            }
        }
        assertEquals(List.of(), wrong);
    }

    private String lenient(String expected) {
        return JsonBodyMatcher.difference(JsonParser.parseString(expected), root, JsonBodyMatcher.Mode.LENIENT);
    }

    private void read(List<String> wrong, String path, String expected) {
        JsonElement value = JsonPathEvaluator.read(root, path);
        String actual = value == null ? "(missing)" : value.isJsonPrimitive()
                ? JsonPathEvaluator.formatValue(value) : value.toString();
        if (!expected.equals(actual)) {
            wrong.add(path + " read " + actual);
        }
    }

    private void pass(List<String> wrong, String path, String check) {
        JsonPathEvaluator.Check result = JsonPathEvaluator.check(root, path, check);
        if (!result.passed()) {
            wrong.add("should pass: " + path + " " + check + " actual=" + result.actual());
        }
    }

    private void fail(List<String> wrong, String path, String check) {
        if (JsonPathEvaluator.check(root, path, check).passed()) {
            wrong.add("should fail: " + path + " " + check);
        }
    }
}
