package com.v.limiter.process;

import com.v.limiter.model.ClaimedFund;
import com.v.limiter.model.FundAcceptanceResult;
import com.v.limiter.output.FundAcceptanceOutput;
import com.v.limiter.repository.FundRequestRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class Worker {

    private final Semaphore freeSlots;
    private final Executor triggerExecutor;
    private final ExecutorService executor;
    private final FundRequestRepository repository;
    private final LimitsCheckingService limitsCheckingService;
    private final FundAcceptanceOutput fundAcceptanceOutput;
    private final AtomicBoolean pollScheduled = new AtomicBoolean(false);
    private final AtomicBoolean pollRequested = new AtomicBoolean(false);

    public Worker(
            FundRequestRepository repository,
            LimitsCheckingService limitsCheckingService,
            FundAcceptanceOutput fundAcceptanceOutput,
            Executor triggerExecutor,
            @Value("${worker.parallelism:4}") int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("worker.parallelism must be at least 1");
        }
        this.freeSlots = new Semaphore(parallelism);
        this.triggerExecutor = triggerExecutor;
        this.executor = Executors.newFixedThreadPool(parallelism);
        this.repository = repository;
        this.limitsCheckingService = limitsCheckingService;
        this.fundAcceptanceOutput = fundAcceptanceOutput;
    }

    public void signal() {
        log.debug("Received signal for work");
        pollRequested.set(true);

        if (!pollScheduled.compareAndSet(false, true)) {
            return;
        }

        triggerExecutor.execute(() -> {
            try {
                while (pollRequested.getAndSet(false)) {
                    fillAvailableCapacity();
                }
            } finally {
                pollScheduled.set(false);

                if (pollRequested.get()) {
                    signal();
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    public void fillAvailableCapacity() {
        log.debug("Start processing funds");
        while (freeSlots.tryAcquire()) {
            ClaimedFund claimedFund = repository.claimOne();

            if (claimedFund == null) {
                log.debug("No available funds");
                freeSlots.release();
                return;
            }

            executor.execute(() -> process(claimedFund));
        }
    }

    private void process(ClaimedFund claimedFund) {
        log.info("Start processing fund with load id {}", claimedFund.fundRequest().getId());
        try {
            FundAcceptanceResult result = limitsCheckingService.acceptFund(claimedFund);
            log.info("Fund with load id {} accepted {}", claimedFund.fundRequest().getId(), result.accepted());
            // at least one
            fundAcceptanceOutput.writeOutput(result);
            repository.markDone(claimedFund, result);
        } catch (Exception e) {
            log.error("Failed to process fund load {}", claimedFund.fundRequest().getId(), e);
            repository.releaseForRetry(claimedFund);
        } finally {
            freeSlots.release();
            signal();
        }
    }


}
