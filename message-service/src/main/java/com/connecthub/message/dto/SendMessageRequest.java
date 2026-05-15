package com.connecthub.message.dto;

import com.connecthub.message.entity.Message;

public record SendMessageRequest(
        String messageId,
        String roomId,
        Integer senderId,
        String content,
        String type,
        String mediaUrl,
        String thumbnailUrl,
        String replyToMessageId
) {
    public Message toEntity() {
        Message message = new Message();
        message.setMessageId(messageId);
        message.setRoomId(roomId);
        message.setSenderId(senderId);
        message.setContent(content);
        message.setType(type);
        message.setMediaUrl(mediaUrl);
        message.setThumbnailUrl(thumbnailUrl);
        message.setReplyToMessageId(replyToMessageId);
        return message;
    }
}
