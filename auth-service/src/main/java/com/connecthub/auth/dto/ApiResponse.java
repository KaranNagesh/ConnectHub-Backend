package com.connecthub.auth.dto;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonInclude;

@Data @AllArgsConstructor @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private Integer cooldownSeconds;

    public static <T> ApiResponse<T> ok(String message) { return ApiResponse.<T>builder().success(true).message(message).build(); }
    public static <T> ApiResponse<T> ok(String message, T data) { return ApiResponse.<T>builder().success(true).message(message).data(data).build(); }
    public static <T> ApiResponse<T> error(String message) { return ApiResponse.<T>builder().success(false).message(message).build(); }
}
