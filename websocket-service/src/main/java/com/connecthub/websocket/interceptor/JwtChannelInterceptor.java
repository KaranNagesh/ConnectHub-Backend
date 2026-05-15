package com.connecthub.websocket.interceptor;

import com.connecthub.websocket.client.RoomServiceClient;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.Principal;
import java.util.Base64;

/**
 * JwtChannelInterceptor — STOMP WebSocket Authentication via JWT
 *
 * PURPOSE:
 *   Validates the JWT token on every WebSocket CONNECT frame and sets the
 *   authenticated user principal on the STOMP session. This is the WebSocket
 *   equivalent of the HTTP JwtAuthenticationFilter in the gateway — it secures
 *   the STOMP layer so all subsequent message routing uses a verified identity.
 *
 * HOW IT WORKS:
 *   Spring's STOMP message broker passes all inbound channel messages through
 *   registered ChannelInterceptors before routing. This interceptor runs on every
 *   message but only acts on STOMP CONNECT frames (the handshake step). For
 *   CONNECT frames:
 *   1. Extract the "Authorization: Bearer {token}" header from the STOMP native headers.
 *   2. Verify and parse the JWT using the same HMAC-SHA256 secret as auth-service.
 *   3. Extract userId (subject), username, and subscriptionTier claims.
 *   4. Set a StompPrincipal on the accessor — this becomes the session's user principal.
 *   5. All subsequent STOMP frames from this session inherit this principal.
 *
 * WHY THE PRINCIPAL MATTERS:
 *   Spring's user-destination routing (/user/{userId}/queue/...) relies on the session
 *   having a named Principal. The name returned by StompPrincipal.getName() is the
 *   userId string. When DeliveryService calls convertAndSendToUser(userId, ...), Spring
 *   looks up which WebSocket sessions have a principal with that name and delivers to them.
 *   Without a valid principal, user-destination routing does not work.
 *
 * StompPrincipal RECORD:
 *   A lightweight Java record implementing Principal that carries userId, username, and
 *   subscriptionTier. getName() returns the userId string. ChatWebSocketHandler can cast
 *   the session's getUser() to StompPrincipal to access the tier without another JWT parse.
 *
 * REJECTION BEHAVIOR:
 *   Missing or invalid JWTs throw a RuntimeException which Spring translates into
 *   a STOMP ERROR frame sent back to the client, closing the connection. The frontend
 *   handles this by redirecting to the login page.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtChannelInterceptor implements ChannelInterceptor {

    @Value("${jwt.secret}") private String jwtSecret;

    private final RoomServiceClient roomServiceClient;

    /**
     * preSend — intercepts inbound STOMP frames before they reach the message broker.
     *
     * HOW IT WORKS:
     *   Only CONNECT frames carry the Authorization header — subsequent frames from the
     *   same session inherit the already-set principal. For CONNECT frames, the JWT is
     *   parsed and the principal is set. For all other frame types, the message passes
     *   through unchanged.
     *
     *   The JWT secret is Base64-decoded before creating the HMAC key, matching the
     *   encoding used when auth-service generates tokens in JwtUtil.
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String auth = accessor.getFirstNativeHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                try {
                    SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtSecret));
                    Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(auth.substring(7)).getPayload();
                    String userId = claims.getSubject();
                    String username = claims.get("username", String.class);
                    String subscriptionTier = claims.get("subscriptionTier", String.class);
                    if (subscriptionTier == null || subscriptionTier.isBlank()) subscriptionTier = "FREE";
                    accessor.setUser(new StompPrincipal(userId, username, subscriptionTier));
                    log.info("WebSocket CONNECT: user={}", userId);
                } catch (Exception e) {
                    log.error("WebSocket auth failed: {}", e.getMessage());
                    throw new RuntimeException("Invalid JWT");
                }
            } else {
                throw new RuntimeException("Missing Authorization");
            }
        }

        if (accessor != null && StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            Principal user = accessor.getUser();
            String destination = accessor.getDestination();

            if (user == null) {
                throw new RuntimeException("Unauthenticated subscription");
            }

            if (destination != null && destination.startsWith("/topic/room/")) {
                String roomId = extractRoomId(destination);
                if (roomId == null || roomId.isBlank() || !isRoomMember(roomId, user.getName())) {
                    log.warn("Blocked unauthorized subscription: user={} destination={}", user.getName(), destination);
                    throw new RuntimeException("Not allowed to subscribe to this room");
                }
            }
        }

        return message;
    }

    private String extractRoomId(String destination) {
        String prefix = "/topic/room/";
        if (!destination.startsWith(prefix)) {
            return null;
        }

        String remaining = destination.substring(prefix.length());
        int slashIndex = remaining.indexOf('/');
        return slashIndex >= 0 ? remaining.substring(0, slashIndex) : remaining;
    }

    private boolean isRoomMember(String roomId, String userId) {
        try {
            if (roomId == null || roomId.isBlank() || userId == null || userId.isBlank()) {
                return false;
            }
            Boolean member = roomServiceClient.isMember(roomId, userId, userId);
            return Boolean.TRUE.equals(member);
        } catch (Exception e) {
            log.warn("Room subscription membership check failed for user {} in room {}: {}", userId, roomId, e.getMessage());
            return false;
        }
    }
}


