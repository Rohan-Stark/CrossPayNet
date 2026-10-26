package com.crosspaynet.compliance.contracts;

public class ComplianceException extends RuntimeException {
    public ComplianceException(String message) {
        super(message);
    }

    public ComplianceException(String message, Throwable cause) {
        super(message, cause);
    }
}
