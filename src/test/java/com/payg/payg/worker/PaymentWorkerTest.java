package com.payg.payg.worker;

import com.payg.payg.repository.PaymentRepository;
import com.payg.payg.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2: {@link PaymentWorker} owns the claim loop and the lease reaper. Neither
 * needs a database to test - both are just "call the repository, react to
 * what it says" - so these mock {@link PaymentRepository} and
 * {@link PaymentService} directly rather than standing up Postgres.
 */
@ExtendWith(MockitoExtension.class)
class PaymentWorkerTest {

    @Mock private PaymentRepository payments;
    @Mock private PaymentService service;

    private PaymentWorker worker;

    @BeforeEach
    void setUp() {
        // batchSize=3 keeps the "does it stop at the cap" test fast and exact.
        WorkerProperties properties = new WorkerProperties(30, 500, 5000, 3);
        worker = new PaymentWorker(payments, service, properties);
    }

    @Test
    void stopsAsSoonAsNothingIsClaimable() {
        UUID first = UUID.randomUUID();
        when(payments.claimNext(anyString(), any(), any()))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.empty());

        worker.pollAndProcess();

        verify(payments, times(2)).claimNext(anyString(), any(), any());
        verify(service, times(1)).processClaimed(first);
    }

    @Test
    void neverClaimsMoreThanBatchSizeInOneTick() {
        when(payments.claimNext(anyString(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID())); // always something left

        worker.pollAndProcess();

        // batchSize=3: exactly 3 claims, exactly 3 hand-offs to the service,
        // then the tick ends on its own even though more were claimable -
        // the next scheduled tick picks up where this one left off.
        verify(payments, times(3)).claimNext(anyString(), any(), any());
        verify(service, times(3)).processClaimed(any());
    }

    @Test
    void aPaymentThatThrowsDoesNotStopTheRestOfTheBatch() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(payments.claimNext(anyString(), any(), any()))
                .thenReturn(Optional.of(bad))
                .thenReturn(Optional.of(good))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("db hiccup")).when(service).processClaimed(bad);

        worker.pollAndProcess();

        // The exception is swallowed here rather than left for the caller to
        // catch: "bad" keeps its lease and PROCESSING status, and the reaper
        // will move it to UNKNOWN once that lease lapses - not retried here.
        verify(service).processClaimed(bad);
        verify(service).processClaimed(good);
    }

    @Test
    void reaperDelegatesToTheGuardedRepositoryUpdate() {
        when(payments.reapExpiredLeases(any())).thenReturn(2);

        worker.reapExpiredLeases();

        verify(payments, times(1)).reapExpiredLeases(any());
    }

    @Test
    void neverClaimsWhenNothingIsWaiting() {
        when(payments.claimNext(anyString(), any(), any())).thenReturn(Optional.empty());

        worker.pollAndProcess();

        verify(payments, times(1)).claimNext(anyString(), any(), any());
        verify(service, never()).processClaimed(any());
    }
}
