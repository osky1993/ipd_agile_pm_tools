package com.ipd.toolbox.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 测试用例 Excel 模板往返解析。 */
class TestExcelServiceTest {

    @Test
    void 模板生成后可回读_示例行完整() throws Exception {
        TestExcelService svc = new TestExcelService(null, null, null, null, null);
        byte[] tpl = svc.template();
        var rows = TestExcelService.readRows(new ByteArrayInputStream(tpl));
        assertEquals(2, rows.size());
        assertEquals("门未关闭时禁止远程启动", rows.get(0).title());
        assertTrue(rows.get(0).steps().contains("App 下发启动指令"));
        assertEquals("波动≤±5℃", rows.get(1).expected());
        assertEquals("", rows.get(0).reqCode());
    }
}
