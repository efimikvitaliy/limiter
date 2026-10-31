package com.v.limiter.input;

public interface DLQOutput {
    void write(String message);
}
