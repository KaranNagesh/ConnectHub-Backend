package com.connecthub.presence.dto;
import lombok.*;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class UserPresence implements Serializable {
    private int userId;
    private String status;
    private String customMessage;
    private String deviceType;
    private String sessionId;
    private LocalDateTime connectedAt;
    private LocalDateTime lastPingAt;
}
