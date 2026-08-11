package com.ipd.toolbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.security.UserContext;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 风险 / 变更 Excel 导入（仿 TestExcelService 范式，readRows 为纯函数便于单测）。
 * RISK：处置措施/处置期限 写入 ext_fields 的 mitigation/dueDate（与治理页、预警、效能口径一致）。
 * CHANGE：仅基础字段；影响分析仍走守卫流程，不在导入时预置。
 */
@Service
public class RiskChangeExcelService {

    static final Set<String> TYPES = Set.of("RISK", "CHANGE");
    private static final String[] RISK_HEADERS =
            {"标题(必填)", "说明", "优先级(P0~P3)", "责任人ID", "处置措施", "处置期限(yyyy-MM-dd)",
             "概率(1-5)", "影响(1-5)", "策略(AVOID/TRANSFER/MITIGATE/ACCEPT)"};
    private static final Set<String> STRATEGIES = Set.of("AVOID", "TRANSFER", "MITIGATE", "ACCEPT");
    private static final String[] CHANGE_HEADERS = {"标题(必填)", "说明", "优先级(P0~P3)"};

    record ExcelRow(int rowNum, String title, String description, String priority,
                    String ownerId, String mitigation, String dueDate,
                    String probability, String impact, String strategy) {
    }

    private final WorkItemService workItemService;
    private final ObjectMapper objectMapper;

