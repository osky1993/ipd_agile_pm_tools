package com.ipd.toolbox.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 大屏健康度判定规则。 */
class ExecServiceTest {

    @Test
    void 健康度_红线或HIGH为DANGER_MED或未就绪为RISK_否则GOOD() {
        assertEquals("DANGER", ExecService.health(1, 0, 0, true));
        assertEquals("DANGER", ExecService.health(0, 2, 0, true));
        assertEquals("RISK", ExecService.health(0, 0, 3, true));
        assertEquals("RISK", ExecService.health(0, 0, 0, false));
        assertEquals("GOOD", ExecService.health(0, 0, 0, true));
    }
}
