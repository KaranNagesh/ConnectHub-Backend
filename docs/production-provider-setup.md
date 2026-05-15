# ConnectHub Provider Setup

This file lists the remaining real-world setup work that cannot be faked locally because it depends on third-party credentials.

## OAuth login

ConnectHub uses Spring Security's default OAuth callback shape:

- Local Google callback: `http://localhost:8080/login/oauth2/code/google`
- Local GitHub callback: `http://localhost:8080/login/oauth2/code/github`
- Production callback: `https://YOUR_API_DOMAIN/login/oauth2/code/google`
- Production callback: `https://YOUR_API_DOMAIN/login/oauth2/code/github`

Set these in `ConnectHub-Backend-main/.env`:

```env
GATEWAY_URL=http://localhost:8080
FRONTEND_URL=http://localhost:4200
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
GITHUB_CLIENT_ID=...
GITHUB_CLIENT_SECRET=...
```

Use `/oauth2/authorization/google` and `/oauth2/authorization/github` through the API gateway. Spring documents the default redirect URI template as `{baseUrl}/login/oauth2/code/{registrationId}`, and Google requires the redirect URI to exactly match the configured value.

Sources: [Spring Security OAuth2 login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html), [Google OAuth web server apps](https://developers.google.com/identity/protocols/oauth2/web-server), [GitHub OAuth web flow](https://docs.github.com/apps/building-oauth-apps/authorizing-oauth-apps).

## S3 media

Media uploads now allow 25MB files and downloads are generated with 24-hour pre-signed URLs from:

```http
GET /api/v1/media/{mediaId}/download-url
```

Set:

```env
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
AWS_S3_BUCKET=connecthub-media-prod
AWS_REGION=ap-south-1
```

The IAM principal only needs object-level access for the media bucket: `s3:PutObject`, `s3:GetObject`, and `s3:DeleteObject`. AWS notes that SDK-generated pre-signed URLs can be valid up to 7 days; ConnectHub intentionally uses 24 hours.

Source: [Amazon S3 pre-signed URLs](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html).

## Email

Registration OTP, login OTP, password reset, welcome, subscription, and missed direct-message emails are sent through SMTP.

Set:

```env
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=...
MAIL_PASSWORD=...
```

For Gmail, use an app password from the Google account security page, not the normal account password.

Missed direct-message email behavior:

- Triggered only for DM rooms.
- Triggered only when the recipient's `lastSeenAt` is older than 30 minutes.
- Sent by `notification-service` after it consumes `notifications.offline`.

## Firebase push notifications

Firebase legacy FCM APIs are deprecated and shutdown began in July 2024, so ConnectHub uses the HTTP v1 service-account flow.

Set:

```env
FIREBASE_PROJECT_ID=your-firebase-project-id
FIREBASE_CLIENT_EMAIL=firebase-adminsdk-...@your-project.iam.gserviceaccount.com
FIREBASE_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n"
```

Mobile/web clients should register their FCM token after login:

```http
POST /api/v1/notifications/devices
Authorization: Bearer <jwt>
Content-Type: application/json

{ "token": "<fcm-registration-token>", "platform": "WEB" }
```

Source: [Firebase migrate to HTTP v1](https://firebase.google.com/docs/cloud-messaging/migrate-v1).
