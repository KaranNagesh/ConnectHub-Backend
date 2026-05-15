package com.connecthub.websocket.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data @NoArgsConstructor @AllArgsConstructor
public class MessageDeletePayload {
    private Integer deleterId;
    private String messageId;
    private String roomId;
}
