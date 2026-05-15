package com.connecthub.payment.exception;

import com.connecthub.payment.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice
@Slf4j
public class PaymentExceptionHandler {

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ErrorResponse> handlePaymentGateway(PaymentGatewayException ex) {
        log.warn("Payment gateway request failed: {}", ex.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "Payment gateway error", ex.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        String message = ex instanceof MethodArgumentNotValidException validationException
                ? validationException.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .orElse("Invalid payment request")
                : ex.getMessage();
        return error(HttpStatus.BAD_REQUEST, "Bad request", message);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException ex) {
        log.warn("Payment service request failed: {}", ex.getMessage());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Payment service error", ex.getMessage());
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.builder()
                .error(error)
                .message(message)
                .status(status.value())
                .timestamp(LocalDateTime.now())
                .build());
    }
}
