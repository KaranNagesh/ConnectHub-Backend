# Messaging and Real-Time Event Sequence Diagrams

These sequence diagrams display how STOMP Websockets, Redis Pub/Sub, and Async processing handle Chat functionality.

## 1. Chat Message Sending (Real-Time Fast Path vs Slow Path)

```mermaid
sequenceDiagram
    actor UserA as "Sender (User A)"
    participant WS as WebSocket-Service
    participant Redis as Redis Pub/Sub
    participant Kafka as Kafka Broker
    participant MessageSvc as Message-Service
    actor UserB as "Receiver (User B)"

    UserA->>WS: STOMP /app/chat.send {roomId, content}
    
    WS->>WS: Extract Sender ID & Username from JWT
    WS->>WS: Generate UUID MessageID
    WS->>WS: XSS sanitize content
    WS->>WS: set deliveryStatus = SENT

    par Real-Time Fast Path (~10ms)
        WS->>Redis: PUBLISH chat:messages (Payload)
        Redis-->>WS: (RedisSubscriber trigger)
        WS-->>UserB: STOMP /topic/room/{id} (new message)
        WS-->>UserA: STOMP /user/queue/messages (Confirmation)
    and Async Persistence Path (Slow Path)
        WS->>Kafka: Produce Event (topic: chat.messages.persist)
        Kafka-->>MessageSvc: Consume via KafkaMessageListener
        MessageSvc->>MessageSvc: Persist to MySQL DB
    end
```

## 2. Typing Indicator Sequence

Typing events are ephemeral and loss-tolerant, so they broadcast locally within the scope of the room without Redis Pub/Sub fanout.

```mermaid
sequenceDiagram
    actor Typer as "User A"
    participant WS as WebSocket-Service
    actor Observer as "User B"

    Typer->>WS: STOMP /app/chat.typing {roomId, typing: true}
    WS-->>Observer: STOMP /topic/room/{id}/typing
    
    note over Observer: UI Shows "User A is typing..."
    
    note over Typer: (3 seconds of inactivity, or message sent)
    
    Typer->>WS: STOMP /app/chat.typing {roomId, typing: false}
    WS-->>Observer: STOMP /topic/room/{id}/typing
    
    note over Observer: UI Hides typing indicator
```

## 3. Presence Tracking System Workflow

```mermaid
sequenceDiagram
    actor User
    participant Frontend
    participant WS as WebSocket-Service
    participant Presence as Presence-Service (REST)
    participant Redis as Redis DB

    note over User, Frontend: User Logs into Application
    Frontend->>WS: STOMP CONNECT (Bearer Token)
    
    WS->>WS: validate JWT (JwtChannelInterceptor)
    WS->>WS: Trigger onConnect Event
    
    WS->>Presence: POST /presence/online/{userId}
    Presence->>Redis: SET presence:{userId} (5m TTL)
    Presence->>Redis: SADD presence:online {userId}
    
    WS->>Redis: PUBLISH chat:presence {userId, ONLINE}
    Redis-->>Frontend: Broadcast to all connected clients
    
    loop Every 60s
        Frontend->>Presence: POST /presence/ping/{userId}
        Presence->>Redis: Refresh presence TTL (5m)
    end
    
    note over User, Frontend: User closes tab unexpectedly
    loop Every 60s @Scheduled Task 
        Presence->>Presence: Check stale pings
        note over Presence: finds user hasn't pinged in 90s
        Presence->>Redis: DEL presence:{userId}
    end
```

## 4. Edit/Delete Distributed Workflow

```mermaid
sequenceDiagram
    actor UserA
    participant Gateway
    participant MessageSvc as Message-Service(REST)
    participant WS as WebSocket-Service
    participant Redis as Redis Pub/Sub
    actor UserB

    UserA->>Gateway: PUT /api/v1/messages/{messageId} (New Content)
    Gateway->>MessageSvc: Forward
    MessageSvc->>MessageSvc: Update DB (isEdited=true, new content)
    MessageSvc-->>UserA: 200 OK
    
    UserA->>WS: STOMP /app/chat.edit {roomId, messageId, newContent}
    WS->>WS: Sanitize
    WS->>Redis: PUBLISH chat:edits
    
    Redis-->>WS: (RedisSubscriber)
    WS-->>UserB: STOMP /topic/room/{id}/edit
    
    note over UserB: UI updates the single message content in-place
```
