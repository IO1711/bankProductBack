package com.bilolbek.bankProductCreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DemoHostProviderTest {
    private static final class MutableClock extends Clock {
        private Instant now=Instant.parse("2026-10-29T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advanceSeconds(long seconds) { now=now.plusSeconds(seconds); }
    }

    @Test void issuedTicket_isSingleUseAndExpiresAfterSixtySeconds() {
        MutableClock clock=new MutableClock();
        DemoHostProvider host=new DemoHostProvider(clock);
        String first=host.issueTicket("u-creator");
        assertThat(host.validateAndConsume(first).user_id).isEqualTo("u-creator");
        assertThatThrownBy(()->host.validateAndConsume(first)).isInstanceOf(ResponseStatusException.class);
        String second=host.issueTicket("u-creator");
        clock.advanceSeconds(60);
        assertThatThrownBy(()->host.validateAndConsume(second)).isInstanceOf(ResponseStatusException.class);
    }
}
