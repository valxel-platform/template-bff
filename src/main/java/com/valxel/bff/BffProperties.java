package com.valxel.bff;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.bff.*
 *
 * @param origins the origins the web app is served from (e.g. https://emr.example.com); a
 *     state-changing request from any other origin is refused
 * @param afterLogin where to send people after they sign in
 * @param afterLogout where Keycloak sends people after they sign out
 * @param mobile the product's mobile apps (the {@code /mobile/api/**} route)
 */
@ConfigurationProperties("app.bff")
public record BffProperties(List<String> origins, String afterLogin, String afterLogout, Mobile mobile) {

    public BffProperties {
        origins = origins == null ? List.of() : List.copyOf(origins);
        afterLogin = afterLogin == null || afterLogin.isBlank() ? "/" : afterLogin;
        afterLogout = afterLogout == null || afterLogout.isBlank() ? "{baseUrl}/" : afterLogout;
        mobile = mobile == null ? new Mobile(null, null) : mobile;
    }

    /**
     * app.bff.mobile.*
     *
     * @param audience the audience mobile tokens must carry: the product's API (e.g. emr-api). Empty:
     *     the product has no mobile app and {@code /mobile/**} refuses everything.
     * @param minVersion the oldest app version still supported (e.g. 1.4.0); older versions are
     *     answered 426 so the app asks the person to update. Empty: any version.
     */
    public record Mobile(String audience, String minVersion) {

        public Mobile {
            audience = audience == null ? "" : audience.strip();
            minVersion = minVersion == null ? "" : minVersion.strip();
        }

        public boolean enabled() {
            return !audience.isEmpty();
        }
    }
}
