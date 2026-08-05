package com.ipd.toolbox.service;

import com.ipd.toolbox.domain.entity.Baseline;
import com.ipd.toolbox.domain.entity.BaselineItem;
import com.ipd.toolbox.domain.entity.WorkItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 基线 diff 纯函数：蔓延/移除/完成/日期偏差/估算漂移。 */
class BaselineServiceTest {

    private BaselineItem frozen(Long wid, String code, String status, String estimate, String planned) {
        BaselineItem f = new BaselineItem();
        f.setWorkItemId(wid);
        f.setCode(code);
        f.setTitle("t" + wid);
        f.setType("REQUIREMENT");
        f.setStatus(status);
        f.setEstimate(estimate);
        f.setPlannedDate(planned == null ? null : LocalDate.parse(planned));
        return f;
    }

    private WorkItem cur(Long id, String code, String status, String estimate, String forecast) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setCode(code);
        w.setTitle("t" + id);
        w.setType("REQUIREMENT");
        w.setStatus(status);
        w.setEstimate(estimate);
        w.setForecastDate(forecast == null ? null : LocalDate.parse(forecast));
        return w;
    }

    @Test
    void diff_蔓延_移除_完成_偏差_漂移() {
        Baseline b = new Baseline();
        b.setId(1L);
        // 基线：1 完成、2 拖期且估算涨、3 被移除
        List<BaselineItem> frozen = List.of(
                frozen(1L, "R-1", "In Progress", "5", "2026-08-01"),
                frozen(2L, "R-2", "Ready", "3", "2026-08-10"),
                frozen(3L, "R-3", "Backlog", "2", null));
        // 当前：1 已验收、2 forecast 推后 5 天且估算 3→8、4 为基线外新增（蔓延）
        Map<Long, WorkItem> current = Map.of(
                1L, cur(1L, "R-1", "Accepted", "5", "2026-08-01"),
                2L, cur(2L, "R-2", "In Progress", "8", "2026-08-15"),
                4L, cur(4L, "R-4", "Backlog", "1", null)).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        BaselineService.Diff d = BaselineService.diff(b, frozen, current);

        assertEquals(3, d.summary().baselineCount());
        assertEquals(1, d.summary().added());
        assertEquals(1, d.summary().removed());
        assertEquals(1, d.summary().done());
        assertEquals(33.3, d.summary().creepRate(), 0.01);
        assertEquals(2.5, d.summary().avgSlipDays(), 0.01); // (0 + 5) / 2
        assertEquals(5L, d.summary().maxSlipDays());
        assertEquals(5.0, d.summary().estimateDeltaTotal(), 0.01); // (5-5)+(8-3)

        Map<String, BaselineService.DiffRow> byCode = d.rows().stream()
                .collect(Collectors.toMap(BaselineService.DiffRow::code, r -> r));
        assertEquals("DONE", byCode.get("R-1").kind());
        assertEquals("OPEN", byCode.get("R-2").kind());
        assertEquals(5L, byCode.get("R-2").slipDays());
        assertEquals("REMOVED", byCode.get("R-3").kind());
        assertEquals("ADDED", byCode.get("R-4").kind());
    }

    @Test
    void diff_空基线_蔓延率为0不除零() {
        Baseline b = new Baseline();
        BaselineService.Diff d = BaselineService.diff(b, List.of(),
                Map.of(9L, cur(9L, "R-9", "Backlog", null, null)));
        assertEquals(0, d.summary().creepRate());
        assertEquals(1, d.summary().added());
        assertNull(d.summary().avgSlipDays());
    }

    @Test
    void diff_未估算保留原始串_delta仅对可解析值() {
        Baseline b = new Baseline();
        List<BaselineItem> frozen = List.of(frozen(1L, "R-1", "Ready", "abc", null));
        BaselineService.Diff d = BaselineService.diff(b, frozen,
                Map.of(1L, cur(1L, "R-1", "Ready", "4", null)));
        BaselineService.DiffRow row = d.rows().get(0);
        assertEquals("abc", row.baselineEstimate()); // 原始串保留展示
        assertEquals(4.0, row.estimateDelta(), 0.01); // parsePoints 口径：abc→0
    }
}
