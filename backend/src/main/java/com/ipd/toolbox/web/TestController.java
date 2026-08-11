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
/**
 * 测试体系接口：覆盖测试用例生命周期管理、执行记录维护、导入导出文件处理。
 * 与需求/缺陷/变更流程联动：用例状态变更会触发质量评估指标与缺陷池联动。
 */
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

    /** 查询项目下测试用例清单。 */
    @GetMapping("/cases")
    public Result<List<TestCaseService.CaseView>> listCases(@RequestParam Long projectId) {
        return Result.ok(testCaseService.list(projectId));
    }

    /** 新增测试用例：可附带需求关联用于追溯。 */
    @PostMapping("/cases")
    public Result<TestCase> createCase(@RequestBody TestCase tc,
                                       @RequestParam(required = false) Long verifiesRequirementId) {
        return Result.ok(testCaseService.create(tc, verifiesRequirementId));
    }

    /** 编辑用例内容。 */
    @PutMapping("/cases/{id}")
    public Result<TestCase> updateCase(@PathVariable Long id, @RequestBody TestCase patch) {
        return Result.ok(testCaseService.update(id, patch));
    }

    public record StatusReq(String status) {
    }

    /** 用例状态变更（DRAFT/ACTIVE/DISABLED）。 */
    @PostMapping("/cases/{id}/status")
    public Result<TestCase> changeStatus(@PathVariable Long id, @RequestBody StatusReq req) {
        return Result.ok(testCaseService.changeStatus(id, req.status()));
    }

    /** 删除用例（逻辑删）。 */
    @DeleteMapping("/cases/{id}")
    public Result<Void> deleteCase(@PathVariable Long id) {
        testCaseService.delete(id);
        return Result.ok();
    }

    /** 查询某用例最近执行记录（历史缺陷定位、回归趋势）。 */
    @GetMapping("/cases/{caseId}/runs")
    public Result<List<TestRun>> listRuns(@PathVariable Long caseId) {
        return Result.ok(testRunService.listByCase(caseId));
    }

    /** 提交一次执行记录：可带实际结果与失败证据；默认自动生成缺陷联动。 */
    @PostMapping("/runs")
    public Result<TestRun> execute(@RequestBody TestRun run,
                                   @RequestParam(defaultValue = "true") boolean autoCreateDefect) {
        return Result.ok(testRunService.execute(run, autoCreateDefect));
    }
}
