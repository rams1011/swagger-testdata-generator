package com.tx.api.test.generator.studio.dto;

public record AssertionCheck(
        String name,
        String path,
        String expected,
        String actual,
        boolean passed
) {
}
