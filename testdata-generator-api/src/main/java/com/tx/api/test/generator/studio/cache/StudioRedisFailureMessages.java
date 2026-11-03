package com.tx.api.test.generator.studio.cache;

import org.apache.commons.lang3.StringUtils;

/**
 * Formats Redis connection and command failures for operator logs (no secrets).
 */
public final class StudioRedisFailureMessages {

    private StudioRedisFailureMessages() {
    }

    public static String summarize(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        StringBuilder message = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 6) {
            if (depth > 0) {
                message.append(" | caused by: ");
            }
            String name = current.getClass().getSimpleName();
            String text = StringUtils.defaultIfBlank(current.getMessage(), name);
            message.append(name).append(": ").append(text);
            current = current.getCause();
            depth += 1;
        }
        return message.toString();
    }
}
