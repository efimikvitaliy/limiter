package com.v.limiter.input;

import com.v.limiter.events.MessageCreatedEvent;
import com.v.limiter.model.FundRequest;
import com.v.limiter.repository.FundRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundsConsumerTest {
    private static final String VALID_REQUEST = "{\"id\":\"1234\",\"customer_id\":\"1234\","
            + "\"load_amount\":\"$123.45\",\"time\":\"2018-01-01T00:00:00Z\"}";

    private RequestsSource requestsSource;
    private DLQOutput dlqWriter;
    private FundRequestRepository fundRequestRepository;
    private ApplicationEventPublisher eventPublisher;

    private FundsConsumer consumer;
    private ExecutorService executor;
    private CompletableFuture<Void> consumerTask;

    @BeforeEach
    void setUp() {
        requestsSource = mock(RequestsSource.class);
        dlqWriter = mock(DLQOutput.class);
        fundRequestRepository = mock(FundRequestRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        consumer = new FundsConsumer(requestsSource, dlqWriter, fundRequestRepository, eventPublisher);
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stopConsumer() throws Exception {
        consumer.stop();
        if (consumerTask != null) {
            consumerTask.get(2, TimeUnit.SECONDS);
        }
        executor.shutdownNow();
    }

    @Test
    void savesValidRequestAndPublishesEvent() throws Exception {
        when(requestsSource.read()).thenReturn(VALID_REQUEST, (String) null);
        when(fundRequestRepository.saveMessage(any(FundRequest.class), eq(VALID_REQUEST))).thenReturn(true);

        startConsumer();

        ArgumentCaptor<FundRequest> requestCaptor = ArgumentCaptor.forClass(FundRequest.class);
        verify(fundRequestRepository, timeout(1000)).saveMessage(requestCaptor.capture(), eq(VALID_REQUEST));
        FundRequest parsed = requestCaptor.getValue();
        assertEquals(1234L, parsed.getId());
        assertEquals(1234L, parsed.getCustomerId());
        assertEquals("123.45", parsed.getAmount().toPlainString());
        assertEquals("USD", parsed.getCurrency().getCurrencyCode());
        verify(eventPublisher, timeout(1000)).publishEvent(any(MessageCreatedEvent.class));
        verify(dlqWriter, never()).write(any());
    }

    @Test
    void sendsMalformedRequestToDlq() throws Exception {
        String invalidRequest = "not-json";
        when(requestsSource.read()).thenReturn(invalidRequest, (String) null);

        startConsumer();

        verify(dlqWriter, timeout(5000)).write(invalidRequest);
        verify(fundRequestRepository, never()).saveMessage(any(), any());
        verify(eventPublisher, never()).publishEvent(any(MessageCreatedEvent.class));
    }

    @Test
    void doesNotPublishEventForDuplicateRequest() throws Exception {
        when(requestsSource.read()).thenReturn(VALID_REQUEST, (String) null);
        when(fundRequestRepository.saveMessage(any(FundRequest.class), eq(VALID_REQUEST))).thenReturn(false);

        startConsumer();

        verify(fundRequestRepository, timeout(1000)).saveMessage(any(FundRequest.class), eq(VALID_REQUEST));
        verify(eventPublisher, never()).publishEvent(any(MessageCreatedEvent.class));
        verify(dlqWriter, never()).write(any());
    }

    @Test
    void stopEndsPollingLoop() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        when(requestsSource.read()).thenAnswer(invocation -> {
            reads.incrementAndGet();
            return null;
        });

        startConsumer();
        verify(requestsSource, timeout(1000).atLeastOnce()).read();

        consumer.stop();
        consumerTask.get(2, TimeUnit.SECONDS);

        int completedReads = reads.get();
        TimeUnit.MILLISECONDS.sleep(300);
        assertEquals(completedReads, reads.get());
    }

    private void startConsumer() {
        consumerTask = CompletableFuture.runAsync(consumer::consumeMessages, executor);
    }
}
