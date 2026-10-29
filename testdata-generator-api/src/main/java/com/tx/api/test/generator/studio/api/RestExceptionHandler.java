package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.dto.ApiError;
import com.tx.api.test.generator.studio.tenant.ForbiddenException;
import com.tx.api.test.generator.studio.tenant.LoginLimitedException;
import com.tx.api.test.generator.studio.tenant.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Locale;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);
    static final String GENERIC_FAILURE = "Studio is unavailable. Reload and try again.";

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException exception) {
        log.warn("Bad request: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ApiError(clientMessage(exception.getMessage())));
    }

    @ExceptionHandler(LoginLimitedException.class)
    public ResponseEntity<ApiError> tooManySignIns(LoginLimitedException exception) {
        log.warn("Sign-in limited: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ApiError(clientMessage(exception.getMessage())));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ApiError> unauthorized(UnauthenticatedException exception) {
        log.warn("Unauthorized: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(clientMessage(exception.getMessage())));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenException exception) {
        log.warn("Forbidden: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(clientMessage(exception.getMessage())));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> missingStatic(NoResourceFoundException exception) {
        log.debug("No static resource: {}", exception.getResourcePath());
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> notFound(NoSuchElementException exception) {
        log.warn("Not found: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(clientMessage(exception.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException exception) {
        log.warn("Conflict: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(clientMessage(exception.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        log.error("Unhandled error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(GENERIC_FAILURE));
    }

    static String clientMessage(String message) {
        if (message == null || message.isBlank()) {
            return "That action could not be completed. Try again.";
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (message.indexOf('\n') >= 0
                || lower.contains("exception")
                || lower.contains("java.")
                || lower.contains("jdbc:")
                || lower.contains("org.springframework")
                || lower.contains("org.postgresql")
                || lower.contains("org.h2")
                || lower.contains("sql")
                || lower.contains("file:")
                || lower.contains("c:\\")
                || lower.contains("/home/")
                || lower.contains("/var/")
                || lower.contains("/usr/")
                || lower.contains("/opt/")) {
            return "That action could not be completed. Try again.";
        }
        return message;
    }
}
