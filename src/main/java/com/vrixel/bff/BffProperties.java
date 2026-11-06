package com.vrixel.bff;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.bff.*
 *
 * @param origins the origins the web app is served from (e.g. https://emr.example.com); a
 *     state-changing request from any other origin is refused
 * @param afterLogin where to send people after they sign in
 * @param afterLogout where Keycloak sends people after they sign out
 */
@ConfigurationProperties("app.bff")
public record BffProperties(List<String> origins, String afterLogin, String afterLogout) {

    public BffProperties {
        origins = origins == null ? List.of() : List.copyOf(origins);
        afterLogin = afterLogin == null || afterLogin.isBlank() ? "/" : afterLogin;
        afterLogout = afterLogout == null || afterLogout.isBlank() ? "{baseUrl}/" : afterLogout;
    }
}
