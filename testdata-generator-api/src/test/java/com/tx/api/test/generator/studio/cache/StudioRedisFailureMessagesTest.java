package com.tx.api.test.generator.studio.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioRedisFailureMessagesTest {

    @Test
    void summarizesCauseChain() {
        Exception root = new IllegalStateException("Connection refused");
        Exception wrapped = new RuntimeException("Unable to connect to Redis", root);
        String text = StudioRedisFailureMessages.summarize(wrapped);
        assertTrue(text.contains("RuntimeException"));
        assertTrue(text.contains("Connection refused"));
    }
}
