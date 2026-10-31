package com.prguard.pull;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class InvariantExecutionController {
    private final InvariantExecutionService execution;
    public InvariantExecutionController(InvariantExecutionService execution) { this.execution = execution; }

    @PostMapping("/api/projects/{id}/pulls/{number}/invariant-runs")
    public JsonNode start(@PathVariable long id, @PathVariable int number,
                         @RequestBody InvariantRuleCompiler.Candidate candidate) {
        return execution.start(id, number, candidate);
    }

    @GetMapping("/api/projects/{id}/pulls/{number}/invariant-runs/{runId}")
    public JsonNode get(@PathVariable long id, @PathVariable int number, @PathVariable UUID runId) {
        return execution.get(id, number, runId);
    }
}
