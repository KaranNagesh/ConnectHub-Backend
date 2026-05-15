package com.connecthub.websocket.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReactionPayload {
    @JsonAlias("userId")
    private Integer senderId;
    private String messageId;
    private String roomId;
    private String emoji;
    @JsonAlias("type")
    private String action;
}
