package com.connecthub.room.service;
import com.connecthub.room.dto.CreateRoomRequest;
import com.connecthub.room.dto.UpdateRoomRequest;
import com.connecthub.room.entity.Room;
import com.connecthub.room.entity.RoomMember;
import com.connecthub.room.exception.*;
import com.connecthub.room.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * RoomService — Core Business Logic for Chat Rooms and Membership
 *
 * PURPOSE:
 *   This service manages everything related to rooms: creating DMs and group chats,
 *   managing membership (add/remove/role), tracking read state, and keeping rooms
 *   sorted by most recent activity. It is the authoritative source for room data,
 *   backed by MySQL via JPA repositories and a Redis member cache.
 *
 * TWO ROOM TYPES:
 *   - DM (Direct Message): a private 1-on-1 conversation between exactly two users.
 *     DMs are auto-named ("DM-{userId}"), always private, and limited to 2 members.
 *     If a DM already exists between two users, createRoom() returns the existing one
 *     instead of creating a duplicate.
 *   - GROUP: a multi-user chat with a name, optional description, configurable member
 *     capacity, and a designated creator who starts with the ADMIN role.
 *
 * SUBSCRIPTION TIER ENFORCEMENT:
 *   FREE users can create at most FREE_PLAN_MAX_GROUP_ROOMS (5) group chats. This
 *   limit is checked against the database count of groups created by the user. PRO
 *   and BUSINESS users have unlimited group rooms. DMs are never capped.
 *
 * MEMBER CACHING:
 *   Room member lists are cached in Redis via RoomCacheService using the key
 *   "room:members:{roomId}". This cache is populated on the first getMembers() call
 *   (cache miss) and invalidated on any mutation: addMember(), removeMember(), updateRole().
 *   Caching the member list avoids repeated DB queries for the high-frequency operation
 *   of "who is in this room?" which happens on every WebSocket message delivery.
 *
 * ROOM ORDERING:
 *   getRoomsByUser() sorts rooms by lastMessageAt (most recently active first), falling
 *   back to createdAt if no messages have been sent yet. This gives users the familiar
 *   chat-app sidebar experience where the most active conversations appear at the top.
 *
 * KAFKA EVENTS:
 *   When a room is created, a "room.created" event is published to Kafka so downstream
 *   services (e.g., notification-service) can react to new rooms being formed. Kafka
 *   failures are caught and logged without blocking room creation.
 *
 * LAST READ TRACKING:
 *   Each RoomMember row has a lastReadAt timestamp. updateLastRead() stamps the current
 *   time when a user views a room, allowing unread count queries to be scoped to messages
 *   sent after that timestamp.
 */
@SuppressWarnings("null")
@Service @RequiredArgsConstructor @Slf4j @Transactional
public class RoomService {
    private static final String ROOM_TYPE_DM = "DM";
    private static final String ROOM_TYPE_GROUP = "GROUP";

    /*
     * FREE plan cap on group rooms — aligned with billing tier feature matrix.
     * PRO/BUSINESS users bypass this check via isPaidTier().
     */
    private static final int FREE_PLAN_MAX_GROUP_ROOMS = 5;

