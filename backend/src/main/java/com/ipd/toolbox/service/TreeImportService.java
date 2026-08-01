package com.ipd.toolbox.service;

import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
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
 * 能力与需求树 Excel 导入（层级序号表达树：1 能力 → 1.1 需求 → 1.1.1 故事/任务）。
 * 逐条走 WorkItemService.create（编号/初始状态/审计/parent_of 全部生效）。
 */
@Service
public class TreeImportService {

    private final WorkItemService workItemService;

    public TreeImportService(WorkItemService workItemService) {
        this.workItemService = workItemService;
    }

    /** Excel 行原始数据（rowNum 为 Excel 显示行号，从 2 起）。 */
    record RowData(int rowNum, String seq, String type, String title,
                   String description, String priority, String ac, String estimate) {
    }

    record PlannedNode(RowData row, String parentSeq, WorkItemType type) {
    }

    record Plan(List<PlannedNode> nodes, List<String> errors) {
    }

    /** 序号校验：数字段点分（1 / 1.1 / 1.1.2）。 */
    static boolean validSeq(String seq) {
        return seq != null && seq.matches("\\d+(\\.\\d+)*");
    }

    static String parentSeq(String seq) {
        int i = seq.lastIndexOf('.');
        return i < 0 ? null : seq.substring(0, i);
    }

    /** 层级默认类型：1 段=能力、2 段=需求、3 段=故事、更深=任务。 */
    static WorkItemType typeByDepth(String seq) {
        int depth = seq.split("\\.").length;
        return switch (depth) {
            case 1 -> WorkItemType.CAPABILITY;
            case 2 -> WorkItemType.REQUIREMENT;
            case 3 -> WorkItemType.STORY;
            default -> WorkItemType.TASK;
        };
    }

    /** 行 → 建树计划（纯函数）：校验序号/标题/父存在/类型（显式优先，空则按层级推断）。 */
    static Plan plan(List<RowData> rows) {
        List<PlannedNode> nodes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seqs = new HashSet<>();
        for (RowData r : rows) {
            if ((r.seq() == null || r.seq().isBlank()) && (r.title() == null || r.title().isBlank())) {
                continue; // 空行
            }
            if (!validSeq(r.seq())) {
                errors.add("第 " + r.rowNum() + " 行: 序号格式应为 1 / 1.1 / 1.1.2（当前: " + r.seq() + "）");
                continue;
            }
            if (r.title() == null || r.title().isBlank()) {
                errors.add("第 " + r.rowNum() + " 行: 标题不能为空");
                continue;
            }
            if (!seqs.add(r.seq())) {
                errors.add("第 " + r.rowNum() + " 行: 序号 " + r.seq() + " 重复");
                continue;
            }
            String parent = parentSeq(r.seq());
            if (parent != null && !seqs.contains(parent)) {
                errors.add("第 " + r.rowNum() + " 行: 找不到父级序号 " + parent + "（父级行必须先于子级出现）");
                continue;
            }
            WorkItemType type;
            if (r.type() != null && !r.type().isBlank()) {
                try {
                    type = WorkItemService.resolveType(r.type());
                } catch (Exception e) {
                    errors.add("第 " + r.rowNum() + " 行: " + e.getMessage());
                    continue;
                }
            } else {
                type = typeByDepth(r.seq());
            }
            nodes.add(new PlannedNode(r, parent, type));
        }
        return new Plan(nodes, errors);
    }

    /** 读取 xlsx 第一个 sheet（跳表头），DataFormatter 保持单元格显示值。 */
    static List<RowData> readRows(InputStream in) throws IOException {
        List<RowData> out = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue; // 表头
                }
                String[] c = new String[7];
                for (int i = 0; i < 7; i++) {
                    Cell cell = row.getCell(i);
                    c[i] = cell == null ? "" : fmt.formatCellValue(cell).trim();
                }
                out.add(new RowData(row.getRowNum() + 1, c[0], c[1], c[2], c[3], c[4], c[5], c[6]));
            }
        }
        return out;
    }

    @Transactional
    public Map<String, Object> importExcel(Long projectId, InputStream in) {
        UserContext.requireRole("PM");
        Plan p;
        try {
            p = plan(readRows(in));
        } catch (IOException e) {
            throw new BusinessException("Excel 解析失败: " + e.getMessage());
        }
        List<String> errors = new ArrayList<>(p.errors());
        Map<String, Long> idBySeq = new HashMap<>();
        int created = 0;
        for (PlannedNode n : p.nodes()) {
            try {
                WorkItem w = new WorkItem();
                w.setProjectId(projectId);
                w.setType(n.type().name());
                w.setTitle(n.row().title().trim());
                if (!n.row().description().isBlank()) w.setDescription(n.row().description());
                if (!n.row().priority().isBlank()) w.setPriority(n.row().priority());
                if (!n.row().ac().isBlank()) w.setAcceptanceCriteria(n.row().ac());
                if (!n.row().estimate().isBlank()) w.setEstimate(n.row().estimate());
                Long parentId = n.parentSeq() == null ? null : idBySeq.get(n.parentSeq());
                WorkItem saved = workItemService.create(w, parentId);
                idBySeq.put(n.row().seq(), saved.getId());
                created++;
            } catch (Exception e) {
                errors.add("第 " + n.row().rowNum() + " 行: " + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("errors", errors);
        return out;
    }

    /** 生成导入模板（表头 + 示例行 + 列宽）。 */
    public byte[] template() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("能力需求树");
            String[] headers = {"序号", "类型(可留空自动推断)", "标题(必填)", "描述", "优先级", "验收条件", "估算(数字)"};
            CellStyle headStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headStyle.setFont(bold);
            CellStyle textStyle = wb.createCellStyle();
            textStyle.setDataFormat(wb.createDataFormat().getFormat("@")); // 序号列文本格式，防 1.10→1.1

            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headStyle);
            }
            String[][] samples = {
                    {"1", "", "智能助力", "按骑行工况自适应输出助力", "P0", "", ""},
                    {"1.1", "", "踏频+力矩双信号融合助力", "响应延迟≤80ms", "P0", "台架实测延迟≤80ms", "8"},
                    {"1.1.1", "", "力矩信号采集与滤波", "", "P1", "采样1kHz无毛刺", "3"},
                    {"1.1.2", "任务", "台架联调测试脚本", "", "P2", "", "2"},
                    {"2", "", "防盗与定位", "", "P1", "", ""},
                    {"2.1", "", "异动报警推送", "车辆被移动时 App 推送", "P1", "触达延迟≤10s", "5"},
            };
            for (int r = 0; r < samples.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int i = 0; i < samples[r].length; i++) {
                    Cell c = row.createCell(i);
                    c.setCellValue(samples[r][i]);
                    if (i == 0) {
                        c.setCellStyle(textStyle);
                    }
                }
            }
            sheet.setDefaultColumnStyle(0, textStyle);
            int[] widths = {10, 18, 30, 32, 8, 26, 12};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("模板生成失败: " + e.getMessage());
        }
    }
}
