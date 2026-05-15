package com.connecthub.websocket.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessagePayload {
    private String messageId;
    private Integer senderId;
    @JsonAlias("senderName")
    private String senderUsername;
    private String roomId;
    private String content;
    private String type;
    @JsonAlias({"fileUrl", "url"})
    private String mediaUrl;
    private String thumbnailUrl;
    @JsonAlias("replyToId")
    private String replyToMessageId;
    private String deliveryStatus;
    private long timestamp;
    /**
     * ISO-8601 string assigned by the DB (@CreationTimestamp) once the message
     * is synchronously persisted via message-service. When present, clients use
     * this for day-grouping and ordering so live bubbles match reloaded history.
     */
    private String sentAt;
    /** Echoed from JWT for Kafka tier enforcement (FREE vs PRO rate limits). */
    private String subscriptionTier;
}
