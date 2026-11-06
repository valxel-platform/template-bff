package com.vrixel.bff;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Just enough of a Keycloak realm for the sign-in flow: discovery, keys and the token endpoint. */
final class FakeKeycloak {

    final HttpServer server;
    final RSAKey key;
    final List<Map<String, String>> tokenRequests = new ArrayList<>();
    final List<String> tokenAuth = new ArrayList<>();
    volatile String nonce = "";

    FakeKeycloak() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/realms/emr/.well-known/openid-configuration", ex -> json(ex, discovery()));
        server.createContext("/realms/emr/protocol/openid-connect/certs",
                ex -> json(ex, new JWKSet(key.toPublicJWK()).toString()));
        server.createContext("/realms/emr/protocol/openid-connect/token", this::token);
        server.start();
    }

    String issuer() {
        return "http://localhost:" + server.getAddress().getPort() + "/realms/emr";
    }

    private String discovery() {
        String i = issuer();
        return """
                {"issuer":"%s","authorization_endpoint":"%s/protocol/openid-connect/auth",
                 "token_endpoint":"%s/protocol/openid-connect/token","jwks_uri":"%s/protocol/openid-connect/certs",
                 "end_session_endpoint":"%s/protocol/openid-connect/logout",
                 "response_types_supported":["code"],"subject_types_supported":["public"],
                 "id_token_signing_alg_values_supported":["RS256"],
                 "code_challenge_methods_supported":["S256"]}"""
                .formatted(i, i, i, i, i);
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> form = new HashMap<>();
        for (String pair : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            String[] kv = pair.split("=", 2);
            form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        tokenRequests.add(form);
        tokenAuth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer()).subject("user-1").audience("template-bff")
                    .claim("nonce", nonce).claim("name", "Test Person").claim("azp", "template-bff")
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build();
            SignedJWT id = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
            id.sign(new RSASSASigner(key));
            json(ex, """
                    {"access_token":"access-1","refresh_token":"refresh-1","id_token":"%s",
                     "token_type":"Bearer","expires_in":300,"scope":"openid profile"}"""
                    .formatted(id.serialize()));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    static void json(HttpExchange ex, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }
}
