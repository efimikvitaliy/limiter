package com.v.limiter.process;

import com.v.limiter.model.ClaimedFund;
import com.v.limiter.model.CustomerLoadStats;
import com.v.limiter.model.FundAcceptanceResult;
import com.v.limiter.model.FundRequest;
import com.v.limiter.output.FundAcceptanceOutput;
import com.v.limiter.repository.FundRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerTest {
    private FundRequestRepository repository;
    private LimitsCheckingService limitsCheckingService;
    private FundAcceptanceOutput fundAcceptanceOutput;
    private ManualExecutor triggerExecutor;
    private Worker worker;

    @BeforeEach
    void setUp() {
        repository = mock(FundRequestRepository.class);
        limitsCheckingService = mock(LimitsCheckingService.class);
        fundAcceptanceOutput = mock(FundAcceptanceOutput.class);
        triggerExecutor = new ManualExecutor();
        worker = new Worker(repository, limitsCheckingService, fundAcceptanceOutput, triggerExecutor, 1);
    }

    @AfterEach
    void shutDownWorker() {
        worker.shutdown();
    }

    @Test
    void signalCoalescesRepeatedSignalsIntoOneCapacityPoll() {
        when(repository.claimOne()).thenReturn(null);

        worker.signal();
        worker.signal();

        org.junit.jupiter.api.Assertions.assertEquals(1, triggerExecutor.size());
        triggerExecutor.runNext();
        verify(repository).claimOne();
    }

    @Test
    void writesAcceptanceThenMarksClaimedFundDone() {
        ClaimedFund claimedFund = claimedFund();
        FundAcceptanceResult result = acceptanceResult(true);
        when(repository.claimOne()).thenReturn(claimedFund, (ClaimedFund) null);
        when(limitsCheckingService.acceptFund(claimedFund)).thenReturn(result);
        when(repository.markDone(claimedFund, result)).thenReturn(true);

        worker.fillAvailableCapacity();

        InOrder order = inOrder(fundAcceptanceOutput, repository);
        order.verify(fundAcceptanceOutput, timeout(1000)).writeOutput(result);
        order.verify(repository, timeout(1000)).markDone(claimedFund, result);
    }

    @Test
    void releasesClaimForRetryWhenWritingOutputFails() {
        ClaimedFund claimedFund = claimedFund();
        FundAcceptanceResult result = acceptanceResult(true);
        when(repository.claimOne()).thenReturn(claimedFund, (ClaimedFund) null);
        when(limitsCheckingService.acceptFund(claimedFund)).thenReturn(result);
        doThrow(new IllegalStateException("output failed"))
                .when(fundAcceptanceOutput).writeOutput(result);

        worker.fillAvailableCapacity();

        verify(repository, timeout(1000)).releaseForRetry(claimedFund);
        verify(repository, never()).markDone(claimedFund, result);
    }

    private ClaimedFund claimedFund() {
        FundRequest request = new FundRequest();
        request.setMessageId(1L);
        request.setId(1234L);
        request.setCustomerId(5678L);
        request.setPayload("request payload");
        request.setAmount(new BigDecimal("10.00"));
        return new ClaimedFund(request, UUID.randomUUID(),
                new CustomerLoadStats(null, BigDecimal.ZERO, BigDecimal.ZERO, 0));
    }

    private FundAcceptanceResult acceptanceResult(boolean accepted) {
        return new FundAcceptanceResult(1234L, 5678L, accepted,
                new CustomerLoadStats(Instant.parse("2024-01-01T00:00:00Z"),
                        new BigDecimal("10.00"), new BigDecimal("10.00"), 1));
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
