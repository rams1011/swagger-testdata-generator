package com.tx.api.test.generator.openapi;

import com.sun.net.httpserver.HttpServer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every spec here points at a local server, so a fetch by the parser shows up as a hit. */
class OpenApiSpecLoaderReferenceTest {

    private static final String SCHEMA_AT = "openapi: 3.0.0\ninfo: {title: t, version: '1'}\npaths:\n  /a:\n    get:\n"
            + "      responses:\n        '200':\n          description: ok\n          content:\n"
            + "            application/json:\n              schema:\n";
    private static final String PET = "openapi: 3.0.0\ninfo: {title: t, version: '1'}\npaths:\n  /a:\n    get:\n"
            + "      responses:\n        '200':\n          description: ok\n          content:\n"
            + "            application/json:\n              schema:\n                $ref: '#/components/schemas/Pet'\n"
            + "components:\n  schemas:\n    Pet:\n      type: object\n      properties:\n        kind: {type: string}\n";

    private final AtomicInteger hits = new AtomicInteger();
    private HttpServer server;
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "type: object\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/x.yaml";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void externalReferencesAreRefusedInEverySpellingWithoutAnyFetch() {
        Map<String, String> specs = new LinkedHashMap<>();
        specs.put("plain", SCHEMA_AT + "                $ref: " + url + "\n");
        specs.put("list item", SCHEMA_AT + "                oneOf:\n                  - $ref: " + url + "\n");
        specs.put("flow map", SCHEMA_AT + "                {$ref: " + url + "}\n");
        specs.put("single-quoted key", SCHEMA_AT + "                '$ref': " + url + "\n");
        specs.put("double-quoted key", SCHEMA_AT + "                \"$ref\": " + url + "\n");
        specs.put("yaml escape in key", SCHEMA_AT + "                \"\\x24ref\": " + url + "\n");
        specs.put("explicit key", SCHEMA_AT + "                ? $ref\n                : " + url + "\n");
        specs.put("relative file", SCHEMA_AT + "                {$ref: ./secrets.yaml}\n");
        specs.put("file url", SCHEMA_AT + "                {$ref: 'file:/etc/passwd'}\n");
        specs.put("alias value", "x-u: &u " + url + "\n" + SCHEMA_AT + "                {$ref: *u}\n");
        specs.put("object value", SCHEMA_AT + "                {$ref: {a: b}}\n");
        specs.put("later duplicate", SCHEMA_AT + "                {$ref: '#/a', $ref: " + url + "}\n");
        specs.put("second document", PET + "---\nx: {$ref: " + url + "}\n");
        specs.put("json", "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},\"paths\":{\"/a\":"
                + "{\"$ref\":\"" + url + "\"}}}");
        specs.put("json unicode escape", "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},\"paths\":"
                + "{\"/a\":{\"$\\u0072ef\":\"" + url + "\"}}}");
        specs.put("swagger 2", "swagger: '2.0'\ninfo: {title: t, version: '1'}\npaths:\n  /a:\n    get:\n"
                + "      responses:\n        '200':\n          description: ok\n          schema: {$ref: " + url + "}\n");
        specs.put("path item", "openapi: 3.0.0\ninfo: {title: t, version: '1'}\npaths:\n  /a: {$ref: " + url + "}\n");
        specs.put("mapping url", PET + "      discriminator:\n        propertyName: kind\n        mapping:\n"
                + "          dog: " + url + "\n");
        specs.put("mapping relative", PET + "      discriminator:\n        propertyName: kind\n        mapping:\n"
                + "          dog: ./dog.yaml\n");
        specs.put("mapping alias value", "x-u: &u " + url + "\n" + PET + "      discriminator:\n"
                + "        propertyName: kind\n        mapping:\n          dog: *u\n");
        specs.put("mapping alias", "x-m: &m {dog: https://other.example/dog.yaml}\n" + PET
                + "      discriminator:\n        propertyName: kind\n        mapping: *m\n");
        specs.put("discriminator alias", "x-d: &d {propertyName: kind}\n" + PET + "      discriminator: *d\n");
        specs.put("merged mapping", "x-m: &m {cat: Cat}\n" + PET + "      discriminator:\n        propertyName: kind\n"
                + "        mapping: {<<: *m}\n");

        specs.forEach((name, spec) -> {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> OpenApiSpecLoader.loadFromContent(spec), name);
            assertTrue(exception.getMessage().contains("Remote or file references are not allowed"),
                    name + ": " + exception.getMessage());
            assertEquals(0, hits.get(), name);
        });
    }

    @Test
    void theParserOptionsBlockARemoteReferenceEvenWithoutTheScan() {
        new OpenAPIV3Parser().readContents(SCHEMA_AT + "                {$ref: " + url + "}\n", null,
                OpenApiSpecLoader.contentParseOptions());
        assertEquals(0, hits.get());
    }

    @Test
    void internalReferencesAndMappingsStillLoad() {
        OpenAPI yaml = OpenApiSpecLoader.loadFromContent(PET + "      discriminator:\n        propertyName: kind\n"
                + "        mapping:\n          dog: Pet\n          cat: '#/components/schemas/Pet'\n");
        assertNotNull(yaml.getPaths().get("/a"));
        OpenAPI json = OpenApiSpecLoader.loadFromContent("{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"t\",\"version\":"
                + "\"1\"},\"paths\":{\"/a\":{\"get\":{\"responses\":{\"200\":{\"description\":\"ok\",\"content\":"
                + "{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/Pet\"}}}}}}}},\"components\":"
                + "{\"schemas\":{\"Pet\":{\"type\":\"object\"}}}}");
        assertNotNull(json.getPaths().get("/a"));
    }

    @Test
    void textThatOnlyMentionsAReferenceIsNotAReference() {
        OpenAPI spec = OpenApiSpecLoader.loadFromContent(SCHEMA_AT
                + "                type: object\n                description: 'see \"$ref\": \"" + url + "\"'\n"
                + "                properties:\n                  mapping:\n                    type: string\n"
                + "                    description: a.b/c\n");
        assertNotNull(spec);
        assertEquals(0, hits.get());
    }

    @Test
    void aSpecLargerThanTheDefaultYamlLimitIsStillRead() {
        StringBuilder spec = new StringBuilder(SCHEMA_AT).append("                type: string\n");
        spec.append("x-padding: '").append("a".repeat(4_000_000)).append("'\n");
        OpenApiSpecLoader.rejectExternalReferences(spec.toString());
    }

    @Test
    void textThatCannotBeReadIsRefused() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> OpenApiSpecLoader.loadFromContent("{\"openapi\": "));
        assertTrue(exception.getMessage().contains("not valid JSON or YAML"), exception.getMessage());
    }
}
