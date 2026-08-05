package com.ipd.toolbox.service;

import com.ipd.toolbox.domain.entity.WorkItem;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 风险模式库聚合纯函数：结局/时长/词频/关键词过滤。 */
class RiskPatternServiceTest {

    private WorkItem risk(Long id, Long pid, String title, String status, String ext) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setProjectId(pid);
        w.setCode("R-" + id);
        w.setTitle(title);
        w.setType("RISK");
        w.setStatus(status);
        w.setExtFields(ext);
        return w;
    }

    @Test
    void 聚合_结局分布_平均时长_等级与策略计数() {
        List<WorkItem> risks = List.of(
                risk(1L, 1L, "供应商断供风险", "Closed", "{\"probability\":4,\"impact\":5,\"strategy\":\"MITIGATE\"}"),
                risk(2L, 1L, "供应商产能不足", "Accepted", "{\"probability\":2,\"impact\":3,\"strategy\":\"ACCEPT\"}"),
                risk(3L, 2L, "噪音超标", "Open", null));
        Map<Long, Long> days = Map.of(1L, 10L, 2L, 20L);
        RiskPatternService.Patterns p = RiskPatternService.aggregate(
                risks, Map.of(1L, "EBK", 2L, "PURE"), days, null);

        assertEquals(3, p.total());
        assertEquals(1, p.closed());
        assertEquals(1, p.accepted());
        assertEquals(1, p.open());
        assertEquals(15.0, p.avgResolveDays(), 0.01);
        assertEquals(1, p.byLevel().get("HIGH"));  // 4×5=20
        assertEquals(1, p.byLevel().get("LOW"));   // 2×3=6
        assertEquals(1, p.byStrategy().get("MITIGATE"));
        assertEquals("EBK", p.rows().get(0).projectCode());
        // "供应"出现于两条标题 → 高频词
        assertTrue(p.topWords().stream().anyMatch(w -> w.word().equals("供应") && w.count() >= 2));
    }

    @Test
    void 关键词过滤_只统计命中行() {
        List<WorkItem> risks = List.of(
                risk(1L, 1L, "供应商断供", "Open", null),
                risk(2L, 1L, "噪音超标", "Open", null));
        RiskPatternService.Patterns p = RiskPatternService.aggregate(
                risks, Map.of(1L, "EBK"), Map.of(), "供应");
        assertEquals(1, p.total());
        assertEquals("R-1", p.rows().get(0).code());
    }

    @Test
    void bigram_混合中英文只取汉字_跨分段不连词() {
        Map<String, Integer> freq = new HashMap<>();
        RiskPatternService.countBigrams("App 配网失败", freq);
        assertEquals(1, freq.get("配网"));
        assertEquals(1, freq.get("失败"));
        assertNull(freq.get("pp配")); // 英文不参与
        Map<String, Integer> f2 = new HashMap<>();
        RiskPatternService.countBigrams("断供 风险", f2);
        assertNull(f2.get("供风")); // 空格分段之间不成词
    }
}