    /**
     * 风险/变更导入服务依赖注入。
     * workItemService 用于落库，ObjectMapper 仅用于构造风险 ext_fields JSON。
     */
    public RiskChangeExcelService(WorkItemService workItemService, ObjectMapper objectMapper) {
        this.workItemService = workItemService;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 Excel 为内存行结构（纯函数），第一行为表头自动忽略。
     * 使用 DataFormatter 兼容数字/日期/空单元格，统一返回 trim 后文本。
     */
    static List<ExcelRow> readRows(InputStream in) throws IOException {
        List<ExcelRow> out = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue;
                }
                String[] c = new String[9];
                for (int i = 0; i < 9; i++) {
                    Cell cell = row.getCell(i);
                    c[i] = cell == null ? "" : fmt.formatCellValue(cell).trim();
                }
                out.add(new ExcelRow(row.getRowNum() + 1, c[0], c[1], c[2], c[3], c[4], c[5],
                        c[6], c[7], c[8]));
            }
        }
        return out;
    }

    /**
     * 批量导入入口（RISK/CHANGE）。
     * PM-only；失败行不会中断全量，返回成功数和错误列表供前端提示修复。
     */
    @Transactional
    public Map<String, Object> importExcel(Long projectId, String type, InputStream in) {
        UserContext.requireRole("PM");
        if (!TYPES.contains(type)) {
            throw new BusinessException("导入类型仅支持 RISK / CHANGE");
        }
        List<ExcelRow> rows;
        try {
            rows = readRows(in);
        } catch (IOException e) {
            throw new BusinessException("Excel 解析失败: " + e.getMessage());
        }
        int created = 0;
        List<String> errors = new ArrayList<>();
        for (ExcelRow r : rows) {
            if (r.title().isBlank() && r.description().isBlank()) {
                continue; // 空行
            }
            try {
                created += importRow(projectId, type, r);
            } catch (BusinessException e) {
                errors.add("第" + r.rowNum() + "行: " + e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("errors", errors);
        return result;
    }

    /**
     * 处理单行记录：校验字段、组装 WorkItem（风险会写入 ext_fields），并交由 WorkItemService.create 落库。
     * 校验不通过抛异常，上层收集并继续处理下一行。
     */
    private int importRow(Long projectId, String type, ExcelRow r) {
        if (r.title().isBlank()) {
            throw new BusinessException("标题不能为空");
        }
        if (!r.priority().isBlank() && !Set.of("P0", "P1", "P2", "P3").contains(r.priority())) {
            throw new BusinessException("优先级须为 P0~P3，当前: " + r.priority());
        }
        WorkItem w = new WorkItem();
        w.setProjectId(projectId);
        w.setType(type);
        w.setTitle(r.title());
        w.setDescription(r.description().isBlank() ? null : r.description());
        w.setPriority(r.priority().isBlank() ? null : r.priority());
        if ("RISK".equals(type)) {
            if (!r.ownerId().isBlank()) {
                try {
                    w.setOwnerId(Long.parseLong(r.ownerId()));
                } catch (NumberFormatException e) {
                    throw new BusinessException("责任人ID须为数字，当前: " + r.ownerId());
                }
            }
            if (!r.dueDate().isBlank()) {
                try {
                    LocalDate.parse(r.dueDate());
                } catch (DateTimeParseException e) {
                    throw new BusinessException("处置期限格式须为 yyyy-MM-dd，当前: " + r.dueDate());
                }
            }
            Integer probability = parseScale(r.probability(), "概率");
            Integer impact = parseScale(r.impact(), "影响");
            String strategy = null;
            if (!r.strategy().isBlank()) {
                strategy = r.strategy().trim().toUpperCase();
                if (!STRATEGIES.contains(strategy)) {
                    throw new BusinessException("策略须为 AVOID/TRANSFER/MITIGATE/ACCEPT，当前: " + r.strategy());
                }
            }
            if (!r.mitigation().isBlank() || !r.dueDate().isBlank()
                    || probability != null || impact != null || strategy != null) {
                ObjectNode ext = objectMapper.createObjectNode();
                if (!r.mitigation().isBlank()) {
                    ext.put("mitigation", r.mitigation());
                }
                if (!r.dueDate().isBlank()) {
                    ext.put("dueDate", r.dueDate());
                }
                if (probability != null) {
                    ext.put("probability", probability);
                }
                if (impact != null) {
                    ext.put("impact", impact);
                }
                if (strategy != null) {
                    ext.put("strategy", strategy);
                }
                w.setExtFields(ext.toString());
            }
        }
        workItemService.create(w, null);
        return 1;
    }

    /**
     * 解析 1~5 的整数量表，空值返回 null。
     * 越界或非数字会抛业务异常并附字段标签。
     */
    private static Integer parseScale(String s, String label) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(s.trim());
            if (v < 1 || v > 5) {
                throw new BusinessException(label + "须为 1~5，当前: " + s);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new BusinessException(label + "须为 1~5 的整数，当前: " + s);
        }
    }

    /**
     * 生成导入模板（示例数据 + 表头）。
     * 返回二进制 XLSX，供前端直接下载到本地。
     */
    public byte[] template(String type) {
        if (!TYPES.contains(type)) {
            throw new BusinessException("模板类型仅支持 RISK / CHANGE");
        }
        String[] headers = "RISK".equals(type) ? RISK_HEADERS : CHANGE_HEADERS;
        String[][] samples = "RISK".equals(type)
                ? new String[][]{
                        {"关键元器件断供风险", "主控芯片供应商单一", "P1", "1", "引入第二供应商并完成认证", "2026-09-30", "3", "5", "MITIGATE"},
                        {"整机噪音超标风险", "风道设计余量不足", "P2", "", "预研降噪方案", "", "2", "3", "ACCEPT"}}
                : new String[][]{
                        {"集尘座风道重新设计", "为降低噪音需调整风道结构", "P1"},
                        {"App 配网流程简化", "配网失败率高，需改为蓝牙辅助配网", "P2"}};
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("RISK".equals(type) ? "风险" : "变更");
            CellStyle headStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headStyle.setFont(bold);
            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headStyle);
            }
            for (int r = 0; r < samples.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int i = 0; i < samples[r].length; i++) {
                    row.createCell(i).setCellValue(samples[r][i]);
                }
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.setColumnWidth(i, 26 * 256);
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("模板生成失败: " + e.getMessage());
        }
    }
}
