package com.tx.api.test.generator.studio.safety;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Off: no checks. Shadow: record what would be blocked, block nothing. Enforce: block and record. */
public enum SafetyMode {
    OFF,
    SHADOW,
    ENFORCE;

    @JsonValue
    public String wire() {
        return Wire.name(this);
    }

    @JsonCreator
    public static SafetyMode from(String value) {
        return Wire.parse(SafetyMode.class, value, "mode");
    }
}
