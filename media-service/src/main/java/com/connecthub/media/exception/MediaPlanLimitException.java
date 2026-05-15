package com.connecthub.media.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class MediaPlanLimitException extends RuntimeException {
    public MediaPlanLimitException(String message) {
        super(message);
    }
}
