package com.tx.api.test.generator.studio.tenant.github;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitHubAuthServiceTest {

    @Test
    void issuerIsStableForAccountLinking() {
        assertEquals("https://github.com", GitHubAuthService.ISSUER);
    }
}
