package com.connecthub.websocket.dto;

import lombok.Data;

@Data
public class RoomMemberDto {
    private Integer userId;
    private String role;
}
