package com.tx.api.test.generator.studio.safety;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Whether the only admin of an organization may approve their own production write request. */
public enum SelfApproval {
    ALLOWED,
    NEVER;

    @JsonValue
    public String wire() {
        return Wire.name(this);
    }

    @JsonCreator
    public static SelfApproval from(String value) {
        return Wire.parse(SelfApproval.class, value, "selfApproval");
    }
}
