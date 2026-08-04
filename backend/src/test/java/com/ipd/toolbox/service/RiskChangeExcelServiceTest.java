package com.ipd.toolbox.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 风险/变更 Excel 导入：readRows 纯函数解析与模板往返。 */
class RiskChangeExcelServiceTest {

    private byte[] workbook(String[][] rows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("风险");
            Row head = sheet.createRow(0);
            head.createCell(0).setCellValue("标题(必填)");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int i = 0; i < rows[r].length; i++) {
                    row.createCell(i).setCellValue(rows[r][i]);
                }
            }
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    @Test
    void readRows_解析六列_跳过表头_保留行号() throws Exception {
        byte[] wb = workbook(new String[][]{
                {"断供风险", "描述A", "P1", "1", "引入二供", "2026-09-30"},
                {"噪音风险", "", "P2", "", "", ""},
        });
        List<RiskChangeExcelService.ExcelRow> rows =
                RiskChangeExcelService.readRows(new ByteArrayInputStream(wb));
        assertEquals(2, rows.size());
        assertEquals(2, rows.get(0).rowNum());
        assertEquals("断供风险", rows.get(0).title());
        assertEquals("引入二供", rows.get(0).mitigation());
        assertEquals("2026-09-30", rows.get(0).dueDate());
        assertEquals("噪音风险", rows.get(1).title());
        assertEquals("", rows.get(1).ownerId());
    }

    @Test
    void readRows_短行缺列按空串补齐() throws Exception {
        byte[] wb = workbook(new String[][]{{"仅标题"}});
        List<RiskChangeExcelService.ExcelRow> rows =
                RiskChangeExcelService.readRows(new ByteArrayInputStream(wb));
        assertEquals(1, rows.size());
        assertEquals("仅标题", rows.get(0).title());
        assertEquals("", rows.get(0).description());
        assertEquals("", rows.get(0).dueDate());
    }

    @Test
    void 模板可被readRows解析_两类型表头列数正确() throws Exception {
        RiskChangeExcelService svc = new RiskChangeExcelService(null,
                new com.fasterxml.jackson.databind.ObjectMapper());
        for (String type : List.of("RISK", "CHANGE")) {
            byte[] tpl = svc.template(type);
            List<RiskChangeExcelService.ExcelRow> rows =
                    RiskChangeExcelService.readRows(new ByteArrayInputStream(tpl));
            assertFalse(rows.isEmpty(), type + " 模板应含示例行");
            assertFalse(rows.get(0).title().isBlank());
        }
        // RISK 示例含期限；CHANGE 无第 4~6 列
        byte[] risk = svc.template("RISK");
        assertEquals("2026-09-30",
                RiskChangeExcelService.readRows(new ByteArrayInputStream(risk)).get(0).dueDate());
    }
}
