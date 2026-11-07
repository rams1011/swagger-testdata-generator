package com.tx.api.test.generator.studio.api;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The editor's "reload or overwrite" dialog depends on a conflict reaching it as a 409 carrying the current copy. */
class ConflictResponseTest {

    @RestController
    static class ConflictingController {
        @GetMapping("/conflict")
        Object conflict() {
            throw new ConflictException("Someone else saved a newer version.", Map.of("testName", "Their copy"));
        }
    }

    @Test
    void aConflictIsA409WithTheCurrentCopyNotAServerError() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConflictingController())
                .setControllerAdvice(new RestExceptionHandler(), new StudioApiAdvice())
                .build();

        var response = mvc.perform(get("/conflict")).andReturn().getResponse();

        assertEquals(409, response.getStatus());
        assertTrue(response.getContentAsString().contains("Their copy"));
    }
}
