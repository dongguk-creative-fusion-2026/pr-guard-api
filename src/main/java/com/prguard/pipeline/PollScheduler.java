package com.prguard.pipeline;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PollScheduler {

    private final PollService poller;

    public PollScheduler(PollService poller) {
        this.poller = poller;
    }

    @Scheduled(fixedDelayString = "${prguard.poll.interval}", initialDelayString = "PT5S")
    public void pollAll() {
        poller.pollAll();
    }
}
