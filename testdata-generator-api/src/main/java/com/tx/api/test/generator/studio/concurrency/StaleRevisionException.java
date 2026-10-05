package com.tx.api.test.generator.studio.concurrency;

public class StaleRevisionException extends RuntimeException {

    public StaleRevisionException() {
        super("Resource revision changed before save");
    }
}
