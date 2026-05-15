package com.connecthub.presence.service;

import com.connecthub.presence.dto.UserPresence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock SetOperations<String, String> setOps;

    PresenceService svc;
    ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        svc = new PresenceService(redis);
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    // ── setOnline ────────────────────────────────────────────────────────────

    @Test
    void setOnline_storesPresenceInRedis() {
        svc.setOnline(1, "WEB", "sess-1");
        verify(valueOps).set(eq("presence:1"), anyString(), any());
        verify(setOps).add("presence:online", "1");
    }

    @Test
    void setOnline_storedJsonContainsCorrectStatus() throws Exception {
        svc.setOnline(5, "MOBILE", "s99");
        ArgumentCaptor<String> jsonCap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("presence:5"), jsonCap.capture(), any());
        UserPresence stored = mapper.readValue(jsonCap.getValue(), UserPresence.class);
        assertEquals(5, stored.getUserId());
        assertEquals("ONLINE", stored.getStatus());
        assertEquals("MOBILE", stored.getDeviceType());
    }

    // ── setOffline ───────────────────────────────────────────────────────────

    @Test
    void setOffline_removesFromRedis() {
        svc.setOffline(1);
        verify(redis).delete("presence:1");
        verify(setOps).remove("presence:online", "1");
    }

    // ── get ──────────────────────────────────────────────────────────────────

    @Test
    void get_existingUser_returnsPresence() throws Exception {
        UserPresence p = UserPresence.builder().userId(3).status("ONLINE").build();
        when(valueOps.get("presence:3")).thenReturn(mapper.writeValueAsString(p));

        assertTrue(svc.get(3).isPresent());
        assertEquals("ONLINE", svc.get(3).get().getStatus());
    }

    @Test
    void get_missingUser_returnsEmpty() {
        when(valueOps.get("presence:99")).thenReturn(null);
        assertTrue(svc.get(99).isEmpty());
    }

    @Test
    void get_malformedJson_returnsEmpty() {
        when(valueOps.get("presence:2")).thenReturn("not-valid-json{{{");
        assertTrue(svc.get(2).isEmpty());
    }

    // ── getBulk ──────────────────────────────────────────────────────────────

    @Test
    void getBulk_returnsOnlyNonNullEntries() throws Exception {
        UserPresence p1 = UserPresence.builder().userId(1).status("ONLINE").build();
        UserPresence p2 = UserPresence.builder().userId(2).status("AWAY").build();
        when(valueOps.multiGet(any())).thenReturn(java.util.Arrays.asList(mapper.writeValueAsString(p1), null, mapper.writeValueAsString(p2)));

        List<UserPresence> result = svc.getBulk(List.of(1, 3, 2));
        assertEquals(2, result.size());
    }

    @Test
    void getBulk_emptyIds_returnsEmpty() {
        when(valueOps.multiGet(any())).thenReturn(null);
        assertTrue(svc.getBulk(List.of(1)).isEmpty());
    }

    // ── ping ─────────────────────────────────────────────────────────────────

    @Test
    void ping_updatesLastPingAt() throws Exception {
        LocalDateTime before = LocalDateTime.now().minusMinutes(5);
        UserPresence p = UserPresence.builder().userId(1).status("ONLINE").lastPingAt(before).build();
        when(valueOps.get("presence:1")).thenReturn(mapper.writeValueAsString(p));

        svc.ping(1);

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("presence:1"), cap.capture(), any());
        UserPresence updated = mapper.readValue(cap.getValue(), UserPresence.class);
        assertTrue(updated.getLastPingAt().isAfter(before));
    }

    @Test
    void ping_missingUser_noWrite() {
        when(valueOps.get("presence:99")).thenReturn(null);
        svc.ping(99);
        verify(valueOps, never()).set(eq("presence:99"), anyString(), any());
    }

    // ── updateStatus ─────────────────────────────────────────────────────────

    @Test
    void updateStatus_changesStatusField() throws Exception {
        UserPresence p = UserPresence.builder().userId(1).status("ONLINE").build();
        when(valueOps.get("presence:1")).thenReturn(mapper.writeValueAsString(p));

        svc.updateStatus(1, "AWAY", "in a meeting");

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("presence:1"), cap.capture(), any());
        UserPresence updated = mapper.readValue(cap.getValue(), UserPresence.class);
        assertEquals("AWAY", updated.getStatus());
        assertEquals("in a meeting", updated.getCustomMessage());
    }

    // ── isOnline ─────────────────────────────────────────────────────────────

    @Test
    void isOnline_trueWhenInSet() {
        when(setOps.isMember("presence:online", "1")).thenReturn(true);
        assertTrue(svc.isOnline(1));
    }

    @Test
    void isOnline_falseWhenNotInSet() {
        when(setOps.isMember("presence:online", "99")).thenReturn(false);
        assertFalse(svc.isOnline(99));
    }

    // ── onlineCount ──────────────────────────────────────────────────────────

    @Test
    void onlineCount_returnsSetSize() {
        when(setOps.size("presence:online")).thenReturn(42L);
        assertEquals(42, svc.onlineCount());
    }

    @Test
    void onlineCount_nullFromRedis_returnsZero() {
        when(setOps.size("presence:online")).thenReturn(null);
        assertEquals(0, svc.onlineCount());
    }

    // ── cleanStale ───────────────────────────────────────────────────────────

    @Test
    void cleanStale_removesStaleUsers() throws Exception {
        UserPresence stale = UserPresence.builder().userId(5).status("ONLINE")
            .lastPingAt(LocalDateTime.now().minusMinutes(5)).build();
        when(setOps.members("presence:online")).thenReturn(Set.of("5"));
        when(valueOps.get("presence:5")).thenReturn(mapper.writeValueAsString(stale));

        svc.cleanStale();

        verify(redis).delete("presence:5");
        verify(setOps).remove("presence:online", "5");
    }

    @Test
    void cleanStale_recentUser_notRemoved() throws Exception {
        UserPresence fresh = UserPresence.builder().userId(6).status("ONLINE")
            .lastPingAt(LocalDateTime.now()).build();
        when(setOps.members("presence:online")).thenReturn(Set.of("6"));
        when(valueOps.get("presence:6")).thenReturn(mapper.writeValueAsString(fresh));

        svc.cleanStale();

        verify(redis, never()).delete("presence:6");
    }

    @Test
    void cleanStale_nullMembersSet_noOp() {
        when(setOps.members("presence:online")).thenReturn(null);
        assertDoesNotThrow(() -> svc.cleanStale());
    }
}
