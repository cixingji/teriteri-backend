# Authentication and administrator access

The application uses a 30-minute JWT access token and a rotating, opaque 30-day refresh token.
Refresh tokens are stored only in HttpOnly `SameSite=Lax` cookies. User and administrator cookies
are separated so both local frontends can stay signed in at the same time. Redis stores hashed refresh
tokens and session metadata, supports up to ten devices per user, and provides current-session and
all-device logout.

## Database migration

For an existing database, run `database/auth_upgrade.sql` once. A fresh database can use
`database/teriteri.sql` directly.

## Windows development configuration

Set the following values before starting the backend:

```powershell
$env:AUTH_JWT_SECRET = '<at-least-32-random-characters>'
$env:GITHUB_OAUTH_CLIENT_ID = '<github-oauth-client-id>'
$env:GITHUB_OAUTH_CLIENT_SECRET = '<github-oauth-client-secret>'
.\mvnw.cmd spring-boot:run
```

Without `AUTH_JWT_SECRET`, a temporary key is generated in non-production profiles. Existing
sessions then become invalid after a restart. A production profile refuses to start without the
secret.

## GitHub OAuth App

Create an OAuth App in GitHub account settings with:

- Homepage URL: `http://localhost:8080`
- Authorization callback URL: `http://localhost:7070/oauth/callback/github`

Keep the Client Secret in the Windows environment; never add it to Git.

## Roles

- `role=0`: user
- `role=1`: administrator; video review, content management, and user locking
- `role=2`: super administrator; additionally grants or revokes administrator status

Administrators must use `/admin/account/login`. Sessions created by the regular user login do not
receive administrator authorities, even when the account has an administrator role.
