package com.connecthub.websocket.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data @NoArgsConstructor @AllArgsConstructor
public class TypingIndicatorPayload {
    @JsonAlias("userId")
    private Integer senderId;
    @JsonAlias("username")
    private String senderUsername;
    private String roomId;
    private boolean typing;
}
