package com.prguard.agents;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** AI 코딩 에이전트 규칙 파일(AGENTS.md): 지금 상태 확인, 초안을 레포에 PR 로 올리기 */
@RestController
public class AgentsMdController {

    private final AgentsMdService service;

    public AgentsMdController(AgentsMdService service) {
        this.service = service;
    }

    @GetMapping("/api/projects/{id}/agents-md")
    public AgentsMdService.Info info(@PathVariable long id) {
        return service.info(id);
    }

    public record CreateRequest(String content) {
    }

    /** @return {url: PR 주소} */
    @PostMapping("/api/projects/{id}/agents-md/pull")
    public Map<String, String> createPull(@PathVariable long id, @RequestBody CreateRequest request) {
        return Map.of("url", service.createPull(id, request.content()));
    }
}
