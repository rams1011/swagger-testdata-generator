package com.tx.api.test.generator.studio.api;

public class ConflictException extends RuntimeException {

    private final Object current;

    public ConflictException(String message) {
        this(message, null);
    }

    public ConflictException(String message, Object current) {
        super(message);
        this.current = current;
    }

    public Object current() {
        return current;
    }
}
