package com.connecthub.websocket.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data @NoArgsConstructor @AllArgsConstructor
public class ReadReceiptPayload {
    private Integer readerId;
    private String roomId;
    private String upToMessageId;
}
