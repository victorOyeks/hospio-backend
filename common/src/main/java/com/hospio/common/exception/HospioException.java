package com.hospio.common.exception;

public class HospioException extends RuntimeException {

    public HospioException(String message) {
        super(message);
    }

    public HospioException(String message, Throwable cause) {
        super(message, cause);
    }
}
