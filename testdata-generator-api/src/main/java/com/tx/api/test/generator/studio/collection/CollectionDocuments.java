package com.tx.api.test.generator.studio.collection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

public final class CollectionDocuments {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private CollectionDocuments() {
    }

    public static String toJson(Object document) {
        return GSON.toJson(document);
    }

    public static StudioCollection parseCollection(String json) {
        StudioCollection collection = parse(json, StudioCollection.class);
        CollectionMapper.validate(collection);
        return collection;
    }

    public static StudioEnvironment parseEnvironment(String json) {
        StudioEnvironment environment = parse(json, StudioEnvironment.class);
        CollectionMapper.validate(environment);
        return environment;
    }

    public static JsonElement parseTree(String json) {
        try {
            return JsonParser.parseString(json);
        } catch (JsonSyntaxException exception) {
            throw new CollectionFormatException("Collection JSON is not valid.");
        }
    }

    private static <T> T parse(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new CollectionFormatException("Collection JSON is required");
        }
        try {
            T value = GSON.fromJson(json, type);
            if (value == null) {
                throw new CollectionFormatException("Collection JSON is empty");
            }
            return value;
        } catch (JsonSyntaxException exception) {
            throw new CollectionFormatException("Collection JSON is not valid.");
        }
    }
}
