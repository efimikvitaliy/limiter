package com.v.limiter.input;

import com.v.limiter.events.MessageCreatedEvent;
import com.v.limiter.model.FundRequest;
import com.v.limiter.repository.FundRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FundsConsumer {
    private final RequestsSource requestsSource;
    private final DLQOutput dlqWriter;
    private final FundRequestRepository fundRequestRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApplicationEventPublisher eventPublisher;
    private volatile boolean running = true;

    public FundsConsumer(RequestsSource requestsSource, DLQOutput dlqWriter,
                         FundRequestRepository fundRequestRepository,
                         ApplicationEventPublisher eventPublisher) {
        this.requestsSource = requestsSource;
        this.dlqWriter = dlqWriter;
        this.fundRequestRepository = fundRequestRepository;
        this.eventPublisher = eventPublisher;
    }


    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void consumeMessages() {
        log.info("Funds consumer started; waiting for request lines");
        while (running) {
            String request = requestsSource.read();
            if (request == null) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            log.info("Consumed request: {}", request);
            try {
                FundRequest fundRequest = objectMapper.readValue(request, FundRequest.class);
                if (fundRequestRepository.saveMessage(fundRequest, request)) {
                    eventPublisher.publishEvent(new MessageCreatedEvent(this));
                    log.info("Published MessageCreatedEvent for load id {}", fundRequest.getId());
                }
            } catch (JsonProcessingException | IllegalArgumentException e) {
                dlqWriter.write(request);
                log.error("Request sent to DLQ: {}", request, e);
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
    }
}
