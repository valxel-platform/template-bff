package com.vrixel.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MinimumVersionTest {

    @Test
    void comparesVersionsNumerically() {
        MinimumVersion min = new MinimumVersion("1.4.0");
        assertThat(min.supported("1.4.0")).isTrue();
        assertThat(min.supported("1.4")).isTrue();
        assertThat(min.supported("1.10.0")).isTrue();
        assertThat(min.supported("2.0.0-beta.1")).isTrue();
        assertThat(min.supported("1.4.0+42")).isTrue();
        assertThat(min.supported("1.3.9")).isFalse();
        assertThat(min.supported("0.9")).isFalse();
        assertThat(min.supported(null)).isFalse();
        assertThat(min.supported("v1.4.0")).isFalse();
    }

    @Test
    void refusesAMinimumThatIsntAVersion() {
        assertThatThrownBy(() -> new MinimumVersion("latest")).isInstanceOf(IllegalArgumentException.class);
        assertThat(MinimumVersion.parse("1.2.3.4")).containsExactly(1, 2, 3, 4);
    }
}
