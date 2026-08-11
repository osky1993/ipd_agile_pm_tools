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
 * <p>说明：服务执行导入/导出时按项目维度做批量处理；导出为只读快照，不进行执行结果补算回写。</p>
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

    /**
     * 从上传的 Excel 中读取测试用例原始行。
     *
     * <p>读取规则说明：
     * <ul>
     *   <li>使用 Apache POI 打开 XLSX，固定读取第一个工作表（index 0）。</li>
     *   <li>跳过第一行表头（rowNum == 0）。</li>
     *   <li>每行固定读取 4 列：用例标题、测试步骤、预期结果、验证需求编号。</li>
     *   <li>空单元格按空字符串处理；最终对每个单元格值做 trim 去除首尾空白。</li>
     *   <li>异常策略：工作簿结构异常（非法内容、空 sheet、单元格类型不一致）会在构建或遍历时抛出 IO 异常，由上层转换为业务错误并终止导入。</li>
     * </ul>
     * 输出 `CaseRow.rowNum` 使用 Excel 里的“可读行号”（`row.getRowNum() + 1`），
     * 便于在导入时直接回填原始报错行号，便于运维/业务快速定位脏数据。
     *
     * @param in Excel 输入流，需为合法 XLSX 二进制
     * @return 解析后的用例行列表，包含标题/步骤/预期/需求编号
     * @throws IOException 文件解析失败时抛出
     */
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

    /**
     * 执行“测试用例”批量导入。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>角色与输入预检通过后，再按文件行推进创建。</li>
     *   <li>文件级异常（解析失败）直接返回失败；行级异常写入 errors 后继续处理，保证“尽量成功”。</li>
     *   <li>创建成功只调用 `TestCaseService.create` 一次，不做跨行去重与重试。</li>
     *   <li>仅当 `reqCode` 命中需求编号时才创建 verifies 关系，否则错误记录并跳过该行。</li>
     * </ul>
     *
     * <p>处理流程：
     * <ol>
     *   <li>校验调用者角色为 QA / PM；其余角色会在 `UserContext` 层拒绝。</li>
     *   <li>调用 {@link #readRows(InputStream)} 解析 Excel 文件，发生解析异常则整体直接报业务错误。</li>
     *   <li>一次性加载当前项目全部工作项，构建“需求编号 -> 工作项 ID”的映射，用于后续 verifies 关系校验。</li>
     *   <li>逐行进行：
     *     <ul>
     *       <li>空行会跳过；标题为空会报错并继续处理下一行。</li>
     *       <li>若填写了需求编号，检查它在当前项目是否存在，不存在则报错并跳过。</li>
     *       <li>通过 `TestCaseService.create` 持久化用例实体，默认状态写入 ACTIVE。</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>注意：当前实现采用“尽量容错”策略，逐行捕获异常并记录到 `errors`，不因单行失败中断整批导入；
     *       所有成功与失败结果会在同一次返回中一并返回。
     *
     * @param projectId 导入目标项目 ID
     * @param in       用户上传的 Excel 文件流
     * @return Map 返回：
     *         <ul>
     *           <li>created：成功创建的用例数</li>
     *           <li>errors：逐行错误信息列表</li>
     *         </ul>
     */
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
                tc.setStatus("ACTIVE"); // 导入即投产可执行
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

    /**
     * 生成测试用例导入模板。
     *
     * <p>模板约定：
     * <ul>
     *   <li>Sheet 名称：{@code 测试用例}</li>
     *   <li>列定义：
     *     <ol>
     *       <li>用例标题(必填)</li>
     *       <li>测试步骤</li>
     *       <li>预期结果</li>
     *       <li>验证需求编号(可选,如 OVN1-REQ-001)</li>
     *     </ol>
     *   </li>
     *   <li>附带 2 条示例数据，便于用户一眼对齐填写格式。</li>
     *   <li>设置列宽以提升可读性。</li>
     * </ul>
     * 模板生成失败会抛出 `BusinessException`，避免返回空文件导致调用端误判。
     * <p>幂等与副作用：方法无持久化副作用，重复调用仅返回新构造的二进制文件。</p>
     *
     * @return 包含模板内容的 XLSX 二进制
     */
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

    /**
     * 导出项目测试资产（用例与执行记录）为 Excel。
     *
     * <p>数据治理与复现性：
     * <ul>
     *   <li>仅按数据库事实生成，不做“导出中的动态修正”；输出即为当前快照。</li>
     *   <li>查询顺序和列序保持稳定，便于导入导出回归比对。</li>
     *   <li>状态与结果经过中文映射，空值统一转空字符串，缺陷映射先 `code` 再回退 `#ID`。</li>
     * </ul>
     *
     * <p>两页结构：
     * <ul>
     *   <li>Sheet1 {@code 测试用例}：
     *     <ul>
     *       <li>测试用例主数据（编号、标题、步骤、预期）</li>
     *       <li>基于 trace link（relation=verifies）补充“验证需求”</li>
     *       <li>按执行记录计算“最新结果”与“执行次数”</li>
     *     </ul>
     *   </li>
     *   <li>Sheet2 {@code 执行记录}：
     *     <ul>
     *       <li>每次执行明细（执行编号、结果、实际结果、是否生成缺陷、执行时间）</li>
     *       <li>缺陷 ID 会尽量映射到工作项 code；无法映射则回退到 #ID</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>实现细节：
     * <ul>
     *   <li>所有查询默认按 id 升序，导出顺序稳定可重现。</li>
     *   <li>状态和结果展示先经过 `zhStatus`/`zhResult` 做中文映射。</li>
     *   <li>空值字段通过 `nv` 统一转空字符串，避免 Excel 空单元格/NPE 异常。</li>
     * </ul>
     * <p>更新粒度：纯读导出，不触发任何业务状态变化；单元格映射与读取逻辑保持与导入约定对齐，便于回放核验。</p>
     *
     * @param projectId 项目 ID
     * @return 打包后的 XLSX 二进制数据；失败抛出 `BusinessException`
     */
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
            writeRow(s1, 0, headStyle, "编号", "用例标题", "测试步骤", "预期结果", "验证需求", "状态", "最新结果", "执行次数");
            int r1 = 1;
            for (TestCase c : cases) {
                List<TestRun> rs = runsByCase.getOrDefault(c.getId(), List.of());
                String latest = rs.isEmpty() ? "未执行" : zhResult(rs.get(rs.size() - 1).getResult());
                writeRow(s1, r1++, null, c.getCode(), c.getTitle(), nv(c.getSteps()), nv(c.getExpected()),
                        reqByCase.getOrDefault(c.getId(), ""), zhStatus(c.getStatus()), latest, String.valueOf(rs.size()));
            }
            int[] w1 = {14, 30, 40, 28, 18, 8, 10, 10};
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

    /**
     * 在指定 Sheet 的指定行写入一组字符串值。
     *
     * 范围说明：仅用于本类内部的导出组装场景，单元格值按传入顺序原样落盘。
     *
     * @param sheet 目标 Sheet
     * @param rowNum 行号（从 0 开始），不存在则创建
     * @param style 可选单元格样式；空值时不强制赋值
     * @param values 逐列写入值，按顺序映射到列 0..N
     */
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

    /**
     * 将测试用例状态码转换为中文展示值。
     *
     * @param s 原始状态值（例如 DRAFT / DISABLED / ACTIVE）
     * @return 中文状态：DRAFT -> 草稿，DISABLED -> 停用，其它值默认“启用”
     */
    private static String zhStatus(String s) {
        return "DRAFT".equals(s) ? "草稿" : "DISABLED".equals(s) ? "停用" : "启用";
    }

    /**
     * 将执行结果码转换为中文展示值。
     *
     * <p>映射：
     * <ul>
     *   <li>PASS -> 通过</li>
     *   <li>FAIL -> 失败</li>
     *   <li>其他/空值 -> 原值或空字符串</li>
     * </ul>
     *
     * @param r 原始结果码
     * @return 中文结果文本
     */
    private static String zhResult(String r) {
        return "PASS".equals(r) ? "通过" : "FAIL".equals(r) ? "失败" : r == null ? "" : r;
    }

    /**
     * 空值安全的字符串取值函数。
     *
     * <p>仅做最小封装，防止空指针在组装导入/导出字符串时扩散到 POI 赋值链路。</p>
     *
     * @param s 可能为 null 的字符串
     * @return 非空字符串
     */
    private static String nv(String s) {
        return s == null ? "" : s;
    }
}
