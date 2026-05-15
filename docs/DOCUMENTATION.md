# ConnectHub — Complete Project Documentation
## Setup Guide, Architecture Walkthrough & Evaluation Preparation

---

# TABLE OF CONTENTS

- [PART 1: LOCAL SETUP (Step-by-Step)](#part-1-local-setup)
  - [1.1 Prerequisites](#11-prerequisites)
  - [1.2 Install Prerequisites](#12-install-prerequisites)
  - [1.3 Clone & Project Structure](#13-clone--project-structure)
  - [1.4 Database Setup](#14-database-setup)
  - [1.5 Redis Setup](#15-redis-setup)
  - [1.6 Configuration](#16-configuration)
  - [1.7 Build the Project](#17-build-the-project)
  - [1.8 Start Services (Correct Order)](#18-start-services-correct-order)
  - [1.9 Verify Everything Works](#19-verify-everything-works)
  - [1.10 Docker Setup (Alternative)](#110-docker-setup-alternative)
  - [1.11 Common Issues & Fixes](#111-common-issues--fixes)
- [PART 2: PROJECT ARCHITECTURE](#part-2-project-architecture)
  - [2.1 What is ConnectHub?](#21-what-is-connecthub)
  - [2.2 Why Microservices?](#22-why-microservices)
  - [2.3 Architecture Diagram](#23-architecture-diagram)
  - [2.4 Service-by-Service Breakdown](#24-service-by-service-breakdown)
  - [2.5 Database Design](#25-database-design)
  - [2.6 Technology Stack Explained](#26-technology-stack-explained)
- [PART 3: HOW IT WORKS (Flow-by-Flow)](#part-3-how-it-works)
  - [3.1 Registration Flow](#31-registration-flow)
  - [3.2 Login Flow](#32-login-flow)
  - [3.3 Forgot Password Flow](#33-forgot-password-flow)
  - [3.4 Sending a Chat Message (Real-Time)](#34-sending-a-chat-message)
  - [3.5 Typing Indicator Flow](#35-typing-indicator-flow)
  - [3.6 Read Receipt Flow](#36-read-receipt-flow)
  - [3.7 File Upload Flow](#37-file-upload-flow)
  - [3.8 Presence (Online/Offline) Flow](#38-presence-flow)
  - [3.9 Edit & Delete Message Flow](#39-edit--delete-message-flow)
  - [3.10 Emoji Reaction Flow](#310-emoji-reaction-flow)
  - [3.11 Gateway Request Flow](#311-gateway-request-flow)
  - [3.12 Cross-Instance Message Broadcast](#312-cross-instance-broadcast)
- [PART 4: CODE WALKTHROUGH](#part-4-code-walkthrough)
  - [4.1 Layered Architecture Pattern](#41-layered-architecture-pattern)
  - [4.2 JWT Authentication Deep Dive](#42-jwt-authentication)
  - [4.3 OTP System Deep Dive](#43-otp-system)
  - [4.4 WebSocket/STOMP Deep Dive](#44-websocketstomp)
  - [4.5 XSS Protection](#45-xss-protection)
  - [4.6 Rate Limiting](#46-rate-limiting)
  - [4.7 Cursor-Based Pagination](#47-cursor-based-pagination)
- [PART 5: TESTING & QUALITY](#part-5-testing--quality)
  - [5.1 Running Tests](#51-running-tests)
  - [5.2 JaCoCo Coverage](#52-jacoco-coverage)
  - [5.3 SonarQube Analysis](#53-sonarqube-analysis)
- [PART 6: API REFERENCE](#part-6-api-reference)
- [PART 7: EVALUATION Q&A](#part-7-evaluation-qa)

---

# PART 1: LOCAL SETUP

## 1.1 Prerequisites

| Tool | Version | Why |
|------|---------|-----|
| Java JDK | 17 or higher | Runtime for Spring Boot |
| Apache Maven | 3.8+ | Build tool for compiling and packaging |
| MySQL | 8.0+ | Primary database for 5 services |
| Redis | 7.0+ | Session store, OTP store, presence, pub/sub |
| Git | Any | Clone the repository |
| IDE | IntelliJ IDEA (recommended) or VS Code | Code editing and debugging |
| Node.js | 18+ | Required for the React frontend (F13) |
| Postman | Any | API testing |

## 1.2 Install Prerequisites

### Windows

```powershell
# Java 17 — Download from https://adoptium.net/temurin/releases/
java -version   # Should show: openjdk version "17.x.x"

# Maven — Download from https://maven.apache.org/download.cgi
# Extract to C:\maven, add C:\maven\bin to PATH
mvn -version    # Should show: Apache Maven 3.x.x

# MySQL — Download from https://dev.mysql.com/downloads/installer/
# Set root password to: root, keep default port 3306
mysql -u root -p -e "SELECT VERSION();"

# Redis — use WSL2 (recommended)
wsl --install
# Inside WSL:
sudo apt update && sudo apt install redis-server
sudo service redis-server start
redis-cli ping  # Should respond: PONG

# Node.js — Download from https://nodejs.org
node -v   # Should show: v18.x or higher
```

### macOS

```bash
brew install openjdk@17 maven mysql redis node
brew services start mysql
brew services start redis
# Set MySQL root password
mysql -u root
ALTER USER 'root'@'localhost' IDENTIFIED BY 'root';
EXIT;
```

### Linux (Ubuntu/Debian)

```bash
sudo apt update
sudo apt install openjdk-17-jdk maven mysql-server redis-server nodejs npm
sudo mysql_secure_installation   # Set root password to: root
sudo systemctl start mysql redis-server
sudo systemctl enable mysql redis-server
# Verify
java -version && mvn -version && redis-cli ping
```

## 1.3 Clone & Project Structure

```
connecthub/
├── pom.xml                      ← Parent POM (manages all modules)
├── docker-compose.yml           ← Docker setup (alternative to manual)
├── init-databases.sql           ← Creates all MySQL databases
├── .env.example                 ← Environment variable template
├── README.md
├── DOCUMENTATION.md             ← This file
│
├── service-registry/            ← Eureka Discovery Server (port 8761)
├── api-gateway/                 ← API Gateway (port 8080)
├── auth-service/                ← Authentication & Users (port 8081)
├── room-service/                ← Chat Rooms & Membership (port 8082)
├── message-service/             ← Messages & Reactions (port 8083)
├── media-service/               ← File Upload & S3 (port 8084)
├── presence-service/            ← Online/Offline Status (port 8085)
├── notification-service/        ← Email, Push, In-App (port 8086)
└── websocket-service/           ← Real-Time STOMP/WebSocket (port 8087)
```

Each service follows this internal structure:

```
auth-service/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/connecthub/auth/
    │   │   ├── AuthServiceApplication.java
    │   │   ├── entity/          ← JPA entities (DB tables)
    │   │   ├── dto/             ← Request/response objects
    │   │   ├── repository/      ← Spring Data JPA interfaces
    │   │   ├── service/         ← Business logic
    │   │   ├── resource/        ← REST controllers
    │   │   ├── config/          ← Security, JWT, Swagger
    │   │   └── exception/       ← Global error handler
    │   └── resources/
    │       ├── application.yml
    │       ├── logback-spring.xml
    │       └── db/migration/
    │           └── V1__initial_schema.sql
    └── test/
        └── java/com/connecthub/auth/service/
```

## 1.4 Database Setup

```bash
mysql -u root -p

CREATE DATABASE connecthub_auth;
CREATE DATABASE connecthub_room;
CREATE DATABASE connecthub_message;
CREATE DATABASE connecthub_media;
CREATE DATABASE connecthub_notification;

SHOW DATABASES LIKE 'connecthub%';
EXIT;
```

**You do NOT need to create tables manually.** Flyway automatically creates all tables on first startup using the SQL files in each service's `db/migration/` folder.

## 1.5 Redis Setup

```bash
redis-cli ping      # Should respond: PONG

# Redis is used for:
# 1. OTP storage              (6-digit codes with TTL)
# 2. Token blacklist          (invalidated JWT tokens after logout)
# 3. Presence data            (who's online, last-seen timestamps)
# 4. Rate limiting            (request counters per user per minute)
# 5. Pub/Sub messaging        (cross-instance WebSocket broadcast, email events)

# Monitor Redis in real-time for debugging:
redis-cli monitor
```

## 1.6 Configuration

All defaults work for local development. The only things you may want to configure:

**Email sending (optional — OTPs fall back to Redis/logs if not set):**

```yaml
# notification-service/src/main/resources/application.yml
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: your-email@gmail.com
    password: xxxx xxxx xxxx xxxx    # Gmail App Password from https://myaccount.google.com/apppasswords
```

If email is not configured, retrieve OTPs directly from Redis:
```bash
redis-cli GET "otp:register:john@example.com"
```

**File uploads (optional — uploads will fail without S3 credentials):**

Use LocalStack to emulate S3 locally:
```bash
docker run -d -p 4566:4566 localstack/localstack
# Then set in media-service/application.yml:
# aws.s3.endpoint-url: http://localhost:4566
```

## 1.7 Build the Project

```bash
# Build ALL backend services (from the parent directory):
mvn clean package -DskipTests

# Build with tests (required before packaging for production):
mvn clean package

# Build a single service:
cd auth-service && mvn clean package -DskipTests

# Build the frontend:
cd ../F13 && npm install && npm run build
```

Expected build time: 2–5 minutes first time, 30–60 seconds after dependencies are cached.

## 1.8 Start Services (Correct Order)

**Order matters:** Eureka must start first, then the gateway, then all other services.

```bash
# Terminal 1: Service Registry — start first, wait for "Started ServiceRegistryApplication"
cd service-registry && mvn spring-boot:run

# Terminal 2: API Gateway
cd api-gateway && mvn spring-boot:run

# Terminals 3–9: All other services (order doesn't matter among these)
cd auth-service         && mvn spring-boot:run
cd room-service         && mvn spring-boot:run
cd message-service      && mvn spring-boot:run
cd presence-service     && mvn spring-boot:run
cd notification-service && mvn spring-boot:run
cd media-service        && mvn spring-boot:run   # Optional (needs S3)
cd websocket-service    && mvn spring-boot:run

# Frontend (separate terminal):
cd F13 && npm run dev    # Runs on http://localhost:5173
```

**IntelliJ IDEA tip:** Create a "Compound" run configuration with all 9 Spring Boot apps to start them all at once with a single click.

## 1.9 Verify Everything Works

```bash
# 1. Eureka Dashboard — all services should appear
open http://localhost:8761

# 2. Gateway health
curl http://localhost:8080/actuator/health
# {"status":"UP"}

# 3. Full registration flow
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"john","email":"john@example.com","password":"MyPass@123","fullName":"John Doe"}'
# Get OTP from Redis if email not configured:
redis-cli GET "otp:register:john@example.com"

# 4. Verify OTP and get JWT
curl -X POST http://localhost:8080/api/v1/auth/verify-registration-otp \
  -H "Content-Type: application/json" \
  -d '{"email":"john@example.com","otp":"847291"}'
# Response: {"accessToken":"eyJ...","refreshToken":"eyJ...","user":{...}}
export TOKEN="eyJ..."

# 5. Test authenticated request
curl http://localhost:8080/api/v1/auth/profile/1 -H "Authorization: Bearer $TOKEN"
```

**Swagger UIs** (for interactive API testing):
```
http://localhost:8081/swagger-ui.html  — Auth
http://localhost:8082/swagger-ui.html  — Room
http://localhost:8083/swagger-ui.html  — Message
http://localhost:8085/swagger-ui.html  — Presence
http://localhost:8086/swagger-ui.html  — Notification
```

## 1.10 Docker Setup (Alternative)

```bash
cp .env.example .env
mvn clean package -DskipTests
docker-compose up -d
docker-compose ps          # Check all containers are Up
docker-compose logs -f auth-service
docker-compose down -v     # Stop and remove data
```

Only the gateway (8080) and Eureka (8761) are port-exposed on the host. All inter-service traffic stays inside the Docker network — exactly as in production.

## 1.11 Common Issues & Fixes

| Problem | Fix |
|---|---|
| `Connection refused` to MySQL | `sudo systemctl start mysql` / `brew services start mysql` |
| `Connection refused` to Redis | `redis-cli ping` — if no PONG, start Redis |
| Port already in use | `lsof -i :8081` to find the PID, kill it, or change port in `application.yml` |
| Service not in Eureka | Wait 30s, check Eureka URL in `application.yml`, ensure service-registry started first |
| Flyway migration failed | Drop and recreate the database: `mysql -u root -p -e "DROP DATABASE connecthub_auth; CREATE DATABASE connecthub_auth;"` |
| OTP not received by email | Normal if SMTP not configured — use `redis-cli GET "otp:register:email@x.com"` |
| WebSocket connection refused | Ensure websocket-service is running on port 8087; check JWT token is valid |

---

# PART 2: PROJECT ARCHITECTURE

## 2.1 What is ConnectHub?

ConnectHub is a **real-time chat application** built with Spring Boot microservices, equivalent in function to WhatsApp Web or Slack. Core capabilities:

- Register with email OTP verification
- Login (JWT) + OAuth2 (Google, GitHub)
- Group rooms and 1:1 direct messages
- Real-time text messaging, file/image sharing
- Typing indicators, read receipts, emoji reactions
- Edit and delete messages (broadcast in real-time to all room members)
- Online/offline presence tracking with automatic stale-session cleanup
- Message search, pinned messages, role-based room membership
- In-app and email notifications

## 2.2 Why Microservices?

```
9 INDEPENDENT SERVICES — each owns exactly one domain:

auth-service      → users and authentication
room-service      → rooms and membership
message-service   → messages and reactions
media-service     → file uploads (S3)
presence-service  → online/offline status (Redis only — no SQL)
notification-svc  → emails, push, in-app notifications
websocket-service → real-time STOMP connections
api-gateway       → routing, JWT validation, rate limiting
service-registry  → service discovery (Eureka)

Benefits:
  ✅ Scale WebSocket independently from auth (they have very different load profiles)
  ✅ Slow email delivery in notification-service never blocks chat
  ✅ Each service has its own database (no coupling, no single point of failure)
  ✅ presence-service uses Redis only — the right tool for ephemeral data
  ✅ A bug in media-service cannot crash the message pipeline
```

## 2.3 Architecture Diagram

```
                         ┌─────────────────────┐
   BROWSER / MOBILE      │   Service Registry   │
        │                │   (Eureka - 8761)    │
        │                └──────────┬───────────┘
        │                           │ All services register here
        ▼                           │
  ┌───────────┐                     │
  │    API    │◄────────────────────┘
  │  Gateway  │   Discovers service locations
  │  (8080)   │   Validates JWT, Rate limiting
  └─────┬─────┘
        │
        │  Routes by URL path:
        │  /api/v1/auth/**        → auth-service  (8081)
        │  /api/v1/rooms/**       → room-service  (8082)
        │  /api/v1/messages/**    → message-service (8083)
        │  /api/v1/media/**       → media-service (8084)
        │  /api/v1/presence/**    → presence-service (8085)
        │  /api/v1/notifications/** → notification-service (8086)
        │  /ws/**                 → websocket-service (8087)
        │
  ┌─────┴──────────────────────────────────────────────┐
  ▼              ▼              ▼              ▼        ▼
┌──────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ ┌──────────┐
│ Auth │  │  Room    │  │ Message  │  │  Media   │ │WebSocket │
│ 8081 │  │  8082    │  │  8083    │  │  8084    │ │  8087    │
└──┬───┘  └────┬─────┘  └────┬─────┘  └────┬─────┘ └────┬─────┘
   │           │              │              │            │
   ▼           ▼              ▼              ▼            │
MySQL(auth) MySQL(room)   MySQL(msg)     MySQL(media)    │
   │                                         │            │
   └────────────────────────────────────────►│◄───────────┘
                                         ┌───┴────┐
                              ┌──────────┤  Redis ├──────────┐
                              │          └────────┘          │
                              ▼                              ▼
                        ┌──────────┐                  ┌──────────┐
                        │ Presence │                  │  Notif   │
                        │  8085    │                  │  8086    │
                        └──────────┘                  └──────────┘
                           Redis only                  MySQL(notif)
```

## 2.4 Service-by-Service Breakdown

### SERVICE REGISTRY — Port 8761

Eureka discovery server. Every service registers its IP+port on startup and sends a heartbeat every 30 seconds. The gateway queries Eureka to find where to route each request. Key file: `@EnableEurekaServer` in `ServiceRegistryApplication.java`.

---

### API GATEWAY — Port 8080

The sole entry point for all external traffic. Applies filters in order before routing:

1. **TraceIdFilter** — generates a unique `X-Trace-Id` header so a request can be traced across all service logs
2. **JwtAuthenticationFilter** — validates the JWT; passes `X-User-Id`, `X-User-Email`, `X-User-Role` headers downstream; returns 401 if invalid
3. **RateLimitFilter** — enforces 100 requests/minute per user via Redis atomic counter; returns 429 if exceeded
4. **CorsConfig** — permits cross-origin requests from the frontend

WebSocket upgrade requests (`/ws/**`) bypass JWT filter and are authenticated by the STOMP `JwtChannelInterceptor` on the WebSocket side.

---

### AUTH-SERVICE — Port 8081

All things user: registration with email OTP, login, forgot password, JWT management, OAuth2, profile, admin controls.

- **OTP flow**: generate 6-digit code → store in Redis with TTL → publish email event → user submits OTP → verify → issue JWT
- **Token blacklist**: on logout, the JWT is stored in Redis with TTL matching token expiry, making it permanently invalid
- **BCrypt strength 12**: intentionally slow hashing to prevent brute-force attacks
- **Scheduled cleanup**: unverified accounts older than 24 hours are auto-deleted

Database: `users`, `audit_logs`

---

### ROOM-SERVICE — Port 8082

Manages chat rooms and their membership.

- GROUP rooms: up to 500 members, creator becomes ADMIN
- DM rooms: exactly 2 members, always private, max members = 2 enforced at creation
- Member roles: ADMIN / MODERATOR / MEMBER with role-change API
- Mute/unmute individual members
- Pin a message in the room
- Tracks `lastReadAt` per member for unread counts

Database: `rooms`, `room_members`

---

### MESSAGE-SERVICE — Port 8083

Persists and retrieves chat messages.

- **Cursor-based pagination**: avoids duplicate/missing messages during scrolling (see §4.7)
- **XSS sanitization**: all content HTML-escaped before storage (`<script>` → `&lt;script&gt;`)
- **Soft delete**: `isDeleted=true` flag — message stays in DB for audit, hidden from chat UI
- **Delivery status pipeline**: SENT → DELIVERED → READ
- **Edit tracking**: `isEdited=true` + `editedAt` timestamp stored
- **Reactions**: unique per user+message+emoji combination
- **Full-text search**: `LIKE '%keyword%'` query across room messages

Database: `messages`, `message_reactions`

---

### MEDIA-SERVICE — Port 8084

Handles file uploads with S3 storage.

- Accepts images (JPEG, PNG, GIF, WebP) and documents (PDF, DOCX, ZIP) up to 25MB
- Auto-generates image thumbnails via Thumbnailator
- Stores files on AWS S3 with UUID-based paths
- Returns pre-signed S3 URLs valid for 24 hours

Database: `media_files` (metadata only; actual files live on S3)

---

### PRESENCE-SERVICE — Port 8085

Tracks who is online. Uses **Redis exclusively** — no SQL database.

- On WebSocket connect → `websocket-service` calls `POST /presence/online/{uid}` → Redis key `presence:{uid}` with 5-minute TTL + added to `presence:online` set
- On WebSocket disconnect → `POST /presence/offline/{uid}` → key deleted, removed from set
- **Stale session cleanup**: `@Scheduled` job runs every 60 seconds; any user whose `lastPingAt` is more than 90 seconds ago is marked offline (handles ungraceful disconnections)
- **Frontend ping**: the browser calls `POST /presence/ping/{uid}` every 60 seconds to refresh the Redis TTL
- Bulk presence lookup for entire room member lists in a single Redis `MGET`

---

### NOTIFICATION-SERVICE — Port 8086

Async email and in-app notifications.

- Subscribes to `email:send` Redis Pub/Sub channel
- Sends OTP emails for registration and password reset
- Stores in-app notifications in MySQL for the notification center UI
- All email sending runs `@Async` so slow SMTP calls don't block the caller

Database: `notifications`

---

### WEBSOCKET-SERVICE — Port 8087

The real-time messaging engine.

- STOMP over SockJS with JWT authentication on CONNECT via `JwtChannelInterceptor`
- Reads `StompPrincipal(id, username)` from the JWT — both fields available to all message handlers
- All chat messages get a **server-generated UUID** before broadcast, so every client receives the same `messageId` they can use for replies, reactions, edits, and deletes
- Redis Pub/Sub on five channels: `chat:messages`, `chat:presence`, `chat:edits`, `chat:deletes`, `chat:reactions` — ensures all instances in a scaled deployment deliver every event
- **Typing indicators** broadcast locally (no Redis fan-out needed — ephemeral, loss is harmless)
- **Async persistence** via `MessagePersistenceService` — message appears in chat in ~10ms; DB write follows asynchronously

**STOMP destinations:**

| Destination | Direction | Purpose |
|---|---|---|
| `/app/chat.send` | Client → Server | Send message |
| `/app/chat.typing` | Client → Server | Typing indicator |
| `/app/chat.read` | Client → Server | Read receipt |
| `/app/chat.react` | Client → Server | Emoji reaction |
| `/app/chat.edit` | Client → Server | Edit message |
| `/app/chat.delete` | Client → Server | Delete message |
| `/topic/room/{roomId}` | Server → Client | New messages |
| `/topic/room/{roomId}/typing` | Server → Client | Typing events |
| `/topic/room/{roomId}/read` | Server → Client | Read receipts |
| `/topic/room/{roomId}/edit` | Server → Client | Message edits |
| `/topic/room/{roomId}/delete` | Server → Client | Message deletions |
| `/topic/room/{roomId}/reactions` | Server → Client | Reaction updates |
| `/topic/presence` | Server → Client | Online/offline events |
| `/user/{id}/queue/messages` | Server → Client | Sender confirmation |

## 2.5 Database Design

```
auth-service      → connecthub_auth        (users, audit_logs)
room-service      → connecthub_room        (rooms, room_members)
message-service   → connecthub_message     (messages, message_reactions)
media-service     → connecthub_media       (media_files)
notification-svc  → connecthub_notification (notifications)
presence-service  → Redis only             (no MySQL)
```

Each service manages its own schema via Flyway. `application.yml` uses `ddl-auto: validate` — Hibernate checks but never modifies the schema. Schema changes go in versioned SQL files: `V2__add_bio_column.sql`, etc.

## 2.6 Technology Stack Explained

| Technology | Where Used | Why |
|---|---|---|
| Java 17 | All services | LTS, records, text blocks |
| Spring Boot 3.2 | All services | Auto-configuration, embedded Tomcat |
| Spring Cloud Gateway | api-gateway | Non-blocking Netty-based routing |
| Spring WebSocket + STOMP | websocket-service | STOMP adds destinations/subscriptions over raw WebSocket |
| SockJS | Frontend + WS service | Fallback for environments blocking WebSocket |
| Netflix Eureka | service-registry | Spring Cloud native service discovery |
| MySQL 8 | 5 services | Relational, ACID transactions for users/rooms/messages |
| Redis 7 | auth, presence, notif, ws, gateway | Sub-millisecond in-memory: OTP, presence, pub/sub, rate limits |
| JWT (JJWT) | auth, gateway, websocket | Stateless auth — no server session needed |
| Flyway | All MySQL services | Versioned, reproducible DB migrations |
| Lombok | All services | Eliminates boilerplate getters/setters/builders |
| BCrypt (strength 12) | auth-service | Adaptive hashing to resist brute force |
| AWS S3 + Thumbnailator | media-service | Scalable file storage + server-side thumbnails |
| SpringDoc OpenAPI | All REST services | Auto-generated Swagger UI |
| JaCoCo | All services | 80% line coverage enforcement |
| Resilience4j | api-gateway | Circuit breaker for downstream service failures |
| Zustand | Frontend (F13) | Lightweight React state management |
| Vite + React | Frontend (F13) | Fast dev build, component-based UI |

---

# PART 3: HOW IT WORKS (Flow-by-Flow)

## 3.1 Registration Flow

```
USER              GATEWAY          AUTH-SERVICE        REDIS          NOTIFICATION-SVC
 │                   │                  │                 │                  │
 ├─POST /register───►│                  │                 │                  │
 │                   ├─────────────────►│                 │                  │
 │                   │                  ├─check email unique                 │
 │                   │                  ├─check username unique              │
 │                   │                  ├─BCrypt hash password               │
 │                   │                  ├─save user (emailVerified=false)    │
 │                   │                  ├─generate OTP───►│ SET otp:register:email (TTL 5min)
 │                   │                  ├─set cooldown───►│ SET otp:cooldown:register:email (TTL 60s)
 │                   │                  ├─publish email──►│ PUBLISH email:send
 │                   │                  │                 │──────────────────►│ SMTP email
 │◄──201─────────────┤◄─────────────────┤                 │                  │
 │                   │                  │                 │                  │
 ├─POST /verify-otp─►│                  │                 │                  │
 │ {email, otp}      ├─────────────────►│                 │                  │
 │                   │                  ├─verify OTP─────►│ GET → match ✓    │
 │                   │                  ├─emailVerified=true                  │
 │                   │                  ├─generate JWT                       │
 │◄──200 {JWT}───────┤◄─────────────────┤                 │                  │
```

## 3.2 Login Flow

```
USER              GATEWAY          AUTH-SERVICE        REDIS
 │                   │                  │                 │
 ├─POST /login──────►│─────────────────►│                 │
 │ {email,password}  │                  ├─find user       │
 │                   │                  ├─check active=true
 │                   │                  ├─check provider=LOCAL
 │                   │                  ├─check emailVerified=true
 │                   │                  ├─BCrypt.matches(password, hash)
 │                   │                  ├─generate access JWT (24h)
 │                   │                  ├─generate refresh JWT (7d)
 │◄──200 {JWT}───────┤◄─────────────────┤                 │
```

## 3.3 Forgot Password Flow

```
USER              GATEWAY          AUTH-SERVICE        REDIS
 │                   │                  │                 │
 ├─POST /forgot─────►│─────────────────►│                 │
 │ {email}           │                  ├─find user (silent no-op if missing → prevents enumeration)
 │                   │                  ├─generate OTP───►│ SET otp:reset:email (TTL 10min)
 │                   │                  ├─publish email──►│ PUBLISH email:send
 │◄──200 "code sent"─┤◄─────────────────┤                 │
 │                   │                  │                 │
 ├─POST /verify-reset-otp              │                 │
 │ {email, otp}      ├─────────────────►├─verify OTP─────►│ ✓
 │                   │                  ├─generate short-lived reset JWT (15min, purpose=PASSWORD_RESET)
 │◄──200 {resetToken}┤◄─────────────────┤                 │
 │                   │                  │                 │
 ├─POST /reset-password                │                 │
 │ {resetToken, newPassword}           │                 │
 │                   ├─────────────────►├─validate token + purpose claim
 │                   │                  ├─BCrypt hash new password
 │                   │                  ├─invalidate all sessions via Redis
 │◄──200─────────────┤◄─────────────────┤                 │
```

## 3.4 Sending a Chat Message

```
USER A            WEBSOCKET-SVC          REDIS           MESSAGE-SVC     USER B
 │                     │                   │                  │              │
 ├─STOMP /app/chat.send►│                   │                  │              │
 │ {roomId,content,type}│                   │                  │              │
 │                     ├─XSS sanitize       │                  │              │
 │                     ├─set senderId+username from JWT principal             │
 │                     ├─generate UUID messageId                             │
 │                     ├─set timestamp, deliveryStatus=SENT                  │
 │                     │                   │                  │              │
 │  ╔═══ FAST PATH (~10ms) ════════════════════════════════════════════════╗  │
 │  ║                  ├─PUBLISH chat:messages─►│                         ║  │
 │  ║                  │                   │    ▼ RedisSubscriber          ║  │
 │  ║◄─confirm /queue/messages             │    ├─STOMP /topic/room/{id}──►║─►│
 │  ╚══════════════════════════════════════════════════════════════════════╝  │
 │                     │                   │                  │              │
 │  ╔═══ SLOW PATH (async — user does not wait) ═══════════════════════╗    │
 │  ║                  ├─POST /api/v1/messages (via RestTemplate)──────►║    │
 │  ║                  │                   │                  ├─persist║    │
 │  ╚══════════════════════════════════════════════════════════════════╝    │
```

The message arrives at all clients in ~10ms. The DB write happens asynchronously and does not block the sender.

## 3.5 Typing Indicator Flow

```
USER A (typing)   WEBSOCKET-SVC                     USER B
 │                     │                                 │
 ├─STOMP /app/chat.typing ─────────────────────────────►│
 │ {roomId, typing:true}├─/topic/room/{id}/typing        ├─UI: "Alice is typing..."
 │                     │                                 │
 │  (3s of inactivity) │                                 │
 ├─{roomId, typing:false}────────────────────────────────►│
 │                     │                                 ├─UI: indicator hidden
```

Typing events are broadcast directly (no Redis fan-out) — ephemeral, not persisted.

## 3.6 Read Receipt Flow

```
USER B (opens room)   WEBSOCKET-SVC                     USER A
 │                         │                                 │
 ├─STOMP /app/chat.read────►│                                 │
 │ {roomId, upToMessageId}  ├─/topic/room/{id}/read──────────►│ UI: ✓✓ (blue)
```

## 3.7 File Upload Flow

```
USER          GATEWAY      MEDIA-SVC            S3
 │               │              │                 │
 ├─POST /upload─►│─────────────►│                 │
 │ (multipart)   │              ├─validate type/size
 │               │              ├─sanitize filename
 │               │              ├─PUT object──────►│ (file to S3)
 │               │              ├─generate thumb──►│ (thumbnail to S3)
 │               │              ├─save metadata to MySQL
 │◄──201 {url, thumbnailUrl}◄───┤                 │
 │  (user then sends WS message with mediaUrl)
```

## 3.8 Presence Flow

```
USER          WEBSOCKET-SVC          REDIS         PRESENCE-SVC
 │                 │                   │                 │
 ├─WS CONNECT─────►│                   │                 │
 │ (JWT header)    ├─validate JWT       │                 │
 │                 ├─PUBLISH {userId, ONLINE}─►│          │
 │                 │                   │→all instances broadcast /topic/presence
 │                 ├─POST /presence/online/1─────────────►│
 │                 │                   │                 ├─SET presence:1 (TTL 5min)
 │                 │                   │                 ├─SADD presence:online "1"
 │                 │                   │                 │
 │  (browser active — ping every 60s)  │                 │
 ├─POST /presence/ping/1 (every 60s)──────────────────────►│ refreshes TTL
 │                 │                   │                 │
 │  (tab closed)   │                   │                 │
 ├─WS DISCONNECT──►│                   │                 │
 │                 ├─PUBLISH {userId, OFFLINE}            │
 │                 ├─POST /presence/offline/1────────────►│ DEL presence:1, SREM set
```

## 3.9 Edit & Delete Message Flow

```
USER A (edits)    WEBSOCKET-SVC          REDIS           USER B
 │                     │                   │                 │
 ├─STOMP /app/chat.edit►│                   │                 │
 │ {roomId, messageId,  ├─XSS sanitize      │                 │
 │  newContent}         ├─set editorId       │                 │
 │                     ├─PUBLISH chat:edits►│                 │
 │                     │                   │ ▼ RedisSubscriber│
 │                     │                   │ ├─STOMP /topic/room/{id}/edit─►│
 │                     │                   │                 ├─UI updates message in place
```

Delete follows the same pattern via `chat:deletes` channel and `/topic/room/{id}/delete`.

## 3.10 Emoji Reaction Flow

```
USER A (reacts)   FRONTEND                  WEBSOCKET-SVC      REDIS       USER B
 │                 │                              │               │             │
 ├─click 👍────────►│                              │               │             │
 │                 ├─optimistic update (instant)  │               │             │
 │                 ├─POST /messages/{id}/reactions (REST persist)  │             │
 │                 ├─STOMP /app/chat.react────────►│               │             │
 │                 │                              ├─PUBLISH chat:reactions──►│   │
 │                 │                              │               │▼ RedisSubscriber
 │                 │                              │               │ /topic/room/{id}/reactions─►│
 │                 │                              │               │             ├─UI: reaction count +1
```

The optimistic update makes the UI feel instant. REST persists to DB. WebSocket notifies other users.

## 3.11 Gateway Request Flow

```
Request: GET /api/v1/rooms/r1
         Authorization: Bearer eyJhbG...

1. TraceIdFilter (order -2)
   → Generates X-Trace-Id: "a1b2c3d4" (appears in every service's log for this request)

2. JwtAuthenticationFilter (order -1)
   → Validates JWT signature and expiry
   → Checks Redis blacklist (token:blacklist:{token})
   → Extracts: userId=42, email=a@b.com, role=USER
   → Adds headers: X-User-Id: 42, X-User-Email: a@b.com, X-User-Role: USER
   → If invalid → 401 Unauthorized (request stops here)

3. RateLimitFilter (order 0)
   → Redis key: ratelimit:42:{minuteEpoch} → INCR → 47
   → If count ≤ 100 → request continues
   → If count > 100 → 429 Too Many Requests

4. Spring Cloud Gateway routes request to room-service
   → Eureka lookup: "ROOM-SERVICE" → 192.168.1.10:8082
   → Forwards request to http://192.168.1.10:8082/api/v1/rooms/r1
   → Includes all X-User-* headers
```

## 3.12 Cross-Instance Broadcast

How a message reaches all connected clients when multiple websocket-service instances are running:

```
Instance 1               Redis                  Instance 2               Instance 3
 │                          │                       │                        │
 ├─User A sends message     │                       │                        │
 ├─PUBLISH chat:messages───►│                       │                        │
 │                          │──────────────────────►│ onMessage() called     │
 │                          │──────────────────────────────────────────────►│ onMessage() called
 │                          │                       │                        │
 │                          │                       ├─STOMP → User B        │
 │                          │                       │  (connected here)      ├─STOMP → User C
 │                          │                       │                        │  (connected here)
```

Same pattern for `chat:edits`, `chat:deletes`, `chat:reactions`, and `chat:presence`.

---

# PART 4: CODE WALKTHROUGH

## 4.1 Layered Architecture Pattern

Every service uses the same four-layer pattern:

```
HTTP Request
     ↓
Resource (Controller)   ← @RestController — parses request, calls service, formats response
     ↓
Service                 ← @Service — business logic, transactions, validation
     ↓
Repository              ← @Repository — Spring Data JPA, database queries
     ↓
Database (MySQL/Redis)
```

This separation means:
- **Resources** never contain business logic (no `if` statements about business rules)
- **Services** never deal with HTTP (no `HttpServletRequest`)
- **Repositories** never contain business logic (only queries)

## 4.2 JWT Authentication

**Token structure:**
```
Header.Payload.Signature
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiIsInVzZXJuYW1lIjoiam9obiIsImVtYWlsIjoiam9obkBleC5jb20iLCJyb2xlIjoiVVNFUiIsImlhdCI6MTcwMDAwMDAwMCwiZXhwIjoxNzAwMDg2NDAwfQ.ABC...

Decoded payload:
{
  "sub": "42",             ← user ID
  "username": "john",
  "email": "john@ex.com",
  "role": "USER",
  "iat": 1700000000,       ← issued at
  "exp": 1700086400        ← expires (24h later)
}
```

**Three token types:**
- **Access token** (24h): Used in `Authorization: Bearer` header for all API calls
- **Refresh token** (7d): Used only at `POST /auth/refresh` to get a new access token
- **Reset token** (15m): Short-lived, contains `purpose: PASSWORD_RESET`, used only for password reset

**Token blacklist:** On logout, `redis.set("token:blacklist:{token}", "1", 24h)`. The gateway checks this before routing any request.

**WebSocket authentication:** At STOMP `CONNECT`, `JwtChannelInterceptor` reads the `Authorization` header, validates the JWT, and wraps the claims in a `StompPrincipal(id, username)` record. All message handlers access `h.getUser().getName()` for the userId and `.username()` for the display name.

## 4.3 OTP System

```
Generation:
  otp = random 6-digit string (000000–999999)
  redis.set("otp:{purpose}:{email}", otp, TTL_minutes)
  redis.set("otp:cooldown:{purpose}:{email}", "1", 60s)
  redis.delete("otp:attempts:{purpose}:{email}")  // reset attempt counter

Verification:
  attempts = redis.get("otp:attempts:{purpose}:{email}")
  if attempts >= 5: return false (locked out)
  
  redis.incr("otp:attempts:{purpose}:{email}")
  stored = redis.get("otp:{purpose}:{email}")
  
  if stored == null: return false (expired)
  if stored != submitted: return false
  
  redis.delete("otp:{purpose}:{email}")  // one-time use
  return true
```

Security properties:
- **5-attempt limit**: prevents brute force (1-in-200,000 chance per attempt)
- **60-second cooldown**: prevents OTP spam
- **5-minute TTL** (registration) / **10-minute TTL** (reset): limits the attack window
- **Single use**: deleted after successful verification

## 4.4 WebSocket/STOMP

**Connection lifecycle:**
```
1. Frontend opens SockJS connection to ws://localhost:8087/ws
2. Frontend sends STOMP CONNECT with Authorization: Bearer {token}
3. JwtChannelInterceptor validates token, creates StompPrincipal
4. Frontend subscribes: /topic/room/{roomId}, /topic/room/{roomId}/typing, etc.
5. WebSocketEventListener.onConnect() fires:
   → calls POST /presence/online/{uid} on presence-service
   → publishes to chat:presence Redis channel
6. Chat begins
7. On tab close: onDisconnect() fires → presence-service notified offline
```

**Message flow in `handleChat()`:**
```java
1. Cast h.getUser() to StompPrincipal to get both userId and username
2. Generate UUID messageId if not provided
3. Set senderId, senderUsername, timestamp, deliveryStatus="SENT"
4. HTML-escape content (XSS protection)
5. Serialize to JSON, PUBLISH to Redis chat:messages
6. SEND personal confirmation to /user/{uid}/queue/messages
7. Async: call message-service REST to persist (same UUID used)
```

## 4.5 XSS Protection

Applied at two layers to prevent script injection:

**Layer 1 — WebSocket handler:**
```java
if (p.getContent() != null) p.setContent(HtmlUtils.htmlEscape(p.getContent()));
```

**Layer 2 — Message service (storage):**
```java
if (msg.getContent() != null) msg.setContent(HtmlUtils.htmlEscape(msg.getContent()));
```

`HtmlUtils.htmlEscape()` converts: `<` → `&lt;`, `>` → `&gt;`, `"` → `&quot;`, `&` → `&amp;`

So `<script>alert(1)</script>` becomes `&lt;script&gt;alert(1)&lt;/script&gt;` — harmless text rendered by the browser.

## 4.6 Rate Limiting

```
Key pattern: ratelimit:{userId}:{minuteEpoch}
// e.g., ratelimit:42:28456123

redis.incr(key)
// If count == 1: set 2-minute TTL (key auto-expires)
// If count > 100: return HTTP 429 Too Many Requests
```

Each minute gets its own counter. Counters auto-expire after 2 minutes, preventing unbounded key growth.

## 4.7 Cursor-Based Pagination

**Problem with offset pagination:**
```
First load:  GET /messages/room/r1?limit=50         → messages M100..M51
New message M101 arrives.
Scroll up:   GET /messages/room/r1?limit=50&offset=50 → returns M101..M52 (M51 duplicated!)
```

**Cursor-based solution:**
```
First load:  GET /messages/room/r1?limit=50
             → messages [M100, M99, ..., M51]
             → client notes: oldest.sentAt = "2024-01-15T10:30:00"

Scroll up:   GET /messages/room/r1?before=2024-01-15T10:30:00&limit=50
             → messages strictly before that timestamp: [M50, M49, ..., M1]
             → no duplicates regardless of new arrivals
```

SQL query: `WHERE sentAt < :before AND isDeleted = false ORDER BY sentAt DESC LIMIT :limit`

---

# PART 5: TESTING & QUALITY

## 5.1 Running Tests

```bash
# Run all tests across all modules
mvn test

# Run tests for a specific service
cd auth-service && mvn test

# Run a specific test class
mvn test -Dtest=AuthServiceImplTest

# Run a specific test method
mvn test -Dtest=MessageServiceTest#send_sanitizesXSS

# Run tests with verbose output
mvn test -Dtest=ChatWebSocketHandlerTest -pl websocket-service
```

### Test Coverage by Module

| Test Class | Tests | What It Verifies |
|---|---|---|
| `AuthServiceImplTest` | 30 tests | Register, login, logout, OTP flows, reset password, profile update, status, admin controls |
| `OtpServiceTest` | 11 tests | Generation, verification, attempt limit, cooldown, TTL, one-time use |
| `RoomServiceTest` | 24 tests | Create GROUP/DM, getRoom, member CRUD, roles, mute, pin, full-room guard |
| `MessageServiceTest` | 22 tests | XSS, SENT status, pagination, edit/delete, search, reactions, unread counts |
| `PresenceServiceTest` | 20 tests | Online/offline, JSON storage, bulk get, ping, stale cleanup, online count |
| `ChatWebSocketHandlerTest` | 17 tests | UUID generation, sender enrichment, XSS, Redis channel routing for all 6 handlers |
| `WebSocketEventListenerTest` | 8 tests | Connect/disconnect events, presence-service REST calls, graceful REST failure |
| `RedisMessageSubscriberTest` | 8 tests | All 5 Redis channels fan-out to correct STOMP topics; unknown channel no-op; error resilience |
| `MessagePersistenceServiceTest` | 7 tests | messageId passthrough, mediaUrl inclusion, null handling, REST failure tolerance |

**Total: 147 tests**

## 5.2 JaCoCo Coverage

```bash
# Generate coverage report for all modules
mvn verify

# Open report for a specific service
open auth-service/target/site/jacoco/index.html
open websocket-service/target/site/jacoco/index.html

# Aggregate report is generated by the full reactor
mvn verify
```

JaCoCo is configured in the parent `pom.xml` with:
- **80% line coverage minimum** for every service module
- **80%+ aggregate line coverage** in `code-coverage-report/target/site/jacoco-aggregate`
- **Excluded from coverage**: `dto/`, `entity/`, `config/`, `exception/`, `model/`, `*Application.java`
- Build **fails** if coverage drops below 80% — enforced in CI

## 5.3 SonarQube Analysis

```bash
# Start SonarQube, verify coverage, and publish separate projects
./run-sonar.ps1 -sonarToken <token>

# Projects created:
# - connecthub-overall
# - connecthub-auth-service
# - connecthub-room-service
# - one project for each remaining service
```

---

# PART 6: API REFERENCE

All APIs accessed via gateway at `http://localhost:8080`. Authenticated routes require `Authorization: Bearer {token}`.

## Auth APIs (`/api/v1/auth`)

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/register` | No | Register — sends OTP to email |
| POST | `/verify-registration-otp` | No | Verify OTP, get JWT |
| POST | `/resend-registration-otp` | No | Resend OTP (60s cooldown) |
| POST | `/login` | No | Login with email + password |
| POST | `/logout` | Yes | Blacklist current token |
| POST | `/refresh` | No | Get new access token with refresh token |
| GET | `/validate?token=x` | No | Check if token is valid |
| POST | `/forgot-password` | No | Send reset OTP |
| POST | `/verify-reset-otp` | No | Verify reset OTP, get reset token |
| POST | `/reset-password` | No | Set new password with reset token |
| GET | `/profile/{userId}` | Yes | Get user profile |
| PUT | `/profile/{userId}` | Yes | Update name, username, avatar, bio |
| PUT | `/password/{userId}` | Yes | Change password |
| GET | `/search?q=john` | Yes | Search users by username/email |
| PUT | `/status/{userId}` | Yes | Set status (ONLINE/AWAY/DND/INVISIBLE) |

## Room APIs (`/api/v1/rooms`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/` | Create room (GROUP or DM) |
| GET | `/{roomId}` | Get room details |
| GET | `/user/{userId}` | List user's rooms |
| PUT | `/{roomId}` | Update room name/description/avatar |
| DELETE | `/{roomId}` | Delete room |
| POST | `/{roomId}/members/{userId}` | Add member |
| DELETE | `/{roomId}/members/{userId}` | Remove member |
| GET | `/{roomId}/members` | List members |
| PUT | `/{roomId}/members/{userId}/role` | Change role (ADMIN/MODERATOR/MEMBER) |
| PUT | `/{roomId}/members/{userId}/mute?muted=true` | Mute/unmute member |
| PUT | `/{roomId}/pin/{messageId}` | Pin a message |

## Message APIs (`/api/v1/messages`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/` | Save message (called by websocket-service internally) |
| GET | `/room/{roomId}?before=&limit=50` | Get messages (cursor pagination) |
| PUT | `/{messageId}` | Edit message content |
| DELETE | `/{messageId}` | Soft delete message |
| GET | `/room/{roomId}/search?keyword=` | Full-text search |
| PUT | `/{messageId}/status` | Update delivery status |
| POST | `/{messageId}/reactions` | Add emoji reaction |
| DELETE | `/{messageId}/reactions?emoji=👍` | Remove reaction |
| GET | `/{messageId}/reactions` | Get all reactions |
| GET | `/room/{roomId}/unread?lastReadAt=` | Get unread count |
| DELETE | `/room/{roomId}/clear` | Clear all messages in room |

## Presence APIs (`/api/v1/presence`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/online/{userId}` | Mark user online (called by websocket-service) |
| POST | `/offline/{userId}` | Mark user offline (called by websocket-service) |
| POST | `/ping/{userId}` | Keepalive ping — refreshes Redis TTL (called by frontend every 60s) |
| GET | `/{userId}` | Get user's presence (status, device, last seen) |
| POST | `/bulk` | Get presence for multiple users |
| PUT | `/status/{userId}` | Update custom status message |
| GET | `/online/count` | Total number of online users |
| GET | `/{userId}/check` | Boolean online/offline check |

---

# PART 7: EVALUATION Q&A

## "Explain your project in 2 minutes"

> ConnectHub is a real-time chat application built with 9 Spring Boot microservices. Each service owns a single domain: authentication, rooms, messages, media uploads, presence tracking, notifications, WebSocket connections, API routing, and service discovery.
>
> Users register with email OTP verification, log in to receive a JWT, then connect over WebSocket for real-time chat. When a message is sent, the server generates a UUID for it, broadcasts it instantly through Redis Pub/Sub to all connected clients in under 10 milliseconds, then persists it asynchronously — the sender doesn't wait for the database write.
>
> The system supports typing indicators, read receipts, emoji reactions, message edits and deletes — all broadcast in real-time through dedicated Redis channels. Presence is tracked via a Redis-only service with a keepalive ping pattern. Security includes JWT validation at the gateway, BCrypt hashing, XSS sanitization, rate limiting, and token blacklisting on logout.

## "Why microservices instead of a monolith?"

> Three concrete reasons: **independent scaling** — WebSocket connections and user auth have completely different load profiles, so we can scale just the WebSocket service during peak hours; **fault isolation** — if the email service (SMTP) is slow, only notification-service is affected, chat keeps working; **technology fit** — presence-service uses Redis exclusively because presence data is ephemeral and needs sub-millisecond reads, while message-service needs relational SQL for complex queries. Forcing them into one database would mean compromising on both.

## "How does real-time messaging work?"

> A STOMP frame travels over WebSocket to the websocket-service. The handler generates a UUID messageId, enriches it with the sender's identity from the JWT principal, sanitizes content for XSS, then publishes to the `chat:messages` Redis channel. Every websocket-service instance subscribes to that channel, so `RedisMessageSubscriber` on all instances broadcasts the message to their locally-connected clients via `/topic/room/{roomId}`. Persistence happens asynchronously using the same pre-generated UUID, so the DB record matches the ID that clients already have. The whole broadcast path takes about 10ms.

## "How do you handle security?"

> Five layers: **JWT** validated at the gateway before any request reaches a service — downstream services trust the `X-User-Id` header in a private network. **Passwords** hashed with BCrypt strength 12 — intentionally slow. **XSS** prevented by HTML-escaping all user content in both the WebSocket handler and the message service. **Rate limiting** at 100 requests/minute per user via Redis atomic counters. **Token blacklisting** on logout stores the token hash in Redis with TTL matching token expiry.

## "How does edit/delete work in real-time?"

> When a user edits a message, the frontend calls `PUT /messages/{id}` to persist the change, then sends a STOMP frame to `/app/chat.edit`. The handler sanitizes the new content, serializes it, and publishes to the `chat:edits` Redis channel — not directly to the STOMP broker. This is important: if there are three WebSocket service instances running, a direct `convertAndSend()` would only reach clients on that one instance. Going through Redis ensures every instance's `RedisMessageSubscriber` receives the event and broadcasts `/topic/room/{id}/edit` to all their connected clients.

## "How does presence work?"

> When a WebSocket connects, `WebSocketEventListener.onConnect()` fires. It calls `POST /presence/online/{uid}` on the presence-service REST API, which stores a Redis key `presence:{uid}` (JSON with status, device, timestamps) with a 5-minute TTL and adds the user to a `presence:online` set. It also publishes to `chat:presence` so all connected frontends update their online indicators. The frontend sends a keepalive ping every 60 seconds to refresh the TTL. A scheduled job runs every 60 seconds and removes users who haven't pinged in 90 seconds — this handles ungraceful disconnections where the WebSocket close event was never received.

## "What happens if a service goes down?"

> Graceful degradation by design. If message-service is down, real-time messaging still works — messages broadcast instantly, persistence will fail but the user still sees the message (it may be lost on reload). If notification-service is down, chat works normally, emails don't send. If presence-service is down, online indicators stop updating but chat continues. The API gateway uses Resilience4j circuit breakers to fail fast rather than queue up timeouts. The biggest single point of failure is Redis — if Redis goes down, WebSocket broadcast, presence, OTP, and rate limiting all fail simultaneously, which is why Redis should be run in cluster/sentinel mode in production.

## "What's Flyway and why did you use it?"

> Flyway is a database migration tool. Instead of `ddl-auto: update` (which Hibernate can use to auto-modify tables but can cause data loss and isn't deterministic), we define versioned SQL files: `V1__initial_schema.sql`, `V2__add_bio_column.sql`, etc. On startup, Flyway checks which migrations have already run and applies only new ones — it's idempotent and safe. It records migrations in a `flyway_schema_history` table. We use `ddl-auto: validate` with Flyway, so Hibernate checks that entities match the schema but never modifies it. This gives us reproducible, version-controlled database changes.

## "How do you guarantee messageId consistency between WebSocket and the database?"

> The UUID is generated in `ChatWebSocketHandler.handleChat()` before anything else, then set on the payload. The broadcast goes out with this UUID, the personal confirmation to the sender includes it, and `MessagePersistenceService.persistMessage()` includes it in the REST body to message-service. Previously the UUID was generated by the database on insert, which meant the broadcast had `messageId: null`. Clients couldn't reply to, react to, edit, or delete messages until the page was refreshed and messages were loaded from the REST API.

## "What is Redis Pub/Sub and why does your system need it?"

> Redis Pub/Sub is a fire-and-forget message distribution mechanism. A publisher sends to a named channel; every subscriber on that channel receives a copy. We use it because websocket-service is designed to be horizontally scalable — in production you'd run 3+ instances behind a load balancer. A STOMP message arrives at one instance, but users on the other two instances need it too. Direct `convertAndSend()` only delivers locally. By publishing to Redis, all instances subscribe and each delivers to their locally-connected clients. We use five channels: `chat:messages`, `chat:presence`, `chat:edits`, `chat:deletes`, `chat:reactions`.
