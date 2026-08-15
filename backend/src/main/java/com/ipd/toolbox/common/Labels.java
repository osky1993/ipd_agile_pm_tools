package com.ipd.toolbox.common;

import com.ipd.toolbox.domain.enums.WorkItemType;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

/**
 * 后端中文标签字典：**只用于拼接给人看的文案**——异常消息、审计摘要、告警/阻塞 detail。
 *
 * <p>与前端 {@code utils/labels.ts} 是同一份语义的两个副本：接口出参一律仍是英文枚举，
 * 由前端按需汉化；但后端有一类文案是**拼好了直接展示**的（守卫报错、审计 summary、
 * 告警 detail 经 ExecService 原样透传到大屏），这些字符串里的英文枚举前端无从下手，
 * 只能在这里就地翻译。改动任一侧时另一侧需同步。
 *
 * <p>所有方法对未知值一律回落原值，绝不抛异常——文案汉化不该成为业务失败的原因。
 */
public final class Labels {

    private Labels() {
    }

    /** 工作项状态（通用/缺陷/变更/风险共用一张表）。 */
    private static final Map<String, String> STATUS = Map.ofEntries(
            // 通用链
            Map.entry("Backlog", "待办"),
            Map.entry("Ready", "就绪"),
            Map.entry("In Progress", "进行中"),
            Map.entry("Verification", "验证中"),
            Map.entry("Accepted", "已验收"),
            // 缺陷链
            Map.entry("Open", "打开"),
            Map.entry("Analysing", "分析中"),
            Map.entry("Fixing", "修复中"),
            Map.entry("Retesting", "复测中"),
            Map.entry("Closed", "已关闭"),
            // 变更链
            Map.entry("Submitted", "已提交"),
            Map.entry("Impact Analysed", "影响已分析"),
            Map.entry("Approved", "已批准"),
            Map.entry("Rejected", "已拒绝"),
            Map.entry("Implemented", "已实施"),
            Map.entry("Verified", "已验证"),
            // 风险链（Open/Closed 复用上面）
            Map.entry("Mitigating", "缓解中"));

    /**
     * 工作项状态中文名。
     *
     * @param type 工作项类型；风险的 Accepted 语义是"已接受"而非"已验收"，需靠它区分
     */
    public static String status(String status, String type) {
        if (status == null) {
            return "";
        }
        if ("RISK".equals(type) && "Accepted".equals(status)) {
            return "已接受";
        }
        return STATUS.getOrDefault(status, status);
    }

    /** 工作项状态中文名（不区分类型）。 */
    public static String status(String status) {
        return status(status, null);
    }

    /** 工作项类型中文名，复用枚举上已有的 label。 */
    public static String workItemType(String type) {
        if (type == null) {
            return "";
        }
        try {
            return WorkItemType.of(type).label();
        } catch (IllegalArgumentException e) {
            return type;
        }
    }

    /** 追溯关系类型。 */
    private static final Map<String, String> RELATION = Map.ofEntries(
            Map.entry("contributes_to", "贡献于（商业目标）"),
            Map.entry("parent_of", "分解为（父→子）"),
            Map.entry("implements", "实现"),
            Map.entry("verifies", "验证"),
            Map.entry("blocks", "阻塞"),
            Map.entry("depends_on", "依赖于"),
            Map.entry("changes", "变更涉及"),
            Map.entry("affects", "影响"),
            Map.entry("evidences", "佐证（挂证据）"),
            Map.entry("released_in", "纳入版本"));

    public static String relation(String r) {
        return r == null ? "" : RELATION.getOrDefault(r, r);
    }

    /** 决策结论（DCP 与变更审批共用）。 */
    private static final Map<String, String> CONCLUSION = Map.of(
            "PASS", "通过", "CONDITIONAL", "有条件通过", "REJECT", "不通过",
            "APPROVED", "批准", "REJECTED", "拒绝");

    public static String conclusion(String c) {
        return c == null ? "" : CONCLUSION.getOrDefault(c, c);
    }

