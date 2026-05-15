# ConnectHub Entity Relationship Diagrams

Due to the microservice architecture, each service operates its own independent database schema. The following diagrams represent the entities and their logical relationships separated by their respective bounded contexts.

## 1. Auth Service Database (`connecthub_auth`)

Stores user credentials, profiling, and manages audit logs.

```mermaid
erDiagram
    USERS {
        BIGINT id PK
        VARCHAR username UK
        VARCHAR email UK
        VARCHAR password
        VARCHAR full_name
        VARCHAR avatar_url
        VARCHAR bio
        VARCHAR role "USER, ADMIN"
        BOOLEAN is_email_verified
        BOOLEAN is_active
        VARCHAR provider "LOCAL, GOOGLE, GITHUB"
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    AUDIT_LOGS {
        BIGINT id PK
        BIGINT user_id FK "Logical link to USERS"
        VARCHAR action
        VARCHAR ip_address
        VARCHAR user_agent
        TIMESTAMP created_at
    }
    
    USERS ||--o{ AUDIT_LOGS : "generates"
```

## 2. Room Service Database (`connecthub_room`)

Handles groups, direct messages (DMs), and membership tracking.

```mermaid
erDiagram
    ROOMS {
        BIGINT id PK
        VARCHAR name
        VARCHAR description
        VARCHAR banner_url
        VARCHAR type "GROUP, DM"
        BIGINT creator_id
        BOOLEAN is_active
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    ROOM_MEMBERS {
        BIGINT id PK
        BIGINT room_id FK
        BIGINT user_id "Logical link to connecthub_auth.USERS"
        VARCHAR role "ADMIN, MODERATOR, MEMBER"
        TIMESTAMP joined_at
        TIMESTAMP last_read_at "Used for read receipts"
        BOOLEAN is_muted
    }

    ROOMS ||--|{ ROOM_MEMBERS : "contains"
```

## 3. Message Service Database (`connecthub_message`)

Manages message history, pagination contexts, and emoji reactions.

```mermaid
erDiagram
    MESSAGES {
        VARCHAR id PK "UUID assigned by WebSocket Service"
        BIGINT room_id "Logical link to ROOMS"
        BIGINT sender_id "Logical link to USERS"
        TEXT content
        VARCHAR type "TEXT, IMAGE, FILE"
        VARCHAR media_url
        VARCHAR delivery_status "SENT, DELIVERED, READ"
        BOOLEAN is_edited
        TIMESTAMP edited_at
        BOOLEAN is_deleted "Soft delete flag"
        TIMESTAMP sent_at
    }

    MESSAGE_REACTIONS {
        BIGINT id PK
        VARCHAR message_id FK
        BIGINT user_id "Logical link to USERS"
        VARCHAR emoji "e.g., 👍, ❤️"
        TIMESTAMP created_at
    }

    MESSAGES ||--o{ MESSAGE_REACTIONS : "receives"
```

## 4. Media & Notification Service Databases

Databases tracking uploaded files and notification queues.

```mermaid
erDiagram
    MEDIA_FILES {
        BIGINT id PK
        BIGINT uploader_id
        VARCHAR file_name
        VARCHAR file_type
        BIGINT file_size
        VARCHAR s3_url
        VARCHAR thumbnail_url
        TIMESTAMP uploaded_at
    }
    
    NOTIFICATIONS {
        BIGINT id PK
        BIGINT user_id
        VARCHAR title
        TEXT body
        VARCHAR type "IN_APP, EMAIL"
        BOOLEAN is_read
        TIMESTAMP created_at
    }
```
