package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.TestCase;
import com.ipd.toolbox.domain.entity.TestRun;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.TestCaseMapper;
import com.ipd.toolbox.mapper.TestRunMapper;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import com.ipd.toolbox.security.UserContext;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * 测试用例 Excel 导入/导出。
 * 导入：标题/步骤/预期/验证需求编号（自动建 verifies 链），逐条走 TestCaseService.create。
 * 导出：双 Sheet——用例清单（含验证需求与最新结果）+ 全部执行记录。
 */
@Service
public class TestExcelService {

    private final TestCaseService testCaseService;
    private final TestCaseMapper testCaseMapper;
    private final TestRunMapper testRunMapper;
    private final WorkItemMapper workItemMapper;
    private final TraceLinkMapper traceLinkMapper;

    public TestExcelService(TestCaseService testCaseService, TestCaseMapper testCaseMapper,
                            TestRunMapper testRunMapper, WorkItemMapper workItemMapper,
                            TraceLinkMapper traceLinkMapper) {
        this.testCaseService = testCaseService;
        this.testCaseMapper = testCaseMapper;
        this.testRunMapper = testRunMapper;
        this.workItemMapper = workItemMapper;
        this.traceLinkMapper = traceLinkMapper;
    }

    record CaseRow(int rowNum, String title, String steps, String expected, String reqCode) {
    }

