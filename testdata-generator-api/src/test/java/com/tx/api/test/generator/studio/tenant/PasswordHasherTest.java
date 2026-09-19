package com.tx.api.test.generator.studio.tenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void hashesAreSaltedAndVerified() {
        String hash = PasswordHasher.hash("password1");
        assertTrue(hash.startsWith("pbkdf2$"));
        assertTrue(PasswordHasher.matches("password1", hash));
        assertFalse(PasswordHasher.matches("password2", hash));
        assertNotEquals(hash, PasswordHasher.hash("password1"));
    }
}