    /** 决策类型。 */
    private static final Map<String, String> DECISION_TYPE = Map.of("DCP", "阶段决策", "CHANGE", "变更决策");

    public static String decisionType(String t) {
        return t == null ? "" : DECISION_TYPE.getOrDefault(t, t);
    }

    /** 测试执行结果。 */
    private static final Map<String, String> TEST_RESULT = Map.of("PASS", "通过", "FAIL", "失败", "BLOCKED", "阻塞");

    public static String testResult(String r) {
        return r == null ? "未执行" : TEST_RESULT.getOrDefault(r, r);
    }

    /** 测试用例状态。 */
    private static final Map<String, String> CASE_STATUS = Map.of("DRAFT", "草稿", "ACTIVE", "启用", "DISABLED", "停用");

    public static String caseStatus(String s) {
        return s == null ? "启用" : CASE_STATUS.getOrDefault(s, s);
    }

    /** 改进项状态（OPEN→DOING→DONE→VERIFIED）。 */
    private static final Map<String, String> IMPROVEMENT_STATUS = Map.of(
            "OPEN", "待启动", "DOING", "进行中", "DONE", "已落地", "VERIFIED", "已验证");

    public static String improvementStatus(String s) {
        return s == null ? "" : IMPROVEMENT_STATUS.getOrDefault(s, s);
    }

    /** 项目生命周期状态。 */
    private static final Map<String, String> LIFECYCLE = Map.of("ACTIVE", "进行中", "ON_HOLD", "暂停", "CLOSED", "已结项");

    public static String lifecycle(String s) {
        return s == null ? "" : LIFECYCLE.getOrDefault(s, s);
    }

    /** 角色（与 V2__seed_roles.sql 的 sys_role.name 一致）。 */
    private static final Map<String, String> ROLE = Map.of(
            "ADMIN", "系统管理员", "PM", "项目经理", "PO", "产品负责人", "DEV", "开发",
            "QA", "测试", "QUALITY", "质量负责人", "REVIEWER", "评审角色");

    public static String role(String code) {
        return code == null ? "" : ROLE.getOrDefault(code, code);
    }

    /** 经验教训类别。 */
    private static final Map<String, String> LESSON_CATEGORY = Map.of(
            "WELL", "做得好", "IMPROVE", "待改进", "PROCESS", "流程", "TECH", "技术",
            "SUPPLY", "供应", "OTHER", "其它");

    public static String lessonCategory(String c) {
        return c == null ? "" : LESSON_CATEGORY.getOrDefault(c, c);
    }

    /** 风险应对策略。 */
    private static final Map<String, String> RISK_STRATEGY = Map.of(
            "AVOID", "规避", "TRANSFER", "转移", "MITIGATE", "减轻", "ACCEPT", "接受");

    public static String riskStrategy(String s) {
        return s == null ? "" : RISK_STRATEGY.getOrDefault(s, s);
    }

    /** 审计动作。 */
    private static final Map<String, String> AUDIT_ACTION = Map.of(
            "CREATE", "新建", "UPDATE", "更新", "DELETE", "删除",
            "STATUS_CHANGE", "状态变更", "OWNER_CHANGE", "责任人变更",
            "DECISION", "决策", "API_TOKEN", "签发令牌");

    public static String auditAction(String a) {
        return a == null ? "" : AUDIT_ACTION.getOrDefault(a, a);
    }

    /**
     * 把一组英文枚举值翻成「中文(英文)」并用顿号连接，供"须为 X 之一"这类校验消息使用。
     *
     * <p>保留英文原值是刻意的：用户看得懂中文，而排查问题时（对着 Excel 模板、API 文档）
     * 需要知道该填哪个英文值。
     */
    public static String options(Collection<String> codes, Function<String, String> zh) {
        StringBuilder sb = new StringBuilder();
        for (String code : codes) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            String label = zh.apply(code);
            sb.append(label.equals(code) ? code : label + "(" + code + ")");
        }
        return sb.toString();
    }
}
