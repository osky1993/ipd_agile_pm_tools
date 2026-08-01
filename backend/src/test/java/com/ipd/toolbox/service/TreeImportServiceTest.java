package com.ipd.toolbox.service;

import com.ipd.toolbox.domain.enums.WorkItemType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 需求树 Excel 导入：序号/层级/类型推断纯函数 + 模板往返解析。 */
class TreeImportServiceTest {

    private TreeImportService.RowData row(int n, String seq, String type, String title) {
        return new TreeImportService.RowData(n, seq, type, title, "", "", "", "");
    }

    @Test
    void 序号校验与父级推导() {
        assertTrue(TreeImportService.validSeq("1"));
        assertTrue(TreeImportService.validSeq("1.10.2"));
        assertFalse(TreeImportService.validSeq("1."));
        assertFalse(TreeImportService.validSeq("a.1"));
        assertFalse(TreeImportService.validSeq(""));
        assertNull(TreeImportService.parentSeq("1"));
        assertEquals("1.1", TreeImportService.parentSeq("1.1.2"));
    }

    @Test
    void 层级类型推断_显式类型优先() {
        assertEquals(WorkItemType.CAPABILITY, TreeImportService.typeByDepth("1"));
        assertEquals(WorkItemType.REQUIREMENT, TreeImportService.typeByDepth("2.3"));
        assertEquals(WorkItemType.STORY, TreeImportService.typeByDepth("1.1.1"));
        assertEquals(WorkItemType.TASK, TreeImportService.typeByDepth("1.1.1.1"));

        var plan = TreeImportService.plan(List.of(
                row(2, "1", "", "能力A"),
                row(3, "1.1", "", "需求B"),
                row(4, "1.1.1", "任务", "显式覆盖为任务")));
        assertTrue(plan.errors().isEmpty());
        assertEquals(WorkItemType.CAPABILITY, plan.nodes().get(0).type());
        assertEquals(WorkItemType.REQUIREMENT, plan.nodes().get(1).type());
        assertEquals(WorkItemType.TASK, plan.nodes().get(2).type());
        assertEquals("1.1", plan.nodes().get(2).parentSeq());
    }

    @Test
    void 计划校验_坏序号_缺标题_重复_父不存在_空行跳过() {
        var plan = TreeImportService.plan(List.of(
                row(2, "x", "", "坏序号"),
                row(3, "1", "", ""),
                row(4, "1", "", "能力A"),
                row(5, "1", "", "序号重复"),
                row(6, "2.1", "", "父级2不存在"),
                new TreeImportService.RowData(7, "", "", "", "", "", "", ""))); // 空行

        assertEquals(1, plan.nodes().size()); // 只有第4行合法
        assertEquals(4, plan.errors().size());
        assertTrue(plan.errors().get(0).contains("序号格式"));
        assertTrue(plan.errors().get(1).contains("标题不能为空"));
        assertTrue(plan.errors().get(2).contains("重复"));
        assertTrue(plan.errors().get(3).contains("父级"));
    }

    @Test
    void 模板生成后可被解析回读_示例行合法() throws Exception {
        TreeImportService svc = new TreeImportService(null);
        byte[] tpl = svc.template();
        var rows = TreeImportService.readRows(new ByteArrayInputStream(tpl));
        assertFalse(rows.isEmpty());
        var plan = TreeImportService.plan(rows);
        assertTrue(plan.errors().isEmpty(), String.join("; ", plan.errors()));
        assertEquals(6, plan.nodes().size());
        assertEquals("智能助力", plan.nodes().get(0).row().title());
        // 显式"任务"覆盖三级默认 STORY
        assertEquals(WorkItemType.TASK, plan.nodes().stream()
                .filter(n -> n.row().seq().equals("1.1.2")).findFirst().orElseThrow().type());
    }

    @Test
    void Excel序号文本格式_保留1点10不丢零() throws Exception {
        // 用 POI 造一个含 "1.10" 文本序号的表，验证 DataFormatter 不丢精度
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = wb.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("序号");
            var r = sheet.createRow(1);
            r.createCell(0).setCellValue("1.10");
            r.createCell(2).setCellValue("标题");
            var bos = new ByteArrayOutputStream();
            wb.write(bos);
            var rows = TreeImportService.readRows(new ByteArrayInputStream(bos.toByteArray()));
            assertEquals("1.10", rows.get(0).seq());
        }
    }
}
