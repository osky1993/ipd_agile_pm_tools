package com.ipd.toolbox.service;

import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.enums.WorkItemType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CSV 导入的解析与类型识别（纯函数）。 */
class WorkItemCsvTest {

    @Test
    void CSV行解析_引号包裹含逗号与转义引号() {
        assertEquals(List.of("REQUIREMENT", "标题A", "说明"),
                WorkItemService.parseCsvLine("REQUIREMENT,标题A,说明"));
        assertEquals(List.of("需求", "含,逗号的标题", "带\"引号\""),
                WorkItemService.parseCsvLine("需求,\"含,逗号的标题\",\"带\"\"引号\"\"\""));
        assertEquals(List.of("a", "", "c"), WorkItemService.parseCsvLine("a,,c"));
    }

    @Test
    void 类型解析_枚举名中文标签缩写均可_未知报错() {
        assertEquals(WorkItemType.REQUIREMENT, WorkItemService.resolveType("REQUIREMENT"));
        assertEquals(WorkItemType.REQUIREMENT, WorkItemService.resolveType("需求"));
        assertEquals(WorkItemType.STORY, WorkItemService.resolveType("sto"));
        assertEquals(WorkItemType.TASK, WorkItemService.resolveType(" 任务 "));
        assertThrows(BusinessException.class, () -> WorkItemService.resolveType("不存在的类型"));
    }
}
