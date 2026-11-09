package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataRowsTest {

    private static final String ROWS = """
            [{"$name":"Gold","tier":"GOLD","limit":500,"items":[{"sku":"A"}]},
             {"tier":"SILVER","limit":"100","$status":"422",
              "$body":{"$.customer.country":"XX","$.customer.vip":true},
              "$expected":{"$.status":"REJECTED"}}]
            """;

    @Test
    void normalizesRowsAndKeepsJsonTypes() {
        String stored = DataRows.normalize(ROWS);
        List<DataRows.Row> rows = DataRows.parse(stored);

        assertEquals(2, rows.size());
        assertEquals("Gold", rows.get(0).label());
        assertEquals("Row 2", rows.get(1).label());
        assertEquals(422, rows.get(1).status(), "a quoted status is stored as a number");
        Map<String, String> gold = DataRows.variables(rows.get(0));
        assertEquals("500", gold.get("limit"));
        assertEquals("500", gold.get("data:limit"));
        assertEquals("[{\"sku\":\"A\"}]", gold.get("items"));
        assertEquals("[{\"sku\":\"A\"}]", gold.get(CaseVariables.JSON_TYPE_PREFIX + "items"));
        assertFalse(gold.containsKey(CaseVariables.JSON_TYPE_PREFIX + "tier"), "text needs no JSON type");
        assertTrue(DataRows.columns(stored).containsAll(List.of("tier", "data:tier", "limit", "items")));
        assertFalse(DataRows.columns(stored).contains("$name"));
    }

    @Test
    void emptyRowsMeanNoData() {
        assertNull(DataRows.normalize(null));
        assertNull(DataRows.normalize("[]"));
        assertNull(DataRows.normalize(List.of()));
    }

    @Test
    void refusesWhatCannotRun() {
        assertRefused("[{\"first name\":\"x\"}]", "column name");
        assertRefused("[{\"$status\":99}]", "100 to 599");
        assertRefused("[{\"$body\":{\"customer\":1}}]", "starting with $");
        assertRefused("[{\"$what\":1}]", "unknown special column");
        assertRefused("{\"tier\":\"x\"}", "list of objects");
        assertRefused("[1]", "must be an object");
    }

    private static void assertRefused(String rows, String message) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> DataRows.normalize(rows));
        assertTrue(refused.getMessage().contains(message), refused.getMessage());
    }

    @Test
    void patchesTheBodyExpectedResponseAndStatusOfOneRowOnly() {
        StudioTestCase testCase = template();
        DataRows.Row silver = DataRows.parse(DataRows.normalize(ROWS)).get(1);

        StudioTestCase patched = DataRows.apply(testCase, silver);

        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) ((Map<String, Object>) patched.getRequestBody()).get("customer");
        assertEquals("XX", customer.get("country"));
        assertEquals(true, customer.get("vip"), "a new key is added to an existing object");
        assertEquals("{{tier}}", customer.get("tier"), "the template's placeholders stay for substitution");
        assertTrue(patched.getExpectedResponse().contains("\"REJECTED\""));
        assertEquals(422, patched.getStatusCode());
        assertEquals("FR", ((Map<?, ?>) ((Map<?, ?>) testCase.getRequestBody()).get("customer")).get("country"),
                "the case itself is unchanged");
        assertNull(patched.getDataRows());
    }

    @Test
    void removeLeavesFieldsOutOfTheRequestBody() {
        DataRows.Row row = DataRows.parse(DataRows.normalize(
                "[{\"$name\":\"No country\",\"$remove\":[\"$.customer.country\"],\"$body\":{\"$.customer.vip\":true}}]")).get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) ((Map<String, Object>) DataRows.apply(template(), row)
                .getRequestBody()).get("customer");

        assertFalse(customer.containsKey("country"));
        assertEquals(true, customer.get("vip"));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> DataRows.apply(template(),
                DataRows.parse(DataRows.normalize("[{\"$name\":\"Typo\",\"$remove\":[\"$.customer.contry\"]}]")).get(0)));
        assertTrue(missing.getMessage().startsWith("Typo: $remove path"), missing.getMessage());
        assertRefused("[{\"$remove\":\"$.a\"}]", "list of JSONPaths");
        assertRefused("[{\"$remove\":[\"a\"]}]", "starting with $");
    }

    @Test
    void theNextIndexAddsAListItemButAGapIsRefused() {
        StudioTestCase testCase = template();
        testCase.setRequestBody(new LinkedHashMap<>(Map.of("addresses", List.of(Map.of("type", "home"), Map.of("type", "mailing")))));
        DataRows.Row add = DataRows.parse(DataRows.normalize(
                "[{\"$body\":{\"$.addresses[2]\":{\"type\":\"work\"},\"$.addresses[0].city\":\"Accra\"}}]")).get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> addresses = (List<Map<String, Object>>) ((Map<String, Object>) DataRows.apply(testCase, add)
                .getRequestBody()).get("addresses");

        assertEquals(List.of("home", "mailing", "work"), addresses.stream().map(item -> item.get("type")).toList());
        assertEquals("Accra", addresses.get(0).get("city"));
        DataRows.Row gap = DataRows.parse(DataRows.normalize(
                "[{\"$name\":\"Gap\",\"$body\":{\"$.addresses[5]\":{}}}]")).get(0);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> DataRows.apply(testCase, gap));
        assertTrue(refused.getMessage().startsWith("Gap:"), refused.getMessage());
    }

    @Test
    void aPatchOnAPathWithNoParentNamesTheRow() {
        StudioTestCase testCase = template();
        DataRows.Row row = DataRows.parse(DataRows.normalize("[{\"$name\":\"Lost\",\"$body\":{\"$.nowhere.x\":1}}]")).get(0);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> DataRows.apply(testCase, row));
        assertTrue(refused.getMessage().startsWith("Lost:"), refused.getMessage());
    }

    @Test
    void dataColumnsAreDefinedForTheirOwnCaseOnly() {
        StudioTestCase testCase = template();
        testCase.setDataRows(DataRows.normalize("[{\"tier\":\"GOLD\",\"$body\":{\"$.token\":\"{{token}}\"}}]"));

        assertEquals(java.util.Set.of("items", "token"), CaseVariables.placeholderNames(testCase),
                "tier comes from the rows; items has no column; a placeholder inside a patch still counts");
    }

    @Test
    void runsEachRowWithItsValuesAndCarriesExtractsOn() {
        StudioTestCase testCase = template();
        testCase.setExtracts("orderId=$.id");
        List<DataRows.Row> rows = DataRows.parse(DataRows.normalize(ROWS));
        Map<String, String> variables = new LinkedHashMap<>(Map.of("baseUrl", "https://x", "tier", "environment"));
        List<String> sentTiers = new ArrayList<>();

        RunTestResult result = DataRowRun.run(testCase, rows, variables, (rowCase, rowVariables) -> {
            sentTiers.add(rowVariables.get("tier") + "/" + rowCase.getStatusCode());
            rowVariables.put("orderId", "o-" + sentTiers.size());
            rowVariables.put("extract:orderId", "o-" + sentTiers.size());
            boolean passed = rowCase.getStatusCode() == 200;
            return new RunTestResult(passed, false, null, rowCase.getId(), rowCase.getTestName(), "POST", "https://x",
                    rowCase.getStatusCode(), 200, passed, 10, 5000, false, "{}", Map.of(), Map.of(), List.of(),
                    passed ? null : "Expected status 422 but got 200", null, Map.of("orderId", "o-" + sentTiers.size()));
        });

        assertEquals(List.of("GOLD/200", "SILVER/422"), sentTiers);
        assertFalse(result.passed());
        assertEquals("1 of 2 data rows failed: Row 2", result.error());
        assertEquals(2, result.iterations().size());
        assertEquals("Gold", result.iterations().get(0).rowLabel());
        assertEquals(List.of(true, false), result.assertions().stream().map(c -> c.passed()).toList());
        assertEquals(20, result.elapsedMs());
        assertEquals("environment", variables.get("tier"), "a row's own values don't leak to later cases");
        assertEquals("o-2", variables.get("orderId"), "the last row's extract carries on");
    }

    @Test
    void aRowThatCannotBeBuiltFailsAloneAndTheOthersStillRun() {
        StudioTestCase testCase = template();
        List<DataRows.Row> rows = DataRows.parse(DataRows.normalize(
                "[{\"$name\":\"Broken\",\"$body\":{\"$.nowhere.x\":1}},{\"$name\":\"Fine\"}]"));

        RunTestResult result = DataRowRun.run(testCase, rows, new LinkedHashMap<>(), (rowCase, rowVariables) ->
                new RunTestResult(true, false, null, rowCase.getId(), rowCase.getTestName(), "POST", "https://x", 200,
                        200, true, 1, 5000, false, "{}", Map.of(), Map.of(), List.of(), null, null, Map.of()));

        assertEquals(List.of(false, true), result.iterations().stream().map(RunTestResult::passed).toList());
        assertTrue(result.iterations().get(0).error().startsWith("Broken:"));
    }

    private static StudioTestCase template() {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setId("case-1");
        testCase.setTestName("Create order");
        testCase.setMethod("POST");
        testCase.setStatusCode(200);
        testCase.setMaxResponseTimeMs(5000L);
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("tier", "{{tier}}");
        customer.put("country", "FR");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer", customer);
        body.put("items", "{{items}}");
        testCase.setRequestBody(body);
        testCase.setExpectedResponse("{\"status\":\"ACCEPTED\",\"tier\":\"{{tier}}\"}");
        return testCase;
    }
}
