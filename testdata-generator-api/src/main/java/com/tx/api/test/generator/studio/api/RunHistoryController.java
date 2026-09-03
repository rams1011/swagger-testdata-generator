package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.dto.RunResultView;
import com.tx.api.test.generator.studio.dto.RunView;
import com.tx.api.test.generator.studio.service.RunHistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/runs")
public class RunHistoryController {

    private final RunHistoryService runHistoryService;

    public RunHistoryController(RunHistoryService runHistoryService) {
        this.runHistoryService = runHistoryService;
    }

    @GetMapping
    public List<RunView> list(@RequestParam String specId,
                              @RequestParam(required = false) String planId,
                              @RequestParam(required = false) String caseId,
                              @RequestParam(required = false) String outcome,
                              @RequestParam(required = false) String trigger,
                              @RequestParam(required = false) Integer limit) {
        return runHistoryService.list(specId, planId, caseId, outcome, trigger, limit);
    }

    @GetMapping("/{runId}")
    public RunView get(@PathVariable String runId) {
        return runHistoryService.get(runId);
    }

    @GetMapping("/{runId}/results/{resultId}")
    public RunResultView getResult(@PathVariable String runId, @PathVariable String resultId) {
        return runHistoryService.getResult(runId, resultId);
    }

    @DeleteMapping("/{runId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String runId) {
        runHistoryService.delete(runId);
    }
}
