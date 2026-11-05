package com.tx.api.test.generator.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tx.api.test.generator.config.GeneratorOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionGeneratorTest {

    @TempDir
    Path temp;

    @Test
    void writesCollectionAndEnvironmentInStudioFormat() throws Exception {
        CollectionGenerator.Result result = new CollectionGenerator().generate(options()
                .outputFile(temp.resolve("out/orders.Default.collection.json").toString())
                .build());

        assertTrue(Files.exists(result.collectionFile()));
        assertEquals(temp.resolve("out/Orders-Service.Default.environment.json").toAbsolutePath(),
                result.environmentFile().toAbsolutePath());
        JsonObject collection = JsonParser.parseString(Files.readString(result.collectionFile())).getAsJsonObject();
        assertEquals("testdata.studio.collection", collection.get("format").getAsString());
        assertEquals(1, collection.get("version").getAsInt());
        assertEquals("Orders-Service", collection.getAsJsonObject("spec").get("name").getAsString());
        assertEquals("https://orders.example.com/api", collection.getAsJsonObject("spec").get("baseUrl").getAsString());
        assertEquals("Default", collection.getAsJsonObject("plan").get("name").getAsString());

        JsonArray cases = collection.getAsJsonArray("cases");
        assertEquals(3, cases.size(), "one happy-path case per operation by default");
        assertEquals(3, result.caseCount());
        Set<String> ids = new HashSet<>();
        for (var element : cases) {
            JsonObject item = element.getAsJsonObject();
            assertTrue(ids.add(item.get("id").getAsString()));
            assertFalse(item.get("testName").getAsString().isBlank());
            assertEquals("{{baseUrl}}", item.get("baseUrl").getAsString());
        }
        JsonObject create = find(cases, "createOrder");
        assertEquals("POST", create.get("method").getAsString());
        assertEquals("/orders", create.get("path").getAsString());
        assertEquals(201, create.get("statusCode").getAsInt());
        assertTrue(create.getAsJsonObject("body").has("sku"));
        assertTrue(create.has("responseJsonSchema"));
        JsonObject get = find(cases, "getOrder");
        assertEquals("/orders/{id}", get.get("path").getAsString());
        assertFalse(get.get("endpoint").getAsString().contains("{"), get.get("endpoint").getAsString());

        JsonObject environment = JsonParser.parseString(Files.readString(result.environmentFile())).getAsJsonObject();
        assertEquals("testdata.studio.environment", environment.get("format").getAsString());
        assertEquals("Default", environment.get("name").getAsString());
        assertEquals("https://orders.example.com/api",
                environment.getAsJsonObject("values").get("baseUrl").getAsString());
        assertEquals("BEARER", environment.getAsJsonObject("auth").get("type").getAsString());
        assertEquals("${AUTOMATION_BEARER_TOKEN}", environment.getAsJsonObject("auth").get("token").getAsString());
    }

    @Test
    void optionsFilterOperationsAddNegativeCasesAndOverrideNames() throws Exception {
        CollectionGenerator.Result result = new CollectionGenerator().generate(options()
                .outputFile(temp.resolve("c.json").toString())
                .environmentOutputFile(temp.resolve("e.json").toString())
                .includePattern("createOrder")
                .includeNegativeCases(true)
                .planName("Smoke")
                .environmentName("int")
                .baseUrlOverride("https://int.example.com")
                .authType("none")
                .build());

        JsonObject collection = JsonParser.parseString(Files.readString(result.collectionFile())).getAsJsonObject();
        JsonArray cases = collection.getAsJsonArray("cases");
        assertTrue(cases.size() > 1, "negative cases included");
        for (var element : cases) {
            assertEquals("createOrder", element.getAsJsonObject().get("operationKey").getAsString());
        }
        assertEquals("Smoke", collection.getAsJsonObject("plan").get("name").getAsString());
        JsonObject environment = JsonParser.parseString(Files.readString(result.environmentFile())).getAsJsonObject();
        assertEquals("int", environment.get("name").getAsString());
        assertEquals("https://int.example.com", environment.getAsJsonObject("values").get("baseUrl").getAsString());
        assertEquals("NONE", environment.getAsJsonObject("auth").get("type").getAsString());
    }

    @Test
    void noMatchingOperationsFails() {
        assertThrows(IllegalStateException.class, () -> new CollectionGenerator().generate(options()
                .outputFile(temp.resolve("c.json").toString())
                .includePattern("nothing-matches")
                .build()));
    }

    private static GeneratorOptions.GeneratorOptionsBuilder options() throws URISyntaxException {
        Path spec = Path.of(CollectionGeneratorTest.class.getResource("/orders-openapi.json").toURI());
        return GeneratorOptions.builder().swaggerLocation(spec.toString()).enabled("Y").maxResponseTimeMs(3000L);
    }

    private static JsonObject find(JsonArray cases, String operationKey) {
        for (var element : cases) {
            JsonObject item = element.getAsJsonObject();
            if (operationKey.equals(item.get("operationKey").getAsString())) {
                return item;
            }
        }
        throw new AssertionError("No case for " + operationKey);
    }
}
