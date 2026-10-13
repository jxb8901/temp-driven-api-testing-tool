package att.server;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class CronExpressionTest {
    @Test void computesDailyAndWeekdayRunsInTheConfiguredIanaZone() {
        ZoneId hongKong=ZoneId.of("Asia/Hong_Kong");
        assertEquals(Instant.parse("2026-01-01T18:00:00Z"),CronExpression.parse("0 2 * * *",15)
                .nextAfter(Instant.parse("2026-01-01T00:00:00Z"),hongKong));
        assertEquals(Instant.parse("2026-01-04T18:00:00Z"),CronExpression.parse("0 2 * * 1-5",15)
                .nextAfter(Instant.parse("2026-01-02T19:00:00Z"),hongKong));
    }

    @Test void skipsSpringGapAndRunsBothRealInstantsInFallOverlap() {
        ZoneId london=ZoneId.of("Europe/London");
        CronExpression spring=CronExpression.parse("30 1 * * *",15);
        assertEquals(Instant.parse("2024-04-01T00:30:00Z"),spring.nextAfter(Instant.parse("2024-03-31T00:00:00Z"),london));

        CronExpression autumn=CronExpression.parse("30 1 * * *",15);
        Instant first=Instant.parse("2024-10-27T00:30:00Z");
        Instant second=Instant.parse("2024-10-27T01:30:00Z");
        assertEquals(first,autumn.nextAfter(Instant.parse("2024-10-27T00:00:00Z"),london));
        assertEquals(second,autumn.nextAfter(first,london));
    }

    @Test void enforcesMinimumFrequencyAndSundayAliases() {
        assertThrows(IllegalArgumentException.class,()->CronExpression.parse("* * * * *",15));
        CronExpression sundayZero=CronExpression.parse("0 0 * * 0",1);
        CronExpression sundaySeven=CronExpression.parse("0 0 * * 7",1);
        ZoneId utc=ZoneId.of("UTC");Instant after=Instant.parse("2026-01-02T00:00:00Z");
        assertEquals(sundayZero.nextAfter(after,utc),sundaySeven.nextAfter(after,utc));
    }

    @Test void usesCronDayOfMonthOrDayOfWeekSemanticsWhenBothAreRestricted() {
        CronExpression expression=CronExpression.parse("0 0 1 * 1",1);
        ZoneId utc=ZoneId.of("UTC");
        assertEquals(Instant.parse("2025-01-06T00:00:00Z"),expression.nextAfter(Instant.parse("2025-01-01T00:01:00Z"),utc));
        assertEquals(Instant.parse("2025-02-01T00:00:00Z"),expression.nextAfter(Instant.parse("2025-01-31T00:01:00Z"),utc));
    }
}
