# BFF template (Spring Cloud Gateway, reactive)

The product's backend-for-frontend: the one component the internet reaches for its web app. It
signs people in with the product's Keycloak realm, keeps their tokens on the server, and serves the
web app and its API from one origin.

```
Browser ──(__Host-SESSION cookie only)──> BFF ──(person's access token)──> services (/api/**)
                                           └──────────(no cookies)───────> web app pages (/**)
```

## What you get

| Concern | How |
| --- | --- |
| Sign-in | OpenID Connect authorization code flow **with PKCE**, as a confidential client of the realm (`BFF_CLIENT_ID`, `BFF_CLIENT_SECRET`) |
| Tokens | Never in the browser. Stored in the person's session in **Redis** (`REDIS_URL`), so every replica can relay and refresh them |
| Session cookie | `__Host-SESSION`: HttpOnly, Secure, SameSite=Strict, Path=/, no Domain. A new session id at sign-in |
| API calls | `/api/**` is proxied to the services with the person's access token (`TokenRelay`), refreshed when it expires; the session cookie is removed. Signed-out callers get 401, never a redirect |
| Pages | Everything else is proxied to the web component, without cookies. One origin: no CORS |
| Cross-site writes | Refused three ways: SameSite=Strict, a CSRF token (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header) on every write, and an Origin check (`BFF_ORIGINS`) |
| Sign-out | `POST /auth/logout` ends the session here and answers `{"redirect": ...}`: the web app navigates there to end the Keycloak session too (RP-initiated logout) |
| Headers | HSTS (behind the gateway's TLS, via `X-Forwarded-*`), `X-Frame-Options: DENY`, `Referrer-Policy: same-origin` |
| Exposure | `workload.yaml`: the only `external` endpoint of the product's web app; health and metrics on the internal management port |

## For the web app

- `GET /auth/me` → `{"authenticated": true, "sub": "...", "name": "..."}` (also sets `XSRF-TOKEN`).
- `GET /auth/login` → sign in. `POST /auth/logout` (with `X-XSRF-TOKEN`) → `{"redirect": url}`;
  set `window.location` to it to finish signing out at Keycloak.
- `fetch("/api/...", { headers: { "X-XSRF-TOKEN": <cookie> } })` for writes. No tokens, no CORS.

## Routes

`spring.cloud.gateway.server.webflux.routes` in `application.yaml`: one `api` route per service
(`Path=/api/v1/patients/**` → `PATIENTS_URL`), each with `SaveSession`, `TokenRelay=` and
`RemoveRequestHeader=Cookie`, then the `web` route last. Service URLs come from the dependencies in
`workload.yaml`, so OpenChoreo also allows exactly that traffic.

## Settings

`BFF_ISSUER` (`https://id.<domain>/realms/<product>`), `BFF_CLIENT_ID`, `BFF_CLIENT_SECRET`,
`BFF_SCOPES` (`openid,profile` plus the product's API scopes), `BFF_ORIGINS`, `REDIS_URL`,
`API_URL`, `WEB_URL`, `BFF_SESSION_TIMEOUT` (default 30m).

## Why Spring Cloud Gateway

A BFF waits on the network. Netty's event loop serves many concurrent requests per pod across all
cores, and Spring Security's OAuth client handles PKCE, token storage, refresh, CSRF and session
fixation. Node would use less memory per pod; for a long-running edge component, throughput per
pod and the maturity of the security stack matter more.

## Develop

```bash
./gradlew test    # integration tests start Redis in a container (needs Docker)
```
