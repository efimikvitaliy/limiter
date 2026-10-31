package com.v.limiter.events;

import com.v.limiter.process.Worker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageCreatedListener {

    private final Worker worker;

    @EventListener
    public void onMessageCreated(MessageCreatedEvent event) {
        log.debug("Received MessageCreatedEvent");
        worker.signal();
    }
}
