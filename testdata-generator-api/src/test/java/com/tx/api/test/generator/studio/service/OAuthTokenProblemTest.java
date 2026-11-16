package com.tx.api.test.generator.studio.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A failed OAuth token request says why, instead of only "failed". */
class OAuthTokenProblemTest {

    @Test
    void namesTheReason() {
        assertEquals("nothing answered at the token URL (connection refused)",
                TestRunService.tokenRequestProblem(new IOException(new ConnectException("refused"))));
        assertEquals("the token URL's host name could not be found",
                TestRunService.tokenRequestProblem(new UnknownHostException("nope.example")));
        assertEquals("the token URL didn't answer in time",
                TestRunService.tokenRequestProblem(new HttpTimeoutException("slow")));
        assertEquals("the token URL couldn't be reached", TestRunService.tokenRequestProblem(new IOException("other")));
    }
}
