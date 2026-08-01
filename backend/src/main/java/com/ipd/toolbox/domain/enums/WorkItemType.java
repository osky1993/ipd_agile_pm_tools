package com.ipd.toolbox.domain.enums;

import java.util.Arrays;

/**
 * 统一工作项类型（docs/01）。abbr 用于编号生成，如 OVN1-REQ-001。
 */
public enum WorkItemType {
    CAPABILITY("CAP", "产品能力"),
    REQUIREMENT("REQ", "需求"),
    STORY("STO", "用户故事"),
    TASK("TSK", "任务"),
    DEFECT("DEF", "缺陷"),
    RISK("RSK", "风险"),
    CHANGE("CHG", "变更");

    private final String abbr;
    private final String label;

    WorkItemType(String abbr, String label) {
        this.abbr = abbr;
        this.label = label;
    }

    public String abbr() {
        return abbr;
    }

    public String label() {
        return label;
    }

    public static WorkItemType of(String name) {
        return Arrays.stream(values())
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知工作项类型: " + name));
    }
}
