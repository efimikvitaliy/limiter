package com.v.limiter.events;

import org.springframework.context.ApplicationEvent;
import com.v.limiter.model.FundRequest;

public class MessageCreatedEvent extends ApplicationEvent {

    public MessageCreatedEvent(Object source) {
        super(source);
    }
}
