# ConnectHub Class Diagrams

These class diagrams represent the general structure of our REST services and the specific setup for our real-time WebSocket communication engine.

## 1. Typical Microservice REST Structure

This diagram outlines the typical Java class relationship for almost any domain in ConnectHub (using `auth-service` as the example).

```mermaid
classDiagram
    class AuthResource {
        +register(RegisterDto) ResponseEntity
        +login(LoginDto) ResponseEntity
        +verifyOtp(OtpVerifyDto) ResponseEntity
        +getProfile(Long id) ResponseEntity
    }

    class AuthService {
        <<interface>>
        +registerUser(RegisterDto) User
        +loginUser(LoginDto) JwtTokenPair
        +verifyRegistrationOtp(String email, String otp) boolean
    }

    class AuthServiceImpl {
        -UserRepository userRepository
        -JwtProvider jwtProvider
        -PasswordEncoder passwordEncoder
        -RedisTemplate redisTemplate
    }

    class UserRepository {
        <<interface>>
        +findByEmail(String email) Optional~User~
        +findByUsername(String username) Optional~User~
    }

    class User {
        -Long id
        -String username
        -String email
        -String password
        -boolean isEmailVerified
    }

    class JwtProvider {
        +generateAccessToken(User) String
        +generateRefreshToken(User) String
        +validateToken(String) boolean
    }

    AuthResource --> AuthService : "Injects"
    AuthService <|-- AuthServiceImpl : "Implements"
    AuthServiceImpl --> UserRepository : "Injects"
    AuthServiceImpl --> JwtProvider : "Uses"
    UserRepository --> User : "Returns"
```

## 2. WebSocket Real-Time Engine Details

This outlines the WebSocket-Service structure displaying how the JWT Authentication, Presence tracking, and generic Chat handling intertwines.

```mermaid
classDiagram
    class ChatWebSocketHandler {
        <<Controller>>
        -RedisMessagePublisher redisPublisher
        -MessagePersistenceService persistenceService
        +handleChat(ChatMessage p, Principal h)
        +handleTyping(TypingEvent t, Principal h)
        +handleReaction(ReactionEvent r, Principal h)
    }

    class JwtChannelInterceptor {
        +preSend(Message msg, MessageChannel mc) Message
        -validateAndExtractPrincipal(String header) StompPrincipal
    }

    class WebSocketEventListener {
        -RedisTemplate redisTemplate
        -PresenceClient presenceClient
        +onConnect(SessionConnectedEvent e)
        +onDisconnect(SessionDisconnectEvent e)
    }

    class RedisMessageSubscriber {
        -SimpMessagingTemplate messagingTemplate
        +onMessage(Message message, byte[] pattern)
    }

    class MessagePersistenceService {
        -RestTemplate restTemplate
        +persistMessage(ChatMessage msg) CompletableFuture
    }

    class StompPrincipal {
        -String userId
        -String username
        +getName() String
    }

    JwtChannelInterceptor --> StompPrincipal : "Creates onCONNECT"
    ChatWebSocketHandler --> MessagePersistenceService : "Calls async DB write"
    ChatWebSocketHandler --> StompPrincipal : "Reads sender identity"
    WebSocketEventListener --> StompPrincipal : "Extracts identity for online/offline event"
    RedisMessageSubscriber ..> ChatWebSocketHandler : "Subscribes to same channels"
```
