package com.connecthub.notification.dto;

import com.connecthub.notification.entity.Notification;

public record SendNotificationRequest(
        Integer recipientId,
        Integer actorId,
        String type,
        String title,
        String message,
        String roomId,
        String messageId
) {
    public Notification toEntity() {
        Notification notification = new Notification();
        notification.setRecipientId(recipientId);
        notification.setActorId(actorId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setRoomId(roomId);
        notification.setMessageId(messageId);
        notification.setRead(false);
        return notification;
    }
}
