package io.github.bamdule.dawn.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventIdsTest {

    @Test
    void 같은_사실에는_늘_같은_아이디가_나온다() {
        assertThat(EventIds.of("order", "20261028-000123", "PAID"))
                .isEqualTo(EventIds.of("order", "20261028-000123", "PAID"));
    }

    @Test
    void 다른_사실에는_다른_아이디가_나온다() {
        assertThat(EventIds.of("order", "20261028-000123", "PAID"))
                .isNotEqualTo(EventIds.of("order", "20261028-000124", "PAID"));
    }
}
