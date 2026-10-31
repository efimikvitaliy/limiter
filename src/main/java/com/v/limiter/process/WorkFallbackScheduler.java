package com.v.limiter.process;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkFallbackScheduler {

    private final Worker worker;

    @Scheduled(fixedDelay = 5000)
    public void fallbackPoll() {
        worker.signal();
    }
}
