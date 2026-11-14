package com.daimon.contracts;

/** Raised when a contract cannot be fulfilled: invalid input/output or executor failure. */
public class ContractExecutionException extends Exception {

    private static final long serialVersionUID = 1L;

    public ContractExecutionException(String message) {
        super(message);
    }

    public ContractExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
