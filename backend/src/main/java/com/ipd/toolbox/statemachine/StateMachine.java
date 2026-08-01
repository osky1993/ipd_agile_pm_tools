package com.ipd.toolbox.statemachine;

import com.ipd.toolbox.domain.enums.WorkItemType;

import java.util.*;

/**
 * 工作项状态机定义（docs/02）。集中定义各类型的合法迁移（含允许的回退）与初始状态。
 * 守卫规则不在此处——迁移是否放行由 TransitionGuard 判断，二者解耦。
 */
public final class StateMachine {

    private StateMachine() {
    }

    /** 各类型初始状态。 */
    private static final Map<WorkItemType, String> INITIAL = new EnumMap<>(WorkItemType.class);

    /** type -> (from -> 允许的 to 集合)。 */
    private static final Map<WorkItemType, Map<String, Set<String>>> TRANSITIONS = new EnumMap<>(WorkItemType.class);

    /** type -> 规范前进顺序，用于判断某次迁移是前进还是回退。 */
    private static final Map<WorkItemType, List<String>> ORDER = new EnumMap<>(WorkItemType.class);

    static {
        // 通用工作项：Backlog → Ready → In Progress → Verification → Accepted（相邻可回退）
        String[] generic = {"Backlog", "Ready", "In Progress", "Verification", "Accepted"};
        for (WorkItemType t : List.of(WorkItemType.CAPABILITY, WorkItemType.REQUIREMENT,
                WorkItemType.STORY, WorkItemType.TASK)) {
            INITIAL.put(t, "Backlog");
            TRANSITIONS.put(t, linear(generic, true));
            ORDER.put(t, List.of(generic));
        }

        // 缺陷：Open → Analysing → Fixing → Retesting → Closed（相邻可回退，用于复测不过打回）
        String[] defect = {"Open", "Analysing", "Fixing", "Retesting", "Closed"};
        INITIAL.put(WorkItemType.DEFECT, "Open");
        TRANSITIONS.put(WorkItemType.DEFECT, linear(defect, true));
        ORDER.put(WorkItemType.DEFECT, List.of(defect));

        // 风险：Open → Mitigating → Closed，且 Open/Mitigating 均可直接 Accepted
        INITIAL.put(WorkItemType.RISK, "Open");
        ORDER.put(WorkItemType.RISK, List.of("Open", "Mitigating", "Closed", "Accepted"));
        Map<String, Set<String>> risk = new HashMap<>();
        risk.put("Open", new HashSet<>(Set.of("Mitigating", "Accepted")));
        risk.put("Mitigating", new HashSet<>(Set.of("Open", "Closed", "Accepted")));
        risk.put("Closed", new HashSet<>(Set.of("Mitigating")));
        risk.put("Accepted", new HashSet<>(Set.of("Mitigating")));
        TRANSITIONS.put(WorkItemType.RISK, risk);

        // 变更：Submitted → Impact Analysed → Approved/Rejected → Implemented → Verified
        INITIAL.put(WorkItemType.CHANGE, "Submitted");
        Map<String, Set<String>> change = new HashMap<>();
        change.put("Submitted", new HashSet<>(Set.of("Impact Analysed")));
        change.put("Impact Analysed", new HashSet<>(Set.of("Approved", "Rejected", "Submitted")));
        change.put("Approved", new HashSet<>(Set.of("Implemented")));
        change.put("Rejected", new HashSet<>(Set.of("Impact Analysed")));
        change.put("Implemented", new HashSet<>(Set.of("Verified", "Approved")));
        change.put("Verified", new HashSet<>(Set.of("Implemented")));
        TRANSITIONS.put(WorkItemType.CHANGE, change);
        ORDER.put(WorkItemType.CHANGE,
                List.of("Submitted", "Impact Analysed", "Rejected", "Approved", "Implemented", "Verified"));
    }

    private static Map<String, Set<String>> linear(String[] states, boolean allowBack) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < states.length; i++) {
            Set<String> to = new HashSet<>();
            if (i + 1 < states.length) {
                to.add(states[i + 1]);
            }
            if (allowBack && i - 1 >= 0) {
                to.add(states[i - 1]);
            }
            map.put(states[i], to);
        }
        return map;
    }

    public static String initialStatus(WorkItemType type) {
        return INITIAL.get(type);
    }

    public static Set<String> allStatuses(WorkItemType type) {
        return TRANSITIONS.get(type).keySet();
    }

    /** 给定当前状态，返回可迁移到的目标状态。 */
    public static Set<String> nextStatuses(WorkItemType type, String from) {
        return TRANSITIONS.getOrDefault(type, Map.of()).getOrDefault(from, Set.of());
    }

    public static boolean canTransition(WorkItemType type, String from, String to) {
        return nextStatuses(type, from).contains(to);
    }

    /** 是否回退迁移（目标在规范顺序中更靠前），用于要求填写回退理由。 */
    public static boolean isBackward(WorkItemType type, String from, String to) {
        List<String> order = ORDER.getOrDefault(type, List.of());
        int fi = order.indexOf(from);
        int ti = order.indexOf(to);
        // 顺序表中都能定位，且目标更靠前，才算回退
        return fi >= 0 && ti >= 0 && ti < fi;
    }
}
