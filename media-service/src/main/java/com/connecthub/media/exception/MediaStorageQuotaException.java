package com.connecthub.media.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
public class MediaStorageQuotaException extends RuntimeException {
    public MediaStorageQuotaException(String message) {
        super(message);
    }
}
