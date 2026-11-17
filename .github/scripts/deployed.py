"""Checks the deployed BFF from outside, as a browser would before signing in.

Runs after each deploy (post-deploy.yml) against BASE_URL, the BFF's public URL: integration in
dev, end-to-end in staging, smoke in production. Nothing here signs in or changes anything, so it
is safe in production. Writes JUnit XML for report.py.

  me            GET /auth/me answers 200 with {"authenticated": false}, names its trace
                (traceresponse) and sends nosniff (and HSTS over https)
  login         GET /auth/login sends the browser to the product's realm with PKCE: 302 to
                /oauth2/authorization/keycloak, then 302 to Keycloak's authorization endpoint
                with code_challenge_method=S256
  csrf          POST /auth/logout without a CSRF token is refused (403)
  cookies       every cookie it sets is Secure and HttpOnly (over https), with SameSite
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from http.client import HTTPResponse
from xml.sax.saxutils import escape

TRACE = re.compile(r"^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):  # type: ignore[no-untyped-def]
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def call(url: str, method: str = "GET") -> tuple[int, dict[str, list[str]], bytes]:
    req = urllib.request.Request(url, method=method, headers={"Accept": "application/json"})
    try:
        resp: HTTPResponse = OPENER.open(req, timeout=15)
        status, headers, body = resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as err:
        status, headers, body = err.code, err.headers, err.read()
    out: dict[str, list[str]] = {}
    for k, v in headers.items():
        out.setdefault(k.lower(), []).append(v)
    return status, out, body


def first(headers: dict[str, list[str]], name: str) -> str:
    return (headers.get(name) or [""])[0]


def checks(base: str) -> dict[str, Callable[[], None]]:
    https = base.startswith("https://")
    seen_cookies: list[str] = []

    def me() -> None:
        status, h, body = call(f"{base}/auth/me")
        assert status == 200, f"/auth/me answered {status}"
        assert json.loads(body or b"{}").get("authenticated") is False, "/auth/me should say signed out"
        assert TRACE.match(first(h, "traceresponse")), "no traceresponse header naming the trace"
        assert first(h, "x-content-type-options").lower() == "nosniff", "no X-Content-Type-Options: nosniff"
        if https:
            assert "max-age" in first(h, "strict-transport-security"), "no Strict-Transport-Security over https"
        seen_cookies.extend(h.get("set-cookie", []))

    def login() -> None:
        status, h, _ = call(f"{base}/auth/login")
        assert status == 302 and first(h, "location").endswith("/oauth2/authorization/keycloak"), (
            f"/auth/login answered {status} to {first(h, 'location')!r}")
        seen_cookies.extend(h.get("set-cookie", []))
        status, h, _ = call(f"{base}/oauth2/authorization/keycloak")
        where = first(h, "location")
        assert status == 302 and "/protocol/openid-connect/auth" in where, f"sign-in didn't go to the realm: {status}"
        assert "code_challenge_method=S256" in where, "sign-in doesn't use PKCE (S256)"
        seen_cookies.extend(h.get("set-cookie", []))

    def csrf() -> None:
        status, _, _ = call(f"{base}/auth/logout", method="POST")
        assert status == 403, f"POST /auth/logout without a CSRF token answered {status}"

    def cookies() -> None:
        for c in seen_cookies:
            name, low = c.split("=", 1)[0], c.lower()
            if https:
                assert "secure" in low and "httponly" in low, f"cookie {name} isn't Secure and HttpOnly"
            assert "samesite" in low or name.startswith("XSRF"), f"cookie {name} has no SameSite"

    return {"me": me, "login": login, "csrf": csrf, "cookies": cookies}


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", required=True)
    p.add_argument("--junit", required=True)
    args = p.parse_args(argv)
    base = args.base_url.rstrip("/")
    cases = []
    for name, check in checks(base).items():
        started = time.monotonic()
        try:
            check()
            failure = ""
        except Exception as exc:  # noqa: BLE001 - every failure is a failed case
            failure = f"{type(exc).__name__}: {exc}"
        cases.append((name, time.monotonic() - started, failure))
        print(("FAIL " if failure else "ok   ") + name + (f": {failure}" if failure else ""))
    failed = sum(1 for c in cases if c[2])
    xml = [f'<testsuite name="deployed" tests="{len(cases)}" failures="{failed}">']
    for name, secs, failure in cases:
        xml.append(f'<testcase classname="deployed" name="{name}" time="{secs:.3f}">'
                   + (f'<failure message="{escape(failure, {chr(34): "&quot;"})}"/>' if failure else "") + "</testcase>")
    xml.append("</testsuite>")
    import os

    os.makedirs(os.path.dirname(args.junit) or ".", exist_ok=True)
    with open(args.junit, "w", encoding="utf-8") as f:
        f.write("\n".join(xml))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
