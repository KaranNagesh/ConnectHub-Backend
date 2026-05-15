package com.connecthub.room.resource;

import com.connecthub.room.dto.CreateRoomRequest;
import com.connecthub.room.dto.UpdateRoomRequest;
import com.connecthub.room.entity.Room;
import com.connecthub.room.entity.RoomMember;
import com.connecthub.room.service.RoomAvatarService;
import com.connecthub.room.service.RoomService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomResourceTest {

    @Mock
    private RoomService svc;

    @Mock
    private RoomAvatarService avatarService;

    @InjectMocks
    private RoomResource res;

    @Test
    void create() {
        CreateRoomRequest req = new CreateRoomRequest();
        when(svc.createRoom(1, req, "PRO")).thenReturn(new Room());
        assertEquals(HttpStatus.CREATED, res.create(1, "PRO", req).getStatusCode());
    }

    @Test
    void get_found() {
        when(svc.getRoom("1")).thenReturn(Optional.of(new Room()));
        assertEquals(HttpStatus.OK, res.get("1").getStatusCode());
    }

    @Test
    void get_notFound() {
        when(svc.getRoom("2")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, res.get("2").getStatusCode());
    }

    @Test
    void byUser() {
        when(svc.getRoomsByUser(1)).thenReturn(List.of(new Room()));
        assertEquals(HttpStatus.OK, res.byUser(1).getStatusCode());
    }

    @Test
    void update() {
        Room r = new Room();
        UpdateRoomRequest request = new UpdateRoomRequest("Room", "Desc", "avatar.png");
        when(svc.updateRoom("1", request)).thenReturn(r);
        assertEquals(HttpStatus.OK, res.update("1", request).getStatusCode());
    }

    @Test
    void uploadAvatar_requiresAdminSavesAvatarAndUpdatesRoom() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "png".getBytes());
        Room room = new Room();
        when(avatarService.saveAvatar(file, "1")).thenReturn("https://cdn/avatar.png");
        when(svc.updateRoomAvatar("1", "https://cdn/avatar.png")).thenReturn(room);

        assertEquals(HttpStatus.OK, res.uploadAvatar("1", 2, "ADMIN", file).getStatusCode());
        verify(svc).requireGroupAdmin("1", 2, "ADMIN");
        verify(svc).updateRoomAvatar("1", "https://cdn/avatar.png");
    }

    @Test
    void avatar_returnsInlineCachedResource() throws Exception {
        ByteArrayResource resource = new ByteArrayResource("png".getBytes());
        when(avatarService.loadAvatar("avatar.png"))
                .thenReturn(new RoomAvatarService.AvatarResource(resource, MediaType.IMAGE_PNG));

        var response = res.avatar("avatar.png");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals(resource, response.getBody());
    }

    @Test
    void delete() {
        assertEquals(HttpStatus.NO_CONTENT, res.delete("1").getStatusCode());
        verify(svc).deleteRoom("1");
    }

    @Test
    void addMember() {
        when(svc.addMember("1", 2, "ADMIN")).thenReturn(new RoomMember());
        assertEquals(HttpStatus.CREATED, res.addMember("1", 2, "ADMIN").getStatusCode());
    }

    @Test
    void removeMember() {
        assertEquals(HttpStatus.NO_CONTENT, res.removeMember("1", 2).getStatusCode());
        verify(svc).removeMember("1", 2);
    }

    @Test
    void members() {
        when(svc.getMembers("1")).thenReturn(List.of(new RoomMember()));
        assertEquals(HttpStatus.OK, res.members("1").getStatusCode());
    }

    @Test
    void role() {
        assertEquals(HttpStatus.NO_CONTENT, res.role("1", 2, Map.of("role", "ADMIN")).getStatusCode());
        verify(svc).updateRole("1", 2, "ADMIN");
    }

    @Test
    void mute() {
        assertEquals(HttpStatus.NO_CONTENT, res.mute("1", 2, true).getStatusCode());
        verify(svc).mute("1", 2, true);
    }

    @Test
    void read() {
        assertEquals(HttpStatus.NO_CONTENT, res.read("1", 2).getStatusCode());
        verify(svc).updateLastRead("1", 2);
    }

    @Test
    void pin() {
        assertEquals(HttpStatus.NO_CONTENT, res.pin("1", "m1").getStatusCode());
        verify(svc).pinMessage("1", "m1");
    }

    @Test
    void unpin() {
        assertEquals(HttpStatus.NO_CONTENT, res.unpin("1").getStatusCode());
        verify(svc).pinMessage("1", null);
    }

    @Test
    void check() {
        when(svc.isMember("1", 2)).thenReturn(true);
        assertEquals(HttpStatus.OK, res.check("1", 2).getStatusCode());
    }

    @Test
    void all() {
        when(svc.getAllRooms()).thenReturn(List.of(new Room()));
        assertEquals(HttpStatus.OK, res.all().getStatusCode());
    }

    @Test
    void updateTimestamp() {
        assertEquals(HttpStatus.NO_CONTENT, res.updateTimestamp("1").getStatusCode());
        verify(svc).updateLastMessageAt("1");
    }

    @Test
    void adminAnalytics_forbiddenForNonAdmin() {
        assertEquals(HttpStatus.FORBIDDEN, res.adminAnalytics("USER").getStatusCode());
        verify(svc, never()).adminAnalytics();
    }

    @Test
    void adminAnalytics_returnsAnalyticsForAdmin() {
        Map<String, Object> analytics = new HashMap<>();
        analytics.put("totalRooms", 2L);
        when(svc.adminAnalytics()).thenReturn(analytics);

        assertEquals(HttpStatus.OK, res.adminAnalytics("PLATFORM_ADMIN").getStatusCode());
    }
}
