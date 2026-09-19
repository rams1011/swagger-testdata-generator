package com.tx.api.test.generator.studio.dto;

public record LoadSpecRequest(
        String location,
        String content,
        String fileName,
        String baseUrl,
        String name,
        String label,
        Boolean manual
) {
}
