package com.wiki4ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link WorkItemLeaseReaper} (WIKI4AI-142).
 * <p>
 * The reaper has no logic of its own — it is a trigger and a delegation — so what
 * is worth testing here is the <b>wiring</b>, which is exactly the part the test
 * profile switches off. The timer does not run in tests
 * ({@code wiki4ai.work-items.scheduling-enabled=false} removes
 * {@code @EnableScheduling}); these tests prove the trigger exists, points at the
 * configured period, and calls the same operation the tests call by hand. Without
 * them, "the reaper is scheduled" would be an unverifiable claim.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkItemLeaseReaper (WIKI4AI-142): the scheduled wiring")
class WorkItemLeaseReaperTest {

    @Mock
    private WorkItemService workItemService;

    @InjectMocks
    private WorkItemLeaseReaper leaseReaper;

    @Test
    @DisplayName("the sweep method is annotated @Scheduled with the configured period")
    void sweepMethodIsScheduledWithConfigurablePeriod() throws Exception {
        Method sweep = WorkItemLeaseReaper.class.getMethod("sweepExpiredLeases");

        Scheduled scheduled = sweep.getAnnotation(Scheduled.class);
        assertThat(scheduled)
                .as("WIKI4AI-142 is the first @Scheduled task of this project; if the annotation is "
                        + "gone the lease TTL silently stops working")
                .isNotNull();
        assertThat(scheduled.fixedDelayString())
                .as("the period is a property placeholder with a 60 s default, not a hardcoded number")
                .isEqualTo("${wiki4ai.work-items.lease-sweep-millis:60000}");
        assertThat(scheduled.cron()).isEmpty();
        assertThat(scheduled.initialDelay()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("the scheduled method delegates to the service operations and adds up their result")
    void scheduledMethodDelegatesToTheOperationTestsCall() {
        given(workItemService.releaseExpiredLeases()).willReturn(3);
        given(workItemService.clearStaleClaims()).willReturn(2);

        leaseReaper.sweepExpiredLeases();

        verify(workItemService).releaseExpiredLeases();
        verify(workItemService).clearStaleClaims();
        assertThat(leaseReaper.releaseExpired()).isEqualTo(5);
    }

    @Test
    @DisplayName("releaseExpired() is the operation: it calls the two atomic statements and nothing else")
    void releaseExpiredCallsOnlyTheTwoBulkStatements() {
        given(workItemService.releaseExpiredLeases()).willReturn(1);
        given(workItemService.clearStaleClaims()).willReturn(0);

        assertThat(leaseReaper.releaseExpired()).isEqualTo(1);

        verify(workItemService).releaseExpiredLeases();
        verify(workItemService).clearStaleClaims();
    }

    @Test
    @DisplayName("a failing sweep does not escape the scheduled method — one missed tick, not a dead reaper")
    void sweepFailureIsLoggedNotThrown() {
        willThrow(new IllegalStateException("database unreachable")).given(workItemService).releaseExpiredLeases();

        assertThatCode(() -> leaseReaper.sweepExpiredLeases()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the reaper holds no @Transactional: the transaction belongs to the service it delegates to")
    void reaperDoesNotOwnTheTransaction() {
        assertThat(WorkItemLeaseReaper.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                .as("a self-call inside one bean does not go through the proxy that would provide the "
                        + "transaction a bulk JPQL UPDATE needs")
                .isNull();
        assertThat(WorkItemLeaseReaper.class.getDeclaredMethods())
                .allSatisfy(method -> assertThat(
                        method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
                        .as("%s must not rely on self-invocation for its transaction", method.getName())
                        .isFalse());
    }
}
