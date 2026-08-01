package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.TestCase;
import com.ipd.toolbox.domain.entity.TestRun;
import com.ipd.toolbox.service.TestCaseService;
import com.ipd.toolbox.service.TestRunService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tests")
public class TestController {

    private final TestCaseService testCaseService;
    private final TestRunService testRunService;

    public TestController(TestCaseService testCaseService, TestRunService testRunService) {
        this.testCaseService = testCaseService;
        this.testRunService = testRunService;
    }

    @GetMapping("/cases")
    public Result<List<TestCase>> listCases(@RequestParam Long projectId) {
        return Result.ok(testCaseService.list(projectId));
    }

    @PostMapping("/cases")
    public Result<TestCase> createCase(@RequestBody TestCase tc,
                                       @RequestParam(required = false) Long verifiesRequirementId) {
        return Result.ok(testCaseService.create(tc, verifiesRequirementId));
    }

    @GetMapping("/cases/{caseId}/runs")
    public Result<List<TestRun>> listRuns(@PathVariable Long caseId) {
        return Result.ok(testRunService.listByCase(caseId));
    }

    @PostMapping("/runs")
    public Result<TestRun> execute(@RequestBody TestRun run,
                                   @RequestParam(defaultValue = "true") boolean autoCreateDefect) {
        return Result.ok(testRunService.execute(run, autoCreateDefect));
    }
}
