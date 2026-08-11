package com.ipd.toolbox.statemachine;

import com.ipd.toolbox.domain.enums.WorkItemType;

import java.util.*;

/**
 * 工作项状态机定义（docs/02）。该类集中声明“状态合法图谱”，描述系统中每类工作项可执行的状态迁移边与初始状态。
 *
 * <p>约定与边界说明：
 * <ul>
 *   <li>本类只维护“可达状态规则”，不直接发起持久化或状态变更；状态变更副作用由上层服务统一承接。</li>
 *   <li>允许迁移与禁止迁移在这里采用白名单模型：只有 nextStatuses 返回集合中的目标才为合法。</li>
 *   <li>是否属于回退迁移由 isBackward 判定，供上层在“需回退原因/流程审批”场景判断。</li>
 *   <li>状态机规则变更与守卫规则分离，减少规则散布导致的审计歧义。</li>
 * </ul>
 */
public final class StateMachine {

    private StateMachine() {
    }

    /** 各类型初始状态。 */
    private static final Map<WorkItemType, String> INITIAL = new EnumMap<>(WorkItemType.class);

    /** type -> (from -> 允许的 to 集合)。 */
    private static final Map<WorkItemType, Map<String, Set<String>>> TRANSITIONS = new EnumMap<>(WorkItemType.class);

    /** type -> 规范顺序，用于判断迁移方向（前进/回退）。 */
    private static final Map<WorkItemType, List<String>> ORDER = new EnumMap<>(WorkItemType.class);

    /**
     * 全局状态图初始化。
     *
     * <p>更新粒度说明：
     * <ul>
     *   <li>静态初始化一次，启动后复用内存对象。</li>
     *   <li>新增/调整迁移规则仅需改本块定义，避免分散在服务层重复实现。</li>
     *   <li>本方法仅抛出运行时配置异常，不产生运行时数据库 I/O 或外部调用。</li>
     * </ul>
     */
    static {
        // 通用工作项：Backlog → Ready → In Progress → Verification → Accepted（相邻可回退）
        String[] generic = {"Backlog", "Ready", "In Progress", "Verification", "Accepted"};
        for (WorkItemType t : List.of(WorkItemType.CAPABILITY, WorkItemType.REQUIREMENT,
                WorkItemType.STORY, WorkItemType.TASK)) {
            INITIAL.put(t, "Backlog");
            TRANSITIONS.put(t, linear(generic, true));
            ORDER.put(t, List.of(generic));
        }

        // 缺陷：Open → Analysing → Fixing → Retesting → Closed（相邻可回退，用于复测不通过回退）
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

    /**
     * 按有序状态数组，构造“当前状态 -> 可到达目标状态集合”的邻接图。
     *
     * <p>约束与边界：
     * <ul>
     *   <li>allowBack=false 时仅允许向前顺序迁移。</li>
     *   <li>首尾状态自然缺失前/后继目标。</li>
     *   <li>返回值为新建集合，方法间不共享外部可变引用。</li>
     * </ul>
     */
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

    /**
     * 获取类型的初始状态。
     *
     * <p>用途：服务层在创建新工作项时给出默认状态，保证新建记录不产生空状态。
     *
     * @param type 工作项类型
     * @return 初始状态；未注册类型返回 null
     */
    public static String initialStatus(WorkItemType type) {
        return INITIAL.get(type);
    }

    /**
     * 获取该类型已声明的来源状态集合。
     *
     * <p>用途：用于流程可视化/审计展示或前端下拉可选状态范围计算。
     *
     * @param type 工作项类型
     * @return 所有声明来源状态；内部视图直接返回
     */
    public static Set<String> allStatuses(WorkItemType type) {
        return TRANSITIONS.get(type).keySet();
    }

    /**
     * 计算当前状态的可迁移目标集合。
     *
     * <p>输入规范：
     * <ul>
     *   <li>type/from 均缺失时返回空集合。</li>
     *   <li>from 未在该类型图中定义时返回空集合。</li>
     * </ul>
     *
     * <p>副作用：无，纯读取；返回的集合为内部集合的直接引用，调用方不应直接修改。
     *
     * @param type 工作项类型
     * @param from 当前状态
     * @return 可迁移目标集合
     */
    public static Set<String> nextStatuses(WorkItemType type, String from) {
        return TRANSITIONS.getOrDefault(type, Map.of()).getOrDefault(from, Set.of());
    }

    /**
     * 判断迁移是否为合法边。
     *
     * <p>用途：状态变更前置规则中的第一层拦截；仅在规则合法时继续进入守卫链与持久化更新。
     *
     * <p>异常/失败策略：
     * <ul>
     *   <li>返回 false 表示非法迁移，通常映射为统一错误码并终止本次流程。</li>
     *   <li>当 type/from/to 任一为空或配置缺失，函数返回 false 而非抛异常。</li>
     * </ul>
     *
     * @param type 工作项类型
     * @param from 当前状态
     * @param to 目标状态
     * @return true 表示当前迁移属于允许边
     */
    public static boolean canTransition(WorkItemType type, String from, String to) {
        return nextStatuses(type, from).contains(to);
    }

    /**
     * 判定迁移方向是否为“回退”。
     *
     * <p>用途：
     * <ul>
     *   <li>状态回退常伴随附加说明要求，本值用于上层决定是否要求 reason 非空。</li>
     *   <li>回退检测与权限校验分离，方便不同调用方按需求复用。</li>
     * </ul>
     *
     * <p>边界：
     * <ul>
     *   <li>from/to 任一无法在 ORDER 中定位返回 false。</li>
     *   <li>ORDER 未声明的类型默认空列表，避免抛异常。</li>
     * </ul>
     *
     * @param type 工作项类型
     * @param from 当前状态
     * @param to 目标状态
     * @return true 表示目标在顺序上早于当前状态
     */
    public static boolean isBackward(WorkItemType type, String from, String to) {
        List<String> order = ORDER.getOrDefault(type, List.of());
        int fi = order.indexOf(from);
        int ti = order.indexOf(to);
        // 顺序表中都能定位，且目标更靠前，才算回退
        return fi >= 0 && ti >= 0 && ti < fi;
    }
}
