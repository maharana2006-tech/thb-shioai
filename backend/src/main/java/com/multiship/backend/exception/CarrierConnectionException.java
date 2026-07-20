package com.multiship.backend.exception;

public class CarrierConnectionException extends RuntimeException {

    public CarrierConnectionException(String message) {
        super(message);
    }

    public CarrierConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