    private final RoomRepository roomRepo;
    private final RoomMemberRepository memberRepo;
    private final RoomCacheService cacheService;
    private final org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * createRoom — creates a new DM or GROUP room with the requester as the admin.
     *
     * HOW IT WORKS:
     *   1. Normalize and validate the room type, member IDs, and request fields.
     *   2. For GROUP rooms on the FREE tier, count existing groups and enforce the cap.
     *   3. For DM rooms, check if a DM already exists between the two users — if so,
     *      return it immediately instead of creating a duplicate.
     *   4. Build and persist the Room entity, setting isPrivate=true for DMs automatically.
     *   5. Add the creator as ADMIN and all requested members as MEMBER.
     *   6. Publish a "room.created" Kafka event for downstream services.
     *
     * The maxMembers for DMs is always fixed at 2. For groups, maxMembers is set to
     * whichever is larger: the requested capacity or the actual group size + 1 (creator).
     *
     * @param creatorId        the user creating the room (becomes ADMIN)
     * @param req              the creation request with type, name, memberIds, etc.
     * @param subscriptionTier the creator's subscription tier for plan enforcement
     */
    public Room createRoom(int creatorId, CreateRoomRequest req, String subscriptionTier) {
        String roomType = normalizeType(req.getType());
        List<Integer> memberIds = normalizeMemberIds(req.getMemberIds(), creatorId);

        validateCreateRoomRequest(roomType, req.getName(), memberIds, req.getMaxMembers());

        if (ROOM_TYPE_GROUP.equals(roomType) && !isPaidTier(subscriptionTier)) {
            long existing = roomRepo.countByCreatedByIdAndType(creatorId, ROOM_TYPE_GROUP);
            if (existing >= FREE_PLAN_MAX_GROUP_ROOMS) {
                throw new ForbiddenException("Free plan allows up to " + FREE_PLAN_MAX_GROUP_ROOMS
                        + " group chats. Upgrade to PRO for unlimited groups.");
            }
        }

        if (ROOM_TYPE_DM.equals(roomType)) {
            return createDirectMessageRoom(creatorId, req, memberIds);
        }

        Room room = Room.builder().name(resolveRoomName(roomType, req.getName(), memberIds))
                .description(req.getDescription()).type(roomType).createdById(creatorId)
                .isPrivate(ROOM_TYPE_DM.equals(roomType) || req.isPrivate())
                .maxMembers(ROOM_TYPE_DM.equals(roomType) ? 2 : Math.max(req.getMaxMembers(), memberIds.size() + 1))
                .build();
        room = roomRepo.save(room);
        addMember(room.getRoomId(), creatorId, "ADMIN");
        for (int memberId : memberIds) {
            addMember(room.getRoomId(), memberId, "MEMBER");
        }
        log.info("Room created: {} type={} by user={}", room.getRoomId(), roomType, creatorId);

        try {
            java.util.Map<String, Object> event = new java.util.HashMap<>();
            event.put("roomId", room.getRoomId());
            event.put("creatorId", creatorId);
            java.util.List<Integer> allMemberIds = new java.util.ArrayList<>(memberIds);
            allMemberIds.add(creatorId);
            event.put("memberIds", allMemberIds);
            kafkaTemplate.send("room.created", objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Failed to publish room.created event", e);
        }

        return room;
    }

    private Room createDirectMessageRoom(int creatorId, CreateRoomRequest req, List<Integer> memberIds) {
        int otherUserId = memberIds.get(0);
        String dmKey = directMessageKey(creatorId, otherUserId);

        Optional<Room> keyedRoom = roomRepo.findByDmKey(dmKey);
        if (keyedRoom.isPresent()) {
            return keyedRoom.get();
        }

        List<Room> legacyRooms = roomRepo.findDirectMessageRooms(creatorId, otherUserId);
        if (!legacyRooms.isEmpty()) {
            Room room = legacyRooms.get(0);
            if (room.getDmKey() == null || room.getDmKey().isBlank()) {
                room.setDmKey(dmKey);
                return roomRepo.save(room);
            }
            return room;
        }

        Room room = Room.builder().name(resolveRoomName(ROOM_TYPE_DM, req.getName(), memberIds))
                .description(req.getDescription()).type(ROOM_TYPE_DM).createdById(creatorId)
                .dmKey(dmKey)
                .isPrivate(true)
                .maxMembers(2)
                .build();
        room = roomRepo.save(room);
        addMember(room.getRoomId(), creatorId, "ADMIN");
        addMember(room.getRoomId(), otherUserId, "MEMBER");
        log.info("Room created: {} type={} by user={}", room.getRoomId(), ROOM_TYPE_DM, creatorId);
        publishRoomCreatedEvent(room.getRoomId(), creatorId, memberIds);

        return room;
    }

    private void publishRoomCreatedEvent(String roomId, int creatorId, List<Integer> memberIds) {
        try {
            java.util.Map<String, Object> event = new java.util.HashMap<>();
            event.put("roomId", roomId);
            event.put("creatorId", creatorId);
            java.util.List<Integer> allMemberIds = new java.util.ArrayList<>(memberIds);
            allMemberIds.add(creatorId);
            event.put("memberIds", allMemberIds);
            kafkaTemplate.send("room.created", objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Failed to publish room.created event", e);
        }
    }

    /**
     * getRoom — fetches a single room by its ID.
     * Returns an Optional so callers can decide how to handle missing rooms.
     */
    @Transactional(readOnly = true)
    public Optional<Room> getRoom(String id) { return roomRepo.findByRoomId(id); }

    /**
     * getRoomsByUser — returns all rooms the user is a member of, sorted by activity.
     *
     * HOW IT WORKS:
     *   1. Fetch the list of room IDs the user belongs to from the member table.
     *   2. Load all those rooms in a single query via findAllById().
     *   3. Sort by lastMessageAt descending (most recently active first), using
     *      createdAt as a fallback for rooms where no messages have been sent yet.
     *   Rooms with a more recent lastMessageAt bubble to the top, matching the
     *   behavior users expect from chat apps.
     */
    @Transactional(readOnly = true)
    public List<Room> getRoomsByUser(int userId) {
        List<String> ids = memberRepo.findRoomIdsByUserId(userId);
        if (ids.isEmpty()) return List.of();
        return roomRepo.findAllById(ids).stream()
                .sorted(Comparator.comparing(
                        (Room room) -> room.getLastMessageAt() != null ? room.getLastMessageAt() : room.getCreatedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * updateRoom — updates mutable fields of a room (name, description, avatarUrl).
     * Only non-null fields in the updates object are applied (partial update pattern).
     * Room type, creator, and privacy settings cannot be changed after creation.
     */
    public Room updateRoom(String roomId, UpdateRoomRequest updates) {
        Room room = roomRepo.findByRoomId(roomId).orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        if (updates.name() != null) room.setName(updates.name());
        if (updates.description() != null) room.setDescription(updates.description());
        if (updates.avatarUrl() != null) room.setAvatarUrl(updates.avatarUrl());
        return roomRepo.save(room);
    }

    public Room updateRoomAvatar(String roomId, String avatarUrl) {
        Room room = roomRepo.findByRoomId(roomId).orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        room.setAvatarUrl(avatarUrl);
        return roomRepo.save(room);
    }

    @Transactional(readOnly = true)
    public void requireGroupAdmin(String roomId, int userId, String platformRole) {
        Room room = roomRepo.findByRoomId(roomId).orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        if (!ROOM_TYPE_GROUP.equals(room.getType())) {
            throw new BadRequestException("Only group rooms can have uploaded photos");
        }
        if ("ADMIN".equals(platformRole) || "PLATFORM_ADMIN".equals(platformRole)) return;
        if (room.getCreatedById() != null && room.getCreatedById() == userId) return;

        RoomMember member = memberRepo.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new ForbiddenException("Only group admins can update the room photo"));
        if (!"ADMIN".equalsIgnoreCase(member.getRole())) {
            throw new ForbiddenException("Only group admins can update the room photo");
        }
    }

    /**
     * deleteRoom — permanently deletes a room and all its member records.
     * Members are deleted first to avoid FK constraint violations.
     * This is a hard delete — the room and its membership history are unrecoverable.
     * Message history is cleaned up separately via message-service's clearHistory().
     */
    public void deleteRoom(String id) { memberRepo.deleteByRoomId(id); roomRepo.deleteById(id); }

    /**
     * addMember — adds a user to a room with a given role.
     *
     * HOW IT WORKS:
     *   - Prevents duplicate membership by checking existsByRoomIdAndUserId first.
     *   - Enforces the room's maxMembers capacity limit.
     *   - Persists a new RoomMember row with lastReadAt set to now (new members start
     *     "caught up" — they won't see old messages as unread on first join).
     *   - Invalidates the Redis member cache so the next getMembers() call hits the DB.
     */
    public RoomMember addMember(String roomId, int userId, String role) {
        if (memberRepo.existsByRoomIdAndUserId(roomId, userId)) throw new BadRequestException("Already a member");
        Room room = roomRepo.findByRoomId(roomId).orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        if (memberRepo.countByRoomId(roomId) >= room.getMaxMembers()) throw new BadRequestException("Room is full");
        RoomMember member = memberRepo.save(RoomMember.builder().roomId(roomId).userId(userId).role(role).lastReadAt(LocalDateTime.now()).build());
        cacheService.evict(roomId);
        return member;
    }

    /**
     * removeMember — removes a user from a room.
     * Deletes the RoomMember row and invalidates the cache so the member list
     * is refreshed on the next access.
     */
    public void removeMember(String roomId, int userId) {
        memberRepo.deleteByRoomIdAndUserId(roomId, userId);
        cacheService.evict(roomId);
    }

    /**
     * getMembers — returns the member list for a room, using Redis cache for performance.
     *
     * HOW IT WORKS:
     *   Checks the Redis cache first (key: "room:members:{roomId}"). On a cache hit,
     *   returns the cached list immediately without hitting the database. On a cache
     *   miss, loads from the DB, caches the result, and returns it. The cache is
     *   invalidated whenever membership changes (addMember, removeMember, updateRole).
     *   This read-through cache pattern is important because getMembers() is called
     *   for every single message delivery to determine who to notify.
     */
    @Transactional(readOnly = true) public List<RoomMember> getMembers(String roomId) {
        List<RoomMember> cached = cacheService.getCachedMembers(roomId);
        if (cached != null) {
            log.debug("Cache HIT room:members:{}", roomId);
            return cached;
        }
        log.debug("Cache MISS room:members:{} — loading from DB", roomId);
        List<RoomMember> members = memberRepo.findByRoomId(roomId);
        cacheService.cacheMembers(roomId, members);
        return members;
    }

    /**
     * updateRole — changes a member's role in a room (e.g., MEMBER → ADMIN).
     * Used when a room admin promotes or demotes another member.
     * The member cache is invalidated so subsequent calls reflect the new role.
     */
    public void updateRole(String roomId, int userId, String role) {
        RoomMember m = memberRepo.findByRoomIdAndUserId(roomId, userId).orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        m.setRole(role);
        memberRepo.save(m);
        cacheService.evict(roomId);
    }

    /**
     * mute — toggles the muted flag on a room member.
     * When muted=true, the frontend suppresses notifications from this room for
     * that member. The mute state is stored in the RoomMember row so it persists
     * across sessions and device reconnects.
     */
    public void mute(String roomId, int userId, boolean muted) {
        RoomMember m = memberRepo.findByRoomIdAndUserId(roomId, userId).orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        m.setMuted(muted); memberRepo.save(m);
    }

    /**
     * updateLastRead — stamps the current time as the user's lastReadAt for this room.
     * Called when the user sends a /chat.read STOMP frame (they opened the room).
     * The timestamp is used by unreadCount queries to calculate how many messages
     * arrived after the user last viewed the room.
     */
    public void updateLastRead(String roomId, int userId) {
        memberRepo.findByRoomIdAndUserId(roomId, userId).ifPresent(m -> { m.setLastReadAt(LocalDateTime.now()); memberRepo.save(m); });
    }

    /**
     * updateLastMessageAt — updates the room's activity timestamp to now.
     * Called by websocket-service via Feign after each new message.
     * This timestamp drives the sidebar sort order in getRoomsByUser().
     */
    public void updateLastMessageAt(String roomId) {
        roomRepo.findByRoomId(roomId).ifPresent(r -> { r.setLastMessageAt(LocalDateTime.now()); roomRepo.save(r); });
    }

    /**
     * pinMessage — sets the pinned message ID on a room.
     * The pinned message is displayed prominently in the chat header. Only one
     * message can be pinned at a time; setting a new one replaces the previous pin.
     */
    public void pinMessage(String roomId, String msgId) {
        Room r = roomRepo.findByRoomId(roomId).orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        r.setPinnedMessageId(msgId); roomRepo.save(r);
    }

    /** isMember — quick membership check used by authorization guards in the resource layer. */
    @Transactional(readOnly = true) public boolean isMember(String roomId, int userId) { return memberRepo.existsByRoomIdAndUserId(roomId, userId); }

    /** getAllRooms — admin-only endpoint that returns all rooms in the system. */
    @Transactional(readOnly = true) public List<Room> getAllRooms() { return roomRepo.findAll(); }

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> adminAnalytics() {
        return java.util.Map.of(
                "totalRooms", roomRepo.count(),
                "groupRooms", roomRepo.countByType(ROOM_TYPE_GROUP),
                "directRooms", roomRepo.countByType(ROOM_TYPE_DM),
                "activeRooms30d", roomRepo.countByLastMessageAtAfter(LocalDateTime.now().minusDays(30))
        );
    }

    /**
     * normalizeType — validates and uppercases the room type from the request.
     * Only "DM" and "GROUP" are supported. Throws BadRequestException for unknown types.
     */
    private String normalizeType(String type) {
        if (type == null || type.isBlank()) throw new BadRequestException("Room type is required");
        String normalizedType = type.trim().toUpperCase(Locale.ROOT);
        if (!ROOM_TYPE_DM.equals(normalizedType) && !ROOM_TYPE_GROUP.equals(normalizedType)) {
            throw new BadRequestException("Unsupported room type");
        }
        return normalizedType;
    }

    /**
     * normalizeMemberIds — cleans and deduplicates the member ID list from the request.
     * Removes nulls and filters out the creator's own ID (they are added as ADMIN separately).
     * Uses distinct() to prevent duplicate membership entries if the same ID appears twice.
     */
    private List<Integer> normalizeMemberIds(List<Integer> memberIds, int creatorId) {
        if (memberIds == null) return List.of();
        return memberIds.stream()
                .filter(memberId -> memberId != null && memberId != creatorId)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * validateCreateRoomRequest — validates type-specific constraints before creating a room.
     * - DMs must have exactly one other member (not 0, not 2+).
     * - Groups must have at least one other member and a non-blank name.
     * - maxMembers must be at least the total initial group size (members + creator).
     */
    private void validateCreateRoomRequest(String roomType, String roomName, List<Integer> memberIds, int maxMembers) {
        if (ROOM_TYPE_DM.equals(roomType) && memberIds.size() != 1) {
            throw new BadRequestException("DM requires exactly one other member");
        }
        if (ROOM_TYPE_GROUP.equals(roomType) && memberIds.isEmpty()) {
            throw new BadRequestException("Group chat requires at least one other member");
        }
        if (ROOM_TYPE_GROUP.equals(roomType) && (roomName == null || roomName.isBlank())) {
            throw new BadRequestException("Room name is required");
        }
        if (ROOM_TYPE_GROUP.equals(roomType) && maxMembers < memberIds.size() + 1) {
            throw new BadRequestException("maxMembers must be at least the total group size");
        }
    }

    /**
     * isPaidTier — returns true if the subscription tier is PRO or BUSINESS.
     * Used to gate the group room creation limit — paid users bypass the FREE cap.
     */
    private boolean isPaidTier(String subscriptionTier) {
        if (subscriptionTier == null || subscriptionTier.isBlank()) return false;
        String t = subscriptionTier.trim().toUpperCase();
        return "PRO".equals(t) || "BUSINESS".equals(t);
    }

    /**
     * resolveRoomName — determines the display name for a new room.
     * DMs get an auto-generated name "DM-{otherUserId}" if no custom name is provided.
     * Groups always require a name (validated earlier), so we just trim it here.
     */
    private String resolveRoomName(String roomType, String roomName, List<Integer> memberIds) {
        if (ROOM_TYPE_DM.equals(roomType)) {
            return roomName == null || roomName.isBlank() ? "DM-" + memberIds.get(0) : roomName.trim();
        }
        return roomName.trim();
    }

    private String directMessageKey(int firstUserId, int secondUserId) {
        int low = Math.min(firstUserId, secondUserId);
        int high = Math.max(firstUserId, secondUserId);
        return low + ":" + high;
    }
}
