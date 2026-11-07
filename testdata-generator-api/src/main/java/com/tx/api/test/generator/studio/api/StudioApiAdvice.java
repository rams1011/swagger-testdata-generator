package com.tx.api.test.generator.studio.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Runs before {@link RestExceptionHandler}, whose catch-all would otherwise answer a conflict with a 500. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class StudioApiAdvice {

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ConflictBody conflict(ConflictException exception) {
        return new ConflictBody(RestExceptionHandler.clientMessage(exception.getMessage()), exception.current());
    }
}
