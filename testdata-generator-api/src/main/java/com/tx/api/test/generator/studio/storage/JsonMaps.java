package com.tx.api.test.generator.studio.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonMaps {

    /**
     * Whole numbers read back as whole numbers (Gson's default reads every number as a double, so {@code 3} became
     * {@code 3.0} in sent bodies and {@code minItems: 1.0} in schemas), and a JSON {@code null} in a body is kept.
     */
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
            .create();
    private static final Type STRING_MAP = new TypeToken<LinkedHashMap<String, String>>() { }.getType();
    private static final Type OBJECT_MAP = new TypeToken<LinkedHashMap<String, Object>>() { }.getType();
    private static final Type STRING_LIST = new TypeToken<ArrayList<String>>() { }.getType();

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

    static List<String> toStringList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        List<String> list = GSON.fromJson(json, STRING_LIST);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list;
    }
}
