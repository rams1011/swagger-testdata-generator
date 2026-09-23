package com.tx.api.test.generator.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiSpecLoaderTest {

    private static final String OPENAPI_31 = """
            {
              "openapi": "3.1.0",
              "info": { "title": "WinRef", "version": "1" },
              "paths": {
                "/pets": {
                  "get": {
                    "responses": {
                      "200": {
                        "description": "ok",
                        "content": {
                          "application/json": {
                            "schema": { "$ref": "#/components/schemas/Pet" }
                          }
                        }
                      }
                    }
                  }
                }
              },
              "components": {
                "schemas": {
                  "Pet": {
                    "type": "object",
                    "properties": { "id": { "type": "integer" } }
                  }
                }
              }
            }
            """;

    @Test
    void loadFromContentResolvesOpenApi31SchemaRefs() {
        OpenAPI openApi = OpenApiSpecLoader.loadFromContent(OPENAPI_31);
        assertNotNull(openApi);
        assertEquals("3.1.0", openApi.getOpenapi());
        assertNotNull(openApi.getPaths().get("/pets").getGet());
    }

    @Test
    void localWindowsPathIsConvertedToFileUri(@TempDir Path tempDir) throws Exception {
        Path spec = tempDir.resolve("openapi-spec.json");
        Files.writeString(spec, OPENAPI_31, StandardCharsets.UTF_8);
        String fileUri = OpenApiSpecLoader.toParserLocation(spec.toString());
        assertTrue(fileUri.startsWith("file:"), fileUri);
        assertFalse(fileUri.contains("\\"), fileUri);
        assertNotNull(OpenApiSpecLoader.load(spec.toString()));
    }
}
