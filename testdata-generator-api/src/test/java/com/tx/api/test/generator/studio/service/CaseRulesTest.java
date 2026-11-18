package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A stored case can't hold what the editor refuses, whether it comes from the API or a collection file. */
class CaseRulesTest {

    @Test
    void cookiesFollowTheSameRulesAsHeaders() {
        assertNull(CaseRules.cookieProblem(Map.of("session", "abc", "", "ignored")));
        assertTrue(CaseRules.cookieProblem(Map.of("session", "a\nb")).contains("line break"));
        assertTrue(CaseRules.cookieProblem(Map.of("bad name", "x")).contains("isn't a valid cookie name"));
        assertTrue(CaseRules.cookieProblem(Map.of("session", "a;b=c")).contains("can't contain ;"), "as the editor says");
        assertNull(CaseRules.cookieProblem(Map.of("session", "{{token}}")), "a variable is checked when it's filled in");
    }

    @Test
    void statusAndResponseTypeMustBeOnesTheEditorOffers() {
        assertDoesNotThrow(() -> CaseRules.requireStorable(storable(201, "JSON")));
        assertDoesNotThrow(() -> CaseRules.requireStorable(storable(599, "none")));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireStorable(storable(99, "JSON")));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireStorable(storable(600, "JSON")));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireStorable(storable(200, "XML2")));
    }

    @Test
    void extractsNeedAVariableNameAndAReadableSource() {
        assertDoesNotThrow(() -> CaseRules.requireExtracts("postId=$.id|loc=header:Location|sid=cookie:SID|x=body:$.a"));
        assertDoesNotThrow(() -> CaseRules.requireExtracts("NA"));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireExtracts("1abc=$.id"));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireExtracts("x=$.[["));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireExtracts("justtext"));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireExtracts("loc=header:"));
    }

    @Test
    void checksNeedAPathAndAKnownOperatorWithAUsableArgument() {
        assertDoesNotThrow(() -> CaseRules.requireAssertions(
                "$.id=op:gt:0|$.name=op:matches:^A.*|$.tags=op:length:1..3|$.n=op:range:1..|$.t=op:type:string"));
        assertDoesNotThrow(() -> CaseRules.requireAssertions("$.id=op:gt:{{minimum}}|xpath://item=x|$.a=plain value"));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireAssertions("$.id=op:gt:abc"));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireAssertions("$..[=op:exists"));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireAssertions("$.id=op:bigger:1"));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireAssertions("$.t=op:type:text"));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireAssertions("$.n=op:matches:(["));
    }

    @Test
    void unknownValuesAreRefusedInsteadOfReadAsADefault() {
        assertEquals("Y", CaseRules.enabledFlag(null));
        assertEquals("N", CaseRules.enabledFlag("false"));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.enabledFlag("maybe"));
        assertDoesNotThrow(() -> CaseRules.requireResponseMatch("ignoreOrder"));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireResponseMatch("fuzzy"));
        assertDoesNotThrow(() -> CaseRules.requireAuthType(new RunAuth("bearer", null, null, null, null, null, null,
                null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> CaseRules.requireAuthType(new RunAuth("KERBEROS", null,
                null, null, null, null, null, null, null, null, null, null)));
    }

    private static StudioTestCase storable(int status, String responseType) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setStatusCode(status);
        testCase.setResponseType(responseType);
        testCase.setExtracts("NA");
        testCase.setJsonPathAssertions("NA");
        return testCase;
    }
}
