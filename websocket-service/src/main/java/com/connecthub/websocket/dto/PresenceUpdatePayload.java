package com.connecthub.websocket.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data @NoArgsConstructor @AllArgsConstructor
public class PresenceUpdatePayload {
    private Integer userId;
    private String username;
    private String status;
    private String customMessage;
}
