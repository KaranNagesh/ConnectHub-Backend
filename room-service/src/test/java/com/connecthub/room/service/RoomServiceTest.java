package com.connecthub.room.service;

import com.connecthub.room.dto.CreateRoomRequest;
import com.connecthub.room.dto.UpdateRoomRequest;
import com.connecthub.room.entity.*;
import com.connecthub.room.exception.*;
import com.connecthub.room.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock RoomRepository roomRepo;
    @Mock RoomMemberRepository memberRepo;
    @Mock RoomCacheService cacheService;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    @Mock ObjectMapper objectMapper;
    @InjectMocks RoomService svc;

    // ── createRoom ───────────────────────────────────────────────────────────

    @Test
    void createGroup_success() {
        CreateRoomRequest req = new CreateRoomRequest();
        req.setName("Dev Team"); req.setType("GROUP"); req.setMemberIds(List.of(2, 3));
        Room saved = Room.builder().roomId("r1").name("Dev Team").type("GROUP").createdById(1).maxMembers(500).build();
        when(roomRepo.save(any())).thenReturn(saved);
        when(roomRepo.countByCreatedByIdAndType(1, "GROUP")).thenReturn(0L);
        when(memberRepo.existsByRoomIdAndUserId(any(), anyInt())).thenReturn(false);
        when(memberRepo.countByRoomId(any())).thenReturn(0);
        when(roomRepo.findByRoomId("r1")).thenReturn(Optional.of(saved));
        when(memberRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Room result = svc.createRoom(1, req, "FREE");

        assertEquals("GROUP", result.getType());
        // creator + 2 members = 3 saves
        verify(memberRepo, times(3)).save(any());
    }

    @Test
    void createDM_success() {
        CreateRoomRequest req = new CreateRoomRequest();
        req.setType("DM"); req.setMemberIds(List.of(2));
        Room saved = Room.builder().roomId("dm1").type("DM").createdById(1).maxMembers(2).build();
        when(roomRepo.findByDmKey("1:2")).thenReturn(Optional.empty());
        when(roomRepo.findDirectMessageRooms(1, 2)).thenReturn(List.of());
        when(roomRepo.save(any())).thenReturn(saved);
        when(memberRepo.existsByRoomIdAndUserId(any(), anyInt())).thenReturn(false);
        when(memberRepo.countByRoomId(any())).thenReturn(0);
        when(roomRepo.findByRoomId("dm1")).thenReturn(Optional.of(saved));
        when(memberRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Room result = svc.createRoom(1, req, "FREE");
        assertEquals("DM", result.getType());
    }

    @Test
    void createGroup_freeTierAtCap_throws() {
        CreateRoomRequest req = new CreateRoomRequest();
        req.setName("Sixth"); req.setType("GROUP"); req.setMemberIds(List.of(2));
        when(roomRepo.countByCreatedByIdAndType(1, "GROUP")).thenReturn(5L);

        assertThrows(ForbiddenException.class, () -> svc.createRoom(1, req, "FREE"));
    }

    @Test
    void createGroup_proTierUnlimited_ok() {
        CreateRoomRequest req = new CreateRoomRequest();
        req.setName("Another"); req.setType("GROUP"); req.setMemberIds(List.of(2));
        Room saved = Room.builder().roomId("r2").name("Another").type("GROUP").createdById(1).maxMembers(500).build();
        when(roomRepo.save(any())).thenReturn(saved);
        when(memberRepo.existsByRoomIdAndUserId(any(), anyInt())).thenReturn(false);
        when(memberRepo.countByRoomId(any())).thenReturn(0);
        when(roomRepo.findByRoomId("r2")).thenReturn(Optional.of(saved));
        when(memberRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Room result = svc.createRoom(1, req, "PRO");
        assertEquals("GROUP", result.getType());
    }

    @Test
    void createDM_wrongMemberCount_throws() {
        CreateRoomRequest req = new CreateRoomRequest();
        req.setType("DM"); req.setMemberIds(List.of(2, 3));
        assertThrows(BadRequestException.class, () -> svc.createRoom(1, req, "FREE"));
    }

    @Test
    void createDM_noMembers_throws() {
        CreateRoomRequest req = new CreateRoomRequest();
        req.setType("DM"); req.setMemberIds(null);
        assertThrows(BadRequestException.class, () -> svc.createRoom(1, req, "FREE"));
    }

    @Test
    void createGroup_withoutOtherMembers_throws() {
        CreateRoomRequest req = new CreateRoomRequest();
        req.setName("Solo Group");
        req.setType("GROUP");
        req.setMemberIds(List.of());

        assertThrows(BadRequestException.class, () -> svc.createRoom(1, req, "FREE"));
    }

    @Test
    void createDM_returnsExistingRoomByDmKeyWhenPresent() {
        CreateRoomRequest req = new CreateRoomRequest();
        req.setType("dm");
        req.setMemberIds(List.of(2));
        Room existing = Room.builder().roomId("dm1").type("DM").dmKey("1:2").createdById(1).maxMembers(2).build();

        when(roomRepo.findByDmKey("1:2")).thenReturn(Optional.of(existing));

        Room result = svc.createRoom(1, req, "FREE");

        assertEquals("dm1", result.getRoomId());
        verify(roomRepo, never()).save(any());
        verify(memberRepo, never()).save(any());
    }

    @Test
    void createDM_returnsLegacyRoomAndStoresDmKeyWhenPresent() {
        CreateRoomRequest req = new CreateRoomRequest();
        req.setType("dm");
        req.setMemberIds(List.of(2));
        Room existing = Room.builder().roomId("dm1").type("DM").createdById(1).maxMembers(2).build();

        when(roomRepo.findByDmKey("1:2")).thenReturn(Optional.empty());
        when(roomRepo.findDirectMessageRooms(1, 2)).thenReturn(List.of(existing));
        when(roomRepo.save(existing)).thenReturn(existing);

        Room result = svc.createRoom(1, req, "FREE");

        assertEquals("dm1", result.getRoomId());
        assertEquals("1:2", result.getDmKey());
        verify(memberRepo, never()).save(any());
    }

    // ── getRoom ──────────────────────────────────────────────────────────────

    @Test
    void getRoom_found() {
        Room room = Room.builder().roomId("r1").name("Test").build();
        when(roomRepo.findByRoomId("r1")).thenReturn(Optional.of(room));
        Optional<Room> result = svc.getRoom("r1");
        assertTrue(result.isPresent());
        assertEquals("Test", result.get().getName());
    }

    @Test
    void getRoom_notFound_returnsEmpty() {
        when(roomRepo.findByRoomId("missing")).thenReturn(Optional.empty());
        assertTrue(svc.getRoom("missing").isEmpty());
    }

    // ── getRoomsByUser ───────────────────────────────────────────────────────

    @Test
    void getRoomsByUser_returnsMappedRooms() {
        Room r = Room.builder().roomId("r1").build();
        when(memberRepo.findRoomIdsByUserId(1)).thenReturn(List.of("r1"));
        when(roomRepo.findAllById(List.of("r1"))).thenReturn(List.of(r));

        List<Room> result = svc.getRoomsByUser(1);
        assertEquals(1, result.size());
    }

    @Test
    void getRoomsByUser_noRooms_returnsEmpty() {
        when(memberRepo.findRoomIdsByUserId(99)).thenReturn(List.of());
        assertTrue(svc.getRoomsByUser(99).isEmpty());
    }

    // ── updateRoom ───────────────────────────────────────────────────────────

    @Test
    void updateRoom_changesName() {
        Room existing = Room.builder().roomId("r1").name("Old").build();
        UpdateRoomRequest updates = new UpdateRoomRequest("New", null, null);
        when(roomRepo.findByRoomId("r1")).thenReturn(Optional.of(existing));
        when(roomRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Room result = svc.updateRoom("r1", updates);
        assertEquals("New", result.getName());
    }

    @Test
    void updateRoom_notFound_throws() {
        when(roomRepo.findByRoomId("x")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> svc.updateRoom("x", new UpdateRoomRequest("New", null, null)));
    }

    // ── deleteRoom ───────────────────────────────────────────────────────────

    @Test
    void deleteRoom_removesAllMembers() {
        svc.deleteRoom("r1");
        verify(memberRepo).deleteByRoomId("r1");
        verify(roomRepo).deleteById("r1");
    }

    // ── addMember ────────────────────────────────────────────────────────────

    @Test
    void addMember_success() {
        Room room = Room.builder().roomId("r1").maxMembers(500).build();
        when(memberRepo.existsByRoomIdAndUserId("r1", 5)).thenReturn(false);
        when(memberRepo.countByRoomId("r1")).thenReturn(10);
        when(roomRepo.findByRoomId("r1")).thenReturn(Optional.of(room));
        when(memberRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        RoomMember m = svc.addMember("r1", 5, "MEMBER");
        assertEquals(5, m.getUserId());
        assertEquals("MEMBER", m.getRole());
    }

    @Test
    void addMember_alreadyMember_throws() {
        when(memberRepo.existsByRoomIdAndUserId("r1", 5)).thenReturn(true);
        assertThrows(BadRequestException.class, () -> svc.addMember("r1", 5, "MEMBER"));
    }

    @Test
    void addMember_roomFull_throws() {
        when(memberRepo.existsByRoomIdAndUserId("r1", 5)).thenReturn(false);
        when(memberRepo.countByRoomId("r1")).thenReturn(500);
        when(roomRepo.findByRoomId("r1")).thenReturn(Optional.of(Room.builder().roomId("r1").maxMembers(500).build()));
        assertThrows(BadRequestException.class, () -> svc.addMember("r1", 5, "MEMBER"));
    }

    // ── removeMember ─────────────────────────────────────────────────────────

    @Test
    void removeMember_callsRepository() {
        svc.removeMember("r1", 5);
        verify(memberRepo).deleteByRoomIdAndUserId("r1", 5);
    }

    // ── getMembers ───────────────────────────────────────────────────────────

    @Test
    void getMembers_returnsList() {
        List<RoomMember> members = List.of(
            RoomMember.builder().roomId("r1").userId(1).role("ADMIN").build(),
            RoomMember.builder().roomId("r1").userId(2).role("MEMBER").build()
        );
        when(cacheService.getCachedMembers("r1")).thenReturn(null); // cache miss
        when(memberRepo.findByRoomId("r1")).thenReturn(members);
        assertEquals(2, svc.getMembers("r1").size());
    }

    // ── updateRole ───────────────────────────────────────────────────────────

    @Test
    void updateRole_changesToModerator() {
        RoomMember m = RoomMember.builder().roomId("r1").userId(2).role("MEMBER").build();
        when(memberRepo.findByRoomIdAndUserId("r1", 2)).thenReturn(Optional.of(m));
        when(memberRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.updateRole("r1", 2, "MODERATOR");
        assertEquals("MODERATOR", m.getRole());
    }

    @Test
    void updateRole_memberNotFound_throws() {
        when(memberRepo.findByRoomIdAndUserId("r1", 99)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> svc.updateRole("r1", 99, "ADMIN"));
    }

    // ── mute ─────────────────────────────────────────────────────────────────

    @Test
    void mute_setsMutedTrue() {
        RoomMember m = RoomMember.builder().roomId("r1").userId(2).isMuted(false).build();
        when(memberRepo.findByRoomIdAndUserId("r1", 2)).thenReturn(Optional.of(m));
        when(memberRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.mute("r1", 2, true);
        assertTrue(m.isMuted());
    }

    @Test
    void unmute_setsMutedFalse() {
        RoomMember m = RoomMember.builder().roomId("r1").userId(2).isMuted(true).build();
        when(memberRepo.findByRoomIdAndUserId("r1", 2)).thenReturn(Optional.of(m));
        when(memberRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.mute("r1", 2, false);
        assertFalse(m.isMuted());
    }

    // ── pinMessage ───────────────────────────────────────────────────────────

    @Test
    void pinMessage_setsPinnedMessageId() {
        Room room = Room.builder().roomId("r1").build();
        when(roomRepo.findByRoomId("r1")).thenReturn(Optional.of(room));
        when(roomRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.pinMessage("r1", "m42");
        assertEquals("m42", room.getPinnedMessageId());
    }

    // ── isMember ─────────────────────────────────────────────────────────────

    @Test
    void isMember_trueWhenExists() {
        when(memberRepo.existsByRoomIdAndUserId("r1", 1)).thenReturn(true);
        assertTrue(svc.isMember("r1", 1));
    }

    @Test
    void isMember_falseWhenNotExists() {
        when(memberRepo.existsByRoomIdAndUserId("r1", 99)).thenReturn(false);
        assertFalse(svc.isMember("r1", 99));
    }
}
