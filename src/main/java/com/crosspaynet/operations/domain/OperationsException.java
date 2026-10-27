package com.crosspaynet.operations.domain;

public class OperationsException extends RuntimeException {
    public OperationsException(String message) {
        super(message);
    }

    public OperationsException(String message, Throwable cause) {
        super(message, cause);
    }
}
