package com.tx.api.test.generator.studio.collection;

/**
 * A named value referenced as {@code {{key}}} in a case. A secret variable never stores its value on the server:
 * runs receive it from the browser, and exports write a {@code ${ENV_VAR}} placeholder.
 */
public record StudioVariable(
        String key,
        String value,
        boolean secret
) {
}
