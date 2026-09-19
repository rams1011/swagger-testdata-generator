package com.tx.api.test.generator.studio.collection;

import java.util.Map;

public record StudioEnvironment(
        String format,
        int version,
        String name,
        Map<String, String> values,
        StudioEnvironmentAuth auth
) {
}
