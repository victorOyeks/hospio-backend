package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.platform.client.TenantClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** Alert thresholds: declines per hour, stalled onboarding; and the health score's shape. */
class AlertRulesTest {

    @Test
    void fiveDeclinesInAnHourMeansPaymentsFailing() {
        assertThat(AlertService.paymentsFailing(4)).isFalse();
        assertThat(AlertService.paymentsFailing(5)).isTrue();
        assertThat(AlertService.paymentsFailing(12)).isTrue();
    }

    @Test
    void draftUntouchedForSevenDaysIsStalled() {
        Instant now = Instant.now();
        TenantClient.Draft fresh = new TenantClient.Draft("b1", "Fresh", "BACK_OFFICE", 3, "IN_PROGRESS", null, now.minus(10, ChronoUnit.DAYS), now.minus(6, ChronoUnit.DAYS));
        TenantClient.Draft stale = new TenantClient.Draft("b2", "Stale", "BACK_OFFICE", 4, "IN_PROGRESS", null, now.minus(20, ChronoUnit.DAYS), now.minus(7, ChronoUnit.DAYS));
        TenantClient.Draft done = new TenantClient.Draft("b3", "Done", "SELF_SERVE", 4, "DONE", null, now.minus(40, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS));
        assertThat(AlertService.isStalled(fresh, now)).isFalse();
        assertThat(AlertService.isStalled(stale, now)).isTrue();
        assertThat(AlertService.isStalled(done, now)).isFalse();
    }

    @Test
    void healthScoreRewardsActivityAndPunishesTicketsAndOverdueFees() {
        int busy = HealthService.score(new HealthService.Factors(8, 15, 40, 0, 0, 0));
        int quiet = HealthService.score(new HealthService.Factors(0, 0, 0, 0, 0, 0));
        int troubled = HealthService.score(new HealthService.Factors(2, 3, 5, 4, 2, 7));
        assertThat(busy).isEqualTo(100);
        assertThat(quiet).isEqualTo(30);
        assertThat(troubled).isBetween(0, 39);
        assertThat(HealthService.bucket(busy)).isEqualTo("good");
        assertThat(HealthService.bucket(quiet)).isEqualTo("atRisk");
        assertThat(HealthService.bucket(55)).isEqualTo("watch");
    }

    @Test
    void factorsAreWrittenAsJson() {
        assertThat(HealthService.factorsJson(new HealthService.Factors(1, 2, 3, 4, 5, 6)))
                .isEqualTo("{\"activeUsers7d\":1,\"bookings7d\":2,\"payments7d\":3,\"openTickets\":4,\"overdueStatements\":5,\"nightAuditsMissed7d\":6}");
    }
}
