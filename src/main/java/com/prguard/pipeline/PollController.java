package com.prguard.pipeline;

import com.prguard.project.ProjectService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 주기를 기다리지 않고 바로 폴링한다 (화면의 "지금 확인" 버튼). */
@RestController
public class PollController {

    private final ProjectService projects;
    private final PollService poller;

    public PollController(ProjectService projects, PollService poller) {
        this.projects = projects;
        this.poller = poller;
    }

    @PostMapping("/api/projects/{id}/poll")
    public PollResult poll(@PathVariable long id) {
        return poller.poll(projects.get(id));
    }
}
