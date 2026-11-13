package com.valxel.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class OriginCheckTest {

    private HttpStatus run(OriginCheck check, MockServerHttpRequest request) {
        MockServerWebExchange ex = MockServerWebExchange.from(request);
        check.filter(ex, e -> {
            e.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        }).block();
        return (HttpStatus) ex.getResponse().getStatusCode();
    }

    @Test
    void refusesWritesFromOtherOrigins() {
        OriginCheck app = new OriginCheck(List.of("https://emr.example.com"));
        assertThat(run(app, MockServerHttpRequest.post("https://emr.example.com/api/x").header("Origin", "https://emr.example.com").build()))
                .isEqualTo(HttpStatus.OK);
        assertThat(run(app, MockServerHttpRequest.post("https://emr.example.com/api/x").header("Origin", "https://evil.example").build()))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(app, MockServerHttpRequest.get("https://emr.example.com/api/x").header("Origin", "https://evil.example").build()))
                .isEqualTo(HttpStatus.OK); // reads change nothing
        assertThat(run(app, MockServerHttpRequest.post("https://emr.example.com/api/x").build())).isEqualTo(HttpStatus.OK);
    }

    @Test
    void withNoOriginsConfiguredOnlyItsOwnOrigin() {
        OriginCheck self = new OriginCheck(List.of());
        assertThat(run(self, MockServerHttpRequest.post("https://emr.example.com/api/x").header("Origin", "https://emr.example.com").build()))
                .isEqualTo(HttpStatus.OK);
        assertThat(run(self, MockServerHttpRequest.delete("https://emr.example.com/api/x").header("Origin", "http://emr.example.com").build()))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void settingsHaveSafeDefaults() {
        BffProperties p = new BffProperties(null, " ", null, null);
        assertThat(p.origins()).isEmpty();
        assertThat(p.afterLogin()).isEqualTo("/");
        assertThat(p.afterLogout()).isEqualTo("{baseUrl}/");
        // No mobile app unless the audience is set: /mobile/** then refuses everything.
        assertThat(p.mobile().enabled()).isFalse();
        assertThat(new BffProperties.Mobile(" example-api ", null).audience()).isEqualTo("example-api");
    }
}