    static List<CaseRow> readRows(InputStream in) throws IOException {
        List<CaseRow> out = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue;
                }
                String[] c = new String[4];
                for (int i = 0; i < 4; i++) {
                    Cell cell = row.getCell(i);
                    c[i] = cell == null ? "" : fmt.formatCellValue(cell).trim();
                }
                out.add(new CaseRow(row.getRowNum() + 1, c[0], c[1], c[2], c[3]));
            }
        }
        return out;
    }

    @Transactional
    public Map<String, Object> importExcel(Long projectId, InputStream in) {
        UserContext.requireRole("QA", "PM");
        List<CaseRow> rows;
        try {
            rows = readRows(in);
        } catch (IOException e) {
            throw new BusinessException("Excel 解析失败: " + e.getMessage());
        }
        // 需求编号 → id（仅本项目）
        Map<String, Long> reqIdByCode = new HashMap<>();
        for (WorkItem w : workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId))) {
            reqIdByCode.put(w.getCode(), w.getId());
        }
        int created = 0;
        List<String> errors = new ArrayList<>();
        for (CaseRow r : rows) {
            if (r.title().isBlank() && r.steps().isBlank() && r.reqCode().isBlank()) {
                continue; // 空行
            }
            if (r.title().isBlank()) {
                errors.add("第 " + r.rowNum() + " 行: 用例标题不能为空");
                continue;
            }
            Long reqId = null;
            if (!r.reqCode().isBlank()) {
                reqId = reqIdByCode.get(r.reqCode());
                if (reqId == null) {
                    errors.add("第 " + r.rowNum() + " 行: 本项目找不到工作项编号 " + r.reqCode());
                    continue;
                }
            }
            try {
                TestCase tc = new TestCase();
                tc.setProjectId(projectId);
                tc.setTitle(r.title());
                if (!r.steps().isBlank()) tc.setSteps(r.steps());
                if (!r.expected().isBlank()) tc.setExpected(r.expected());
                testCaseService.create(tc, reqId);
                created++;
            } catch (Exception e) {
                errors.add("第 " + r.rowNum() + " 行: " + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("errors", errors);
        return out;
    }

    public byte[] template() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("测试用例");
            CellStyle headStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headStyle.setFont(bold);
            String[] headers = {"用例标题(必填)", "测试步骤", "预期结果", "验证需求编号(可选,如 OVN1-REQ-001)"};
            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headStyle);
            }
            String[][] samples = {
                    {"门未关闭时禁止远程启动", "1.打开烤箱门 2.App 下发启动指令", "启动被拒绝并提示关门", ""},
                    {"控温精度测试", "设定200℃恒温30分钟，记录温度曲线", "波动≤±5℃", ""},
            };
            for (int r = 0; r < samples.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int i = 0; i < samples[r].length; i++) {
                    row.createCell(i).setCellValue(samples[r][i]);
                }
            }
            int[] widths = {30, 40, 30, 30};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("模板生成失败: " + e.getMessage());
        }
    }

    /** 导出：Sheet1 用例清单（验证需求/最新结果/执行次数）+ Sheet2 执行记录。 */
    public byte[] export(Long projectId) {
        List<TestCase> cases = testCaseMapper.selectList(new QueryWrapper<TestCase>()
                .eq("project_id", projectId).orderByAsc("id"));
        List<TestRun> runs = testRunMapper.selectList(new QueryWrapper<TestRun>()
                .eq("project_id", projectId).orderByAsc("test_case_id").orderByAsc("id"));
        // 用例 → verifies 需求 code
        Map<Long, String> reqByCase = new HashMap<>();
        Map<Long, WorkItem> items = new HashMap<>();
        workItemMapper.selectList(new QueryWrapper<WorkItem>().eq("project_id", projectId))
                .forEach(w -> items.put(w.getId(), w));
        for (TraceLink l : traceLinkMapper.selectList(new QueryWrapper<TraceLink>()
                .eq("project_id", projectId).eq("relation", "verifies").eq("source_type", "TEST_CASE"))) {
            WorkItem w = items.get(l.getTargetId());
            if (w != null) {
                reqByCase.merge(l.getSourceId(), w.getCode(), (a, b) -> a + "、" + b);
            }
        }
        Map<Long, List<TestRun>> runsByCase = new LinkedHashMap<>();
        runs.forEach(r -> runsByCase.computeIfAbsent(r.getTestCaseId(), k -> new ArrayList<>()).add(r));
        Map<Long, String> caseCode = new HashMap<>();
        cases.forEach(c -> caseCode.put(c.getId(), c.getCode()));

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle headStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headStyle.setFont(bold);

            Sheet s1 = wb.createSheet("测试用例");
            writeRow(s1, 0, headStyle, "编号", "用例标题", "测试步骤", "预期结果", "验证需求", "最新结果", "执行次数");
            int r1 = 1;
            for (TestCase c : cases) {
                List<TestRun> rs = runsByCase.getOrDefault(c.getId(), List.of());
                String latest = rs.isEmpty() ? "未执行" : zhResult(rs.get(rs.size() - 1).getResult());
                writeRow(s1, r1++, null, c.getCode(), c.getTitle(), nv(c.getSteps()), nv(c.getExpected()),
                        reqByCase.getOrDefault(c.getId(), ""), latest, String.valueOf(rs.size()));
            }
            int[] w1 = {14, 30, 40, 28, 18, 10, 10};
            for (int i = 0; i < w1.length; i++) {
                s1.setColumnWidth(i, w1[i] * 256);
            }

            Sheet s2 = wb.createSheet("执行记录");
            writeRow(s2, 0, headStyle, "用例编号", "执行编号", "结果", "实际结果", "生成缺陷", "执行时间");
            int r2 = 1;
            for (TestRun run : runs) {
                String defect = run.getDefectId() == null ? "" :
                        items.containsKey(run.getDefectId()) ? items.get(run.getDefectId()).getCode()
                                : "#" + run.getDefectId();
                writeRow(s2, r2++, null, caseCode.getOrDefault(run.getTestCaseId(), "#" + run.getTestCaseId()),
                        run.getCode(), zhResult(run.getResult()), nv(run.getActual()), defect,
                        run.getRunAt() == null ? "" : run.getRunAt().toString().replace('T', ' '));
            }
            int[] w2 = {14, 14, 8, 36, 14, 20};
            for (int i = 0; i < w2.length; i++) {
                s2.setColumnWidth(i, w2[i] * 256);
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("导出失败: " + e.getMessage());
        }
    }

    private static void writeRow(Sheet sheet, int rowNum, CellStyle style, String... values) {
        Row row = sheet.createRow(rowNum);
        for (int i = 0; i < values.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(values[i]);
            if (style != null) {
                c.setCellStyle(style);
            }
        }
    }

    private static String zhResult(String r) {
        return "PASS".equals(r) ? "通过" : "FAIL".equals(r) ? "失败" : r == null ? "" : r;
    }

    private static String nv(String s) {
        return s == null ? "" : s;
    }
}
