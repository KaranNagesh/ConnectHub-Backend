package com.connecthub.websocket.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

@FeignClient(name = "room-service")
public interface RoomServiceClient {

    @PutMapping("/api/v1/rooms/{roomId}")
    void updateRoom(@PathVariable("roomId") String roomId,
                    @RequestBody Map<String, Object> body,
                    @RequestHeader("X-User-Id") String userId);

    @GetMapping("/api/v1/rooms/{roomId}/members")
    java.util.List<com.connecthub.websocket.dto.RoomMemberDto> getRoomMembers(
            @PathVariable("roomId") String roomId,
            @RequestHeader("X-User-Id") String userId
    );

    @GetMapping("/api/v1/rooms/{roomId}")
    com.connecthub.websocket.dto.RoomDto getRoom(
            @PathVariable("roomId") String roomId,
            @RequestHeader("X-User-Id") String userId
    );

    @GetMapping("/api/v1/rooms/{roomId}/members/{uid}/check")
    Boolean isMember(@PathVariable("roomId") String roomId,
                     @PathVariable("uid") String uid,
                     @RequestHeader("X-User-Id") String userId);

    @PutMapping("/api/v1/rooms/{roomId}/timestamp")
    void updateLastMessageAt(@PathVariable("roomId") String roomId,
                             @RequestHeader("X-User-Id") String userId);

    @PutMapping("/api/v1/rooms/{roomId}/read/{uid}")
    void updateLastRead(@PathVariable("roomId") String roomId,
                        @PathVariable("uid") String uid,
                        @RequestHeader("X-User-Id") String userId);
}
