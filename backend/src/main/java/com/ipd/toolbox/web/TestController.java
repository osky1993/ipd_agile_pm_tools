package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.TestCase;
import com.ipd.toolbox.domain.entity.TestRun;
import com.ipd.toolbox.service.TestCaseService;
import com.ipd.toolbox.service.TestExcelService;
import com.ipd.toolbox.service.TestRunService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tests")
public class TestController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final TestCaseService testCaseService;
    private final TestRunService testRunService;
    private final TestExcelService testExcelService;

    public TestController(TestCaseService testCaseService, TestRunService testRunService,
                          TestExcelService testExcelService) {
        this.testCaseService = testCaseService;
        this.testRunService = testRunService;
        this.testExcelService = testExcelService;
    }

    /** 用例导入模板下载。 */
    @GetMapping("/import-template.xlsx")
    public ResponseEntity<byte[]> importTemplate() {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=testcase-template.xlsx")
                .contentType(XLSX)
                .body(testExcelService.template());
    }

    /** 用例 Excel 导入（标题/步骤/预期/验证需求编号）。 */
    @PostMapping("/import")
    public Result<Map<String, Object>> importCases(@RequestParam Long projectId,
                                                   @RequestPart("file") MultipartFile file) throws IOException {
        return Result.ok(testExcelService.importExcel(projectId, file.getInputStream()));
    }

    /** 用例导出（双 Sheet：用例清单 + 执行记录）。 */
    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> export(@RequestParam Long projectId) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=testcases-with-runs.xlsx")
                .contentType(XLSX)
                .body(testExcelService.export(projectId));
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
