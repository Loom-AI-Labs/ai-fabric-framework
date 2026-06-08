package com.ai.infrastructure.behavior.state;

import org.springframework.stereotype.Component;

@Component
public class BehaviorProcessingState {
    private volatile boolean scheduledPaused = false;

    public boolean isScheduledPaused() {
        return scheduledPaused;
    }

    public void setScheduledPaused(boolean scheduledPaused) {
        this.scheduledPaused = scheduledPaused;
    }
}
