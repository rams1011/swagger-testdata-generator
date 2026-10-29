package com.tx.api.test.generator.studio.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RestExceptionHandlerTest {

    private final RestExceptionHandler handler = new RestExceptionHandler();

    @Test
    void unexpectedErrorHidesTheExceptionText() {
        var response = handler.unexpected(new IllegalStateException("jdbc:postgresql://db.internal/studio"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(RestExceptionHandler.GENERIC_FAILURE, response.getBody().error());
        assertFalse(response.getBody().error().contains("jdbc"));
    }

    @Test
    void productMessageStaysAndADatabaseMessageDoesNot() {
        assertEquals("Spec not found", RestExceptionHandler.clientMessage("Spec not found"));
        assertEquals("That action could not be completed. Try again.",
                RestExceptionHandler.clientMessage("jdbc:h2:file:/var/studio"));
    }
}
