# BFF template (Spring Cloud Gateway, reactive)

The product's backend-for-frontend: the one component the internet reaches for its web app and
its mobile apps. It signs people in with the product's Keycloak realm, keeps their tokens on the
server, and serves the web app and its API from one origin; mobile apps reach the same API with
their own tokens.

```
Browser ──(__Host-SESSION cookie only)──> BFF ──(person's access token)──> services (/api/**)
                                           └──────────(no cookies)───────> web app pages (/**)
Mobile app ──(bearer token, X-App-Version)──> BFF /mobile/api/** ──(same token)──> services
```

## What you get

| Concern | How |
| --- | --- |
| Sign-in | OpenID Connect authorization code flow **with PKCE**, as a confidential client of the realm (`BFF_CLIENT_ID`, `BFF_CLIENT_SECRET`) |
| Tokens | Never in the browser. Stored in the person's session in **Redis** (`REDIS_URL`), so every replica can relay and refresh them. Outside local work the BFF refuses to start unless `REDIS_URL` is `rediss://` (TLS) |
| Sign-in audit | Sign-ins, failed sign-ins (with the OAuth error code) and sign-outs go to the product's audit trail (`vrixel.audit.v1`, `AUDIT_SINK=kafka`), by person id, never name or token; sent without waiting, so an audit outage never blocks signing in. PHI and payments products must use TLS to Kafka |
| Limits | Per person, across replicas (Redis): `BFF_RATE_PER_SECOND` (20) with bursts of `BFF_RATE_BURST` (40) on `/api/**` and `/mobile/api/**`, then 429. Services must connect within `BFF_CONNECT_TIMEOUT_MS` (2000) and answer within `BFF_RESPONSE_TIMEOUT` (30s), or the caller gets 504 |
| Session cookie | `__Host-SESSION`: HttpOnly, Secure, SameSite=Strict, Path=/, no Domain. A new session id at sign-in |
| API calls | `/api/**` is proxied to the services with the person's access token (`TokenRelay`), refreshed when it expires; the session cookie is removed. Signed-out callers get 401, never a redirect |
| Pages | Everything else is proxied to the web component, without cookies. One origin: no CORS |
| Cross-site writes | Refused three ways: SameSite=Strict, a CSRF token (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header) on every write, and an Origin check (`BFF_ORIGINS`) |
| Sign-out | `POST /auth/logout` ends the session here and answers `{"redirect": ...}`: the web app navigates there to end the Keycloak session too (RP-initiated logout) |
| Mobile apps | `/mobile/api/**`: the app's own bearer token (it is a public client: PKCE, no secret), checked here for issuer, expiry and audience (`BFF_MOBILE_AUDIENCE`), then passed on with `/mobile` stripped. No session and no cookies, so no CSRF. Versions older than `BFF_MOBILE_MIN_VERSION` get 426 and the app asks the person to update |
| Gradual releases | `POST /flag-cohorts` (web: session and CSRF token) and `POST /mobile/flag-cohorts` (mobile: bearer token) take the apps' per-flag counts each minute and log one `vrixel.flag_cohorts` line per flag and app a minute (`FlagCohortRelay`). Signed-in people only, once a minute each, at most 120 per side: one person can't swing the SDLC tool's comparison |
| Headers | HSTS (behind the gateway's TLS, via `X-Forwarded-*`), `X-Frame-Options: DENY`, `Referrer-Policy: same-origin` |
| Exposure | `workload.yaml`: the only `external` endpoint of the product's web app; health and metrics on the internal management port |

## For the web app

- `GET /auth/me` → `{"authenticated": true, "sub": "...", "name": "..."}` (also sets `XSRF-TOKEN`).
- `GET /auth/login` → sign in. `POST /auth/logout` (with `X-XSRF-TOKEN`) → `{"redirect": url}`;
  set `window.location` to it to finish signing out at Keycloak.
- `fetch("/api/...", { headers: { "X-XSRF-TOKEN": <cookie> } })` for writes. No tokens, no CORS.

## For the mobile apps (`template-mobile`)

- `GET|PUT|... /mobile/api/...` with `Authorization: Bearer <access token>` and
  `X-App-Version: 1.4.0`. 401 when the token is missing, expired or for another audience; 426
  (problem details) when the version is older than the oldest supported.
- Flags come from `/ofrep/` like the web app's (through the web route, no token).

Raise `BFF_MOBILE_MIN_VERSION` only once few people use older versions (the stores show how many),
and keep the API compatible with every version above it.

## Gradual releases: the apps' counts

While the SDLC tool turns a feature on for a growing share of users, it compares people with the
flag on against people with it off, per component, from `vrixel.flag_cohorts` log lines. The
services count their own requests. The web and mobile apps count user-minutes: a minute in which
the app used a flag, failed if it had an unhandled error or a failed API call. They post these
counts here once a minute:

```json
{"flags": {"emr.emr-feat-3.check-in-kiosk": {"on": {"requests": 1, "errors": 0}}}}
```

The answer is 202 when the counts are added, 429 when the same person already posted within the
minute, and 400 when the counts aren't ones a single person could have: more than 50 flags, more
than 120 on a side, errors above the total, or not whole numbers. Once a minute the BFF logs the
totals with `"source": "web"` or `"mobile"`. Only counts are kept, never a user, page or error.

The BFF checks no flags of its own. It forwards requests to the services, which already count
them, so counting them here as well would count each request twice.

## Routes

`spring.cloud.gateway.server.webflux.routes` in `application.yaml`: one `api` route per service
(`Path=/api/v1/patients/**` → `PATIENTS_URL`), each with `SaveSession`, `TokenRelay=` and
`RemoveRequestHeader=Cookie`, then the `web` route last. Service URLs come from the dependencies in
`workload.yaml`, so OpenChoreo also allows exactly that traffic.

## Settings

`BFF_ISSUER` (`https://id.<domain>/realms/<product>`), `BFF_CLIENT_ID`, `BFF_CLIENT_SECRET`,
`BFF_SCOPES` (`openid,profile` plus the product's API scopes), `BFF_ORIGINS`, `REDIS_URL`,
`API_URL`, `WEB_URL`, `BFF_SESSION_TIMEOUT` (default 30m), `BFF_MOBILE_AUDIENCE` (the product's
API audience; empty: no mobile app, `/mobile/**` refuses everything), `BFF_MOBILE_MIN_VERSION`,
`BFF_RATE_PER_SECOND`, `BFF_RATE_BURST`, `BFF_CONNECT_TIMEOUT_MS`, `BFF_RESPONSE_TIMEOUT`, and the
audit trail's `AUDIT_SINK`, `AUDIT_KAFKA_BOOTSTRAP`, `AUDIT_TOPIC`, `PRODUCT`, `DATA_CLASS` (extra
Kafka settings under `app.audit.kafka`).

Errors and traces: every response carries a W3C `traceresponse` header (a service's own, passed
through, or this request's span here), which the apps put on the errors they report. With
`SENTRY_DSN` set, unhandled exceptions and `ERROR` logs go to Sentry, scrubbed by `ErrorReports`
(type, stack, path, `trace_id`, and a `component` tag so the SDLC tool routes the issue here; never
messages, cookies, headers, codes or tokens). Also `ENVIRONMENT` and `SENTRY_RELEASE`. The tool
puts the component's name in the files `.vrixel/identity` lists when it creates a repo from this
template.

## Why Spring Cloud Gateway

A BFF waits on the network. Netty's event loop serves many concurrent requests per pod across all
cores, and Spring Security's OAuth client handles PKCE, token storage, refresh, CSRF and session
fixation. Node would use less memory per pod; for a long-running edge component, throughput per
pod and the maturity of the security stack matter more.

## Develop

```bash
./gradlew test    # integration tests start Redis in a container (needs Docker)
```
