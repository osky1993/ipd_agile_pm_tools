package com.ipd.toolbox.service;

import org.junit.jupiter.api.Test;

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
}
