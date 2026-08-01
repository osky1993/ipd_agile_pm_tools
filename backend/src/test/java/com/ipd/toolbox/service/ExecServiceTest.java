package com.ipd.toolbox.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 大屏健康度判定规则。 */
class ExecServiceTest {

    @Test
    void 健康度_失控信号DANGER_预警或未就绪RISK_否则GOOD() {
        // 失控信号（超期类 HIGH / 已评审阶段红线未满足）→ DANGER
        assertEquals("DANGER", ExecService.health(true, 0, 0, true));
        // 未评审阶段的红线只体现为 HIGH 预警 → RISK（早期项目不误判红）
        assertEquals("RISK", ExecService.health(false, 2, 0, true));
        assertEquals("RISK", ExecService.health(false, 0, 3, true));
        assertEquals("RISK", ExecService.health(false, 0, 0, false));
        assertEquals("GOOD", ExecService.health(false, 0, 0, true));
    }

    @Test
    void 组合缺陷趋势_按周对齐8桶_流入关闭分列() {
        LocalDate today = LocalDate.of(2026, 8, 1); // 本周一 7-27
        Map<LocalDate, Long> inflow = Map.of(
                LocalDate.of(2026, 7, 27), 2L,   // 本周
                LocalDate.of(2026, 7, 20), 1L);  // 上周
        Map<LocalDate, Long> closed = Map.of(LocalDate.of(2026, 7, 28), 1L);

        List<ExecService.DefectWeek> weeks = ExecService.combinedDefectWeeks(inflow, closed, today);

        assertEquals(8, weeks.size());
        assertEquals("2026-07-27", weeks.get(7).weekStart());
        assertEquals(2, weeks.get(7).inflow());
        assertEquals(1, weeks.get(7).closed());
        assertEquals(1, weeks.get(6).inflow());
        assertEquals(0, weeks.get(6).closed());
    }
}
