package com.tx.api.test.generator.studio.safety;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Treat as read lets a write through (POST /search); Block stops a read (GET /admin/export-all). */
public enum RuleEffect {
    TREAT_AS_READ,
    BLOCK;

    @JsonValue
    public String wire() {
        return Wire.name(this);
    }

    @JsonCreator
    public static RuleEffect from(String value) {
        return Wire.parse(RuleEffect.class, value, "effect");
    }
}
