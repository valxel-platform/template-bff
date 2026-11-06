/**
 * The product's backend-for-frontend: the only component the internet reaches for its web app.
 *
 * <ul>
 *   <li>Signs people in with the product's Keycloak realm (OpenID Connect authorization code flow
 *       with PKCE) as a confidential client.
 *   <li>Keeps their tokens on the server, in the session (Redis, so any replica serves any
 *       request). The browser holds only a {@code __Host-SESSION} cookie: HttpOnly, Secure,
 *       SameSite=Strict. No token ever reaches the browser, so no script can steal one.
 *   <li>Forwards {@code /api/**} to the product's services with the person's access token
 *       (TokenRelay), refreshing it when it expires, and strips the session cookie on the way.
 *   <li>Serves the web app's pages from the web component, so the app and its API share one origin:
 *       no CORS.
 *   <li>Lets the product's mobile apps reach the same services through {@code /mobile/api/**} with
 *       their own bearer tokens, checked here and passed on (see {@link com.vrixel.bff.MobileSecurityConfig}).
 *   <li>Refuses cross-site writes: SameSite cookies, a CSRF token on every state-changing request,
 *       and an Origin check.
 * </ul>
 */
package com.vrixel.bff;
