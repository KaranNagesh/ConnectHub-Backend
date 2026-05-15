package com.connecthub.room.dto;

public record UpdateRoomRequest(
        String name,
        String description,
        String avatarUrl
) {
}
