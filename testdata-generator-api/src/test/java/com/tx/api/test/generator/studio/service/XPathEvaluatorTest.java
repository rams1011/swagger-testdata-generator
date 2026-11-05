package com.tx.api.test.generator.studio.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XPathEvaluatorTest {

    static final String ROLES = """
            <roles xmlns="urn:example:roles" total="3">
              <role id="48"><code>TestCode</code><name> Test </name><active>true</active></role>
              <role id="12"><code>Admin</code><name>Admin</name><active>true</active></role>
              <role id="7"><code>Guest</code><name>Guest</name><active>false</active></role>
            </roles>
            """;

    @Test
    void singleNodesAttributesAndFunctions() {
        assertCheck(true, "xpath://role[code='TestCode']/name", "Test");
        assertCheck(true, "xpath://role[code='TestCode']/@id", "48");
        assertCheck(true, "xpath:/roles/@total", "op:gte:3");
        assertCheck(true, "xpath:count(//role)", "3");
        assertCheck(true, "xpath:count(//role[active='true'])", "op:range:1..2");
        assertCheck(true, "xpath:string(//role[1]/code)", "op:matches:Test.*");
        assertCheck(true, "XPATH: //role[@id='7']/active", "false");
        assertCheck(false, "xpath://role[code='TestCode']/name", "Admin");
    }

    @Test
    void severalNodesFollowTheListRules() {
        assertCheck(true, "xpath://role/code", "op:contains:Admin");
        assertCheck(true, "xpath://role/code", "op:length:3");
        assertCheck(true, "xpath://role/@id", "op:gt:0");
        assertCheck(false, "xpath://role/@id", "op:gt:10");
        assertCheck(true, "xpath://role/code", "op:notContains:Deleted");
    }

    @Test
    void missingInvalidAndNonXml() {
        assertCheck(true, "xpath://role[code='Deleted']", "op:notExists");
        JsonPathEvaluator.Check missing = XPathEvaluator.check(ROLES, "xpath://role[code='Deleted']/name", "x");
        assertFalse(missing.passed());
        assertEquals("(missing)", missing.actual());
        JsonPathEvaluator.Check invalid = XPathEvaluator.check(ROLES, "xpath://role[", "x");
        assertFalse(invalid.passed());
        assertTrue(invalid.actual().startsWith("(invalid XPath"), invalid.actual());
        assertEquals("(not XML)", XPathEvaluator.check("{\"a\":1}", "xpath://a", "1").actual());
        assertEquals("(not XML)", XPathEvaluator.check("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>",
                "xpath:/x", "op:exists").actual());
        assertTrue(XPathEvaluator.isXPath(" xpath:/a"));
        assertFalse(XPathEvaluator.isXPath("$.xpath"));
    }

    private static void assertCheck(boolean expected, String path, String check) {
        JsonPathEvaluator.Check result = XPathEvaluator.check(ROLES, path, check);
        assertEquals(expected, result.passed(), path + " " + check + " actual=" + result.actual());
    }
}
