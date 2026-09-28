package att.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InternalExceptionLoggerTest {
    @Test void expectedDomainAndAvailabilityErrorsStayConcise() {
        assertFalse(InternalExceptionLogger.isInternal(
                new IllegalStateException("db.orders.scalar requires exactly one row but received 2")));
        assertFalse(InternalExceptionLogger.isInternal(
                new IllegalStateException("HTTP invocation is unavailable: http.payment.get")));
        assertFalse(InternalExceptionLogger.isInternal(
                new IllegalStateException("DB query failed for orders: SQL timeout")));
    }

    @Test void internalFailuresRemainVisibleThroughExpectedWrapperCauses() {
        IllegalStateException expectedWrapper = new IllegalStateException(
                "DB query failed for orders: adapter failed", new NullPointerException("unexpected adapter state"));
        assertTrue(InternalExceptionLogger.isInternal(expectedWrapper));
        assertTrue(InternalExceptionLogger.isInternal(new IllegalStateException("unexpected invariant failure")));
        assertTrue(InternalExceptionLogger.isInternal(new ClassCastException("unexpected value type")));
    }
}
