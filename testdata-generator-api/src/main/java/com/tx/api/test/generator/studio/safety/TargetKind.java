package com.tx.api.test.generator.studio.safety;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** A protected target is a host or host pattern, or an IP range. */
public enum TargetKind {
    HOST,
    CIDR;

    @JsonValue
    public String wire() {
        return Wire.name(this);
    }

    @JsonCreator
    public static TargetKind from(String value) {
        return Wire.parse(TargetKind.class, value, "kind");
    }
}
