# Authentication & User Flow Diagrams

The diagrams below represent the standard sequence flows for user management processes happening via the ConnectHub Gateway.

## 1. User Registration Flow

```mermaid
sequenceDiagram
    actor User
    participant Gateway as API Gateway
    participant Auth as Auth-Service
    participant Redis as Redis
    participant Notif as Notification-Service
    participant SMTP as SMTP Email Server

    User->>Gateway: POST /api/v1/auth/register (UserDetails)
    Gateway->>Auth: Forward Request

    Auth->>Auth: Check email & username unique
    Auth->>Auth: BCrypt hash password
    Auth->>Auth: Save pending DB record (isEmailVerified=false)
    Auth->>Auth: Generate 6-digit OTP

    Auth->>Redis: SET otp:register:{email} (TTL 5 mins)
    Auth->>Redis: SET otp:cooldown:register:{email} (TTL 60s)
    
    Auth->>Redis: PUBLISH email:send
    Auth-->>Gateway: 201 Created (Verify Pending)
    Gateway-->>User: 201 Created (Verify Pending)

    Redis-->>Notif: trigger onMessage() (event: email:send)
    Notif->>SMTP: Async Send OTP Email
    SMTP-->>User: Email Delivered!

    note over User, Auth: User submits OTP
    User->>Gateway: POST /api/v1/auth/verify-registration-otp
    Gateway->>Auth: Forward

    Auth->>Redis: GET otp:register:{email}
    Redis-->>Auth: return OTP

    alt OTP Matches
        Auth->>Auth: UPDATE DB (isEmailVerified=true)
        Auth->>Auth: Generate JWT Access & Refresh Tokens
        Auth-->>User: 200 OK + { accessToken, refreshToken }
    else OTP Incorrect/Expired
        Auth-->>User: 400 Bad Request
    end
```

## 2. Login Flow with JWT & Filtering

```mermaid
sequenceDiagram
    actor User
    participant Gateway as API Gateway
    participant JWT as JwtAuthenticationFilter
    participant AuthLayer as Auth-Service
    participant DB as User Database

    User->>Gateway: POST /api/v1/auth/login
    
    note over Gateway, JWT: Gateway routes request bypassing JWT verification 
    Gateway->>AuthLayer: Forward /login
    
    AuthLayer->>DB: findByEmail(email)
    DB-->>AuthLayer: Returns encoded password
    
    AuthLayer->>AuthLayer: BCrypt.matches(raw, hash)
    
    alt Correct Password
        AuthLayer->>AuthLayer: generate accessToken (24h)
        AuthLayer->>AuthLayer: generate refreshToken (7d)
        AuthLayer-->>User: 200 OK + { JWT }
    else Incorrect Password
        AuthLayer-->>User: 401 Unauthorized
    end
    
    note over User, Gateway: Subsequence Authenticated Request
    User->>Gateway: GET /api/v1/rooms/user/1 (Header: Bearer eyJh...)
    
    Gateway->>JWT: pre-filter check
    JWT->>JWT: Validate Token Signature & Expiry
    JWT->>Redis: Check Blacklist (token:blacklist:eyJh...)
    Redis-->>JWT: Not Blacklisted
    
    JWT->>Gateway: Appends X-User-Id, X-User-Role headers
    Gateway->>RoomService: GET /api/v1/rooms/user/1
```

## 3. Forgot Password Flow

```mermaid
sequenceDiagram
    actor User
    participant Auth as Auth-Service
    participant Redis as Redis
    participant Email as Notification-Service

    User->>Auth: POST /forgot-password {email}
    Auth->>Auth: find user (fail silently if not found)
    
    Auth->>Redis: SET otp:reset:{email} (TTL 10min)
    Auth->>Redis: PUBLISH email:send 
    Auth-->>User: 200 Code Sent
    
    Redis-->>Email: pub/sub hook
    Email-->>User: OTP email delivered
    
    User->>Auth: POST /verify-reset-otp {email, OTP}
    Auth->>Redis: verify OTP
    Auth->>Auth: generate ResetToken (Purpose=PASSWORD_RESET, 15m)
    Auth-->>User: 200 OK + { resetToken }
    
    User->>Auth: POST /reset-password {resetToken, newPass}
    Auth->>Auth: validate Token & Purpose
    Auth->>Auth: BCrypt hash newPass
    Auth->>Redis: Invalidate existing sessions
    Auth-->>User: 200 OK
```
