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

import java.util.NoSuchElementException;

@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException exception) {
        log.warn("Bad request: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(LoginLimitedException.class)
    public ResponseEntity<ApiError> tooManySignIns(LoginLimitedException exception) {
        log.warn("Sign-in limited: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ApiError> unauthorized(UnauthenticatedException exception) {
        log.warn("Unauthorized: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenException exception) {
        log.warn("Forbidden: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> missingStatic(NoResourceFoundException exception) {
        log.debug("No static resource: {}", exception.getResourcePath());
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> notFound(NoSuchElementException exception) {
        log.warn("Not found: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException exception) {
        log.warn("Conflict: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        String message = exception.getMessage() == null ? "Unexpected error" : exception.getMessage();
        log.error("Unhandled error: {}", message, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(message));
    }
}
