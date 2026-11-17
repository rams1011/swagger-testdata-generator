package com.tx.api.test.generator.studio.safety;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RuleProtocol {
    REST,
    GRAPHQL,
    GRPC;

    @JsonValue
    public String wire() {
        return Wire.name(this);
    }

    @JsonCreator
    public static RuleProtocol from(String value) {
        return Wire.parse(RuleProtocol.class, value, "protocol");
    }
}
