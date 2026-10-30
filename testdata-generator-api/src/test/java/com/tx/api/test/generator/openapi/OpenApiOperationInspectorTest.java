package com.tx.api.test.generator.openapi;

import com.tx.api.test.generator.TestdataGenerator;
import com.tx.api.test.generator.catalog.OperationPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenApiOperationInspectorTest {

    private static final String WEBHOOK_ONLY = """
            {
              "openapi": "3.1.0",
              "info": { "title": "Configuration webhooks", "version": "1" },
              "webhooks": {
                "balancePlatform.accountHolder.created": {
                  "post": {
                    "operationId": "post-balancePlatform.accountHolder.created",
                    "summary": "Account holder created",
                    "tags": ["Account holder"],
                    "requestBody": {
                      "content": {
                        "application/json": {
                          "schema": {
                            "type": "object",
                            "properties": { "type": { "type": "string" } }
                          }
                        }
                      }
                    },
                    "responses": {
                      "200": {
                        "description": "OK",
                        "content": {
                          "application/json": {
                            "schema": { "type": "string" }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    @Test
    void inspectIncludesOpenApi31WebhookEntries() {
        List<OperationPage> pages = TestdataGenerator.inspect(
                OpenApiSpecLoader.loadFromContent(WEBHOOK_ONLY));
        assertFalse(pages.isEmpty());
        OperationPage page = pages.get(0);
        assertEquals("POST", page.method());
        assertEquals("/webhooks/balancePlatform.accountHolder.created", page.path());
        assertEquals("Account holder created", page.summary());
        assertEquals("post-balancePlatform.accountHolder.created", page.key());
    }
}
