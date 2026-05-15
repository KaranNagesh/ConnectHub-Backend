package com.connecthub.message.exception;

import lombok.Getter;

@Getter
public class TooManyRequestsException extends RuntimeException {
    private final int limit;

    public TooManyRequestsException(String message, int limit) {
        super(message);
        this.limit = limit;
    }
}
