package com.tx.api.test.generator.studio.dto;

public record RunCompareField(
        String field,
        String left,
        String right,
        boolean same
) {
}
