package com.tx.api.test.generator.studio.collection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/**
 * Writes a case's request body to the collection file with its JSON {@code null}s, such as {@code "middleName": null},
 * which a test may send on purpose; the rest of the file still leaves empty fields out. Whole numbers stay whole.
 */
public final class KeepNullsJson extends TypeAdapter<Object> {

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create();

    @Override
    public void write(JsonWriter out, Object value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        boolean serializeNulls = out.getSerializeNulls();
        out.setSerializeNulls(true);
        try {
            GSON.toJson(value, Object.class, out);
        } finally {
            out.setSerializeNulls(serializeNulls);
        }
    }

    @Override
    public Object read(JsonReader in) throws IOException {
        return GSON.getAdapter(Object.class).read(in);
    }
}
