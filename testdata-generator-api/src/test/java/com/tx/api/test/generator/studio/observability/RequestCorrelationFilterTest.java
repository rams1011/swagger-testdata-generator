package com.tx.api.test.generator.studio.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void echoesASafeIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.addHeader(RequestCorrelationFilter.HEADER, "req-12345678");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertEquals("req-12345678", MDC.get(RequestCorrelationFilter.MDC_KEY)));

        assertEquals("req-12345678", response.getHeader(RequestCorrelationFilter.HEADER));
        assertNull(MDC.get(RequestCorrelationFilter.MDC_KEY));
    }

    @Test
    void replacesAHeaderThatCouldBreakLogs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.addHeader(RequestCorrelationFilter.HEADER, "bad\nid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        String assigned = response.getHeader(RequestCorrelationFilter.HEADER);
        assertTrue(assigned != null && !assigned.contains("\n"));
        UUID.fromString(assigned);
        assertNull(MDC.get(RequestCorrelationFilter.MDC_KEY));
    }
}
