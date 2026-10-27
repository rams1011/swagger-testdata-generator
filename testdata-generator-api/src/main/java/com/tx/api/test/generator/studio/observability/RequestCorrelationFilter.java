package com.tx.api.test.generator.studio.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns {@code X-Request-Id} for every call and puts it in the log context.
 * OpenTelemetry adds {@code traceId} and {@code spanId} beside it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = requestId(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String requestId(String incoming) {
        if (isSafe(incoming)) {
            return incoming.trim();
        }
        return UUID.randomUUID().toString();
    }

    private static boolean isSafe(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        int length = trimmed.length();
        if (length < 8 || length > 128) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char ch = trimmed.charAt(i);
            if (ch <= ' ' || ch >= 127 || ch == '"' || ch == '\\') {
                return false;
            }
        }
        return true;
    }
}
