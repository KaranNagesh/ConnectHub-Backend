# ConnectHub Feature Flow Diagrams

These flow diagrams represent the end-to-end functionality flows of the core microservice features inside the ConnectHub backend.

## 1. Complete Registration and Authorization Flowchart

```mermaid
flowchart TD
    %% Start
    A((Start)) --> B[User visits /register]
    
    B --> C{Inputs valid?}
    C -- No --> D[Return 400 Validation Error]
    
    C -- Yes --> E[Gateway filters & forwards to Auth-Service]
    E --> F{Email/User Unique?}
    
    F -- No --> G[Return 409 Conflict]
    
    F -- Yes --> H[BCrypt hash Password]
    H --> I["Save to auth_db DB\nisEmailVerified = false"]
    I --> J[Generate 6-digit OTP]
    J --> K[Store OTP in Redis\nTTL = 5 mins]
    K --> L[Pub/Sub Notification-Service]
    L --> M[Notification sends Email via SMTP]
    
    %% OTP input
    M --> N[User submits OTP via /verify]
    
    N --> O{OTP Correct & Not Expired?}
    O -- No --> P[Return 400 Bad Request]
    
    O -- Yes --> Q["Update auth_db DB\nisEmailVerified = true"]
    Q --> R[Generate JWT Access & Refresh Tokens]
    R --> S[Return 200 OK + JWT]
    S --> T((End: Successfully Registered))
```

## 2. File Upload Flowchart

```mermaid
flowchart TD
    Start((Start)) --> UploadBtn["User clicks Upload Media in Frontend"]
    UploadBtn --> SendFile["Browser POST /api/v1/media/upload (Multipart)"]
    
    SendFile --> GW["API Gateway"]
    GW --> JWT{"Valid JWT?"}
    JWT -- No --> Error401["401 Unauthorized"]
    
    JWT -- Yes --> MediaSvc["Forward to Media-Service"]
    
    MediaSvc --> Validate{"Check Type/Size"}
    Validate -- Invalid --> Error400["400 Bad Request"]
    
    Validate -- Valid --> S3["Upload File to AWS S3 & LocalStack"]
    S3 --> S3Thumbs["Generate & Upload Thumbnail"]
    
    S3Thumbs --> DB["Save metadata to connecthub_media DB"]
    DB --> GenerateURL["Generate pre-signed URL"]
    
    GenerateURL --> ReturnClient["Return 201 Created + URL to Frontend"]
    
    ReturnClient --> MsgGen["Frontend embeds URL in ChatMessage object"]
    MsgGen --> SendSTOMP["Client triggers STOMP /app/chat.send"]
    SendSTOMP --> End((End Broadcast))
```

## 3. Emoji Reactions Pipeline Flowchart

```mermaid
flowchart TD
    Start((Start)) --> UI[User Clicks Emoji 👍 on Message M1]
    
    UI --> Optimistic[Frontend immediately displays +1]
    
    Optimistic --> REST[Frontend POST /messages/M1/reactions]
    REST --> MsgDB[Message-Service Saves reaction to DB]
    
    Optimistic --> WS[Frontend STOMP /app/chat.react]
    WS --> WSSvc[WebSocket Service receives frame]
    
    WSSvc --> Format[Format ReactionEvent with UserID]
    Format --> Pub[Publish to Redis `chat:reactions`]
    
    Pub --> Sub1[Instance 1 Subscriber]
    Pub --> Sub2[Instance 2 Subscriber]
    
    Sub1 --> ClientA[Broadcast to /topic/room/R1/reactions]
    Sub2 --> ClientB[Broadcast to /topic/room/R1/reactions]
    
    ClientA --> Resolve[Frontend compares with optimistic guess]
    ClientB --> End((End Broadcast))
```
