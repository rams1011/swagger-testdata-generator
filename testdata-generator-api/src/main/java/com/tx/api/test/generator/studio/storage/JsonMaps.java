package com.tx.api.test.generator.studio.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

final class JsonMaps {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Type STRING_MAP = new TypeToken<LinkedHashMap<String, String>>() { }.getType();
    private static final Type OBJECT_MAP = new TypeToken<LinkedHashMap<String, Object>>() { }.getType();

    private JsonMaps() {
    }

    static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        return GSON.toJson(value);
    }

    static Object toObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return GSON.fromJson(json, Object.class);
    }

    static Map<String, String> toStringMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> map = GSON.fromJson(json, STRING_MAP);
        return map == null ? new LinkedHashMap<>() : map;
    }

    static Map<String, Object> toObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> map = GSON.fromJson(json, OBJECT_MAP);
        return map == null ? new LinkedHashMap<>() : map;
    }
}
