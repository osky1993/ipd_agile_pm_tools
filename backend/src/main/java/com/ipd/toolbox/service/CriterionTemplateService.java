package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.mapper.GateCriterionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DCP 准入条件模板库：各决策评审点的典型条件预置（含红线标记），
 * 一键铺开到指定 gate（按条件文本去重，可重复应用不产生重复行）。
 * 模板即组织资产——沉淀"每个 DCP 该问什么"，替代逐项目手工重录。
 */
@Service
public class CriterionTemplateService {

    public record TemplateItem(String domain, String criterion, String evidenceReq, boolean redline) {
    }

    public record Template(String key, String name, String note, List<TemplateItem> items) {
    }

    static final List<Template> TEMPLATES = List.of(
            new Template("DCP1", "概念决策（Charter/DCP1）", "回答\"值不值得做\"", List.of(
                    new TemplateItem("商业", "目标市场与客户问题已定义，市场容量有估算依据", "市场分析报告", true),
                    new TemplateItem("商业", "商业模式与盈利逻辑说清（定价/成本/渠道假设）", "商业计划摘要", false),
                    new TemplateItem("技术", "关键技术可行性已评估，重大技术风险已识别", "技术可行性报告", true),
                    new TemplateItem("技术", "与现有产品/平台的复用与差异化已分析", "平台复用分析", false),
                    new TemplateItem("供应", "关键器件供应可获得性初判无致命障碍", "初步供应商评估", false),
                    new TemplateItem("质量", "目标质量水平与合规要求（认证/法规）已识别", "合规要求清单", false))),
            new Template("DCP2", "计划决策（PDCP/DCP2）", "回答\"计划可信不可信\"", List.of(
                    new TemplateItem("商业", "需求基线已冻结并经评审（范围承诺）", "需求基线清单", true),
                    new TemplateItem("技术", "系统架构设计评审通过", "架构评审纪要", true),
                    new TemplateItem("技术", "开发计划与关键路径已排定，估算有依据", "项目计划", false),
                    new TemplateItem("质量", "测试策略与验收标准已定义", "测试策略文档", false),
                    new TemplateItem("供应", "长周期物料已识别并下单/锁定产能", "长周期物料清单", true),
                    new TemplateItem("制造", "可制造性(DFM)初步评审完成", "DFM 评审记录", false),
                    new TemplateItem("商业", "项目预算与投资回报测算更新并获批", "预算批复", false))),
            new Template("ADCP", "可获得性决策（ADCP）", "回答\"能不能规模交付\"", List.of(
                    new TemplateItem("质量", "系统测试完成，无未关闭的致命/严重缺陷", "测试报告", true),
                    new TemplateItem("质量", "可靠性验证（寿命/环境）达标", "可靠性测试报告", true),
                    new TemplateItem("制造", "试产完成，直通率达到目标", "试产总结", true),
                    new TemplateItem("供应", "量产物料齐套，供应商质量协议签署", "齐套核查表", false),
                    new TemplateItem("上市", "认证取证完成（目标市场强制认证）", "证书", true),
                    new TemplateItem("上市", "服务与维修方案就绪（备件/培训/文档）", "服务准备清单", false))),
            new Template("GA", "发布决策（GA/生命周期）", "回答\"可不可以全面上市\"", List.of(
                    new TemplateItem("上市", "首批市场反馈与早期质量数据达标", "早期质量报告", true),
                    new TemplateItem("上市", "渠道/定价/促销物料就绪", "上市包检查单", false),
                    new TemplateItem("质量", "现网问题闭环机制运行（响应 SLA 明确）", "问题管理流程", false),
                    new TemplateItem("供应", "供应爬坡计划与安全库存达成", "爬坡计划", false),
                    new TemplateItem("商业", "商业目标达成情况回顾与生命周期计划", "生命周期计划", false))));

    private final GateCriterionService criterionService;
    private final GateCriterionMapper criterionMapper;

    /**
     * 模板服务依赖注入。
     * criterionService 提供单条条件创建与审计能力；
     * criterionMapper 用于模板幂等比较（按 criterion 文本去重）。
     */
    public CriterionTemplateService(GateCriterionService criterionService,
                                    GateCriterionMapper criterionMapper) {
        this.criterionService = criterionService;
        this.criterionMapper = criterionMapper;
    }

    /**
     * 返回内置模板清单。
     *
     * <p>静态常量 `TEMPLATES` 当前为内置字典（无数据库写入），
     * 可用于前端下拉展示、模板预览和幂等应用前置比对。</p>
     * <p>返回引用是不可变列表；调用方应按内容读取后再决策是否应用，不应修改返回对象。</p>
     */
    public List<Template> templates() {
        return TEMPLATES;
    }

    /**
     * 应用 DCP 模板到目标项目。
     *
     * <p>按模板 key 读取配置项，逐条判断同文本 criterion 是否已存在，存在则跳过（幂等）；不存在则创建。</p>
     * <p>返回值返回实际创建与跳过统计，供调用方进行 UI 提示。</p>
     * <p>更新粒度与失败边界：</p>
     * <ul>
     *   <li>只在 `project_id` 维度内进行文本级幂等比对，不跨项目复用。</li>
     *   <li>每条未命中条件经过 `criterionService.create` 写入 `GATE_CRITERION`，默认带 `isRedline` 约束映射。</li>
     *   <li>整个方法在事务内；任何一条条件写入失败会触发回滚。</li>
     *   <li>返回 `codes` 是本次新建条件的 code 清单；`skipped` 是文本已存在导致跳过的项数。</li>
     * </ul>
     * <p>一致性说明：以 `criterion` 全文匹配作为幂等键，存在同文本不同大小写/空格差异时视为不同项（当前实现不做规范化归一化）。</p>
     *
     * @param projectId 项目 ID
     * @param stageGateId 阶段门 ID（可空）
     * @param templateKey 模板 key
     * @return created/skipped/codes 统计
     */
    @Transactional
    public Map<String, Object> apply(Long projectId, Long stageGateId, String templateKey) {
        Template tpl = TEMPLATES.stream().filter(t -> t.key().equals(templateKey)).findFirst()
                .orElseThrow(() -> new BusinessException("未知模板: " + templateKey));
        Set<String> existing = new HashSet<>();
        for (GateCriterion c : criterionMapper.selectList(new QueryWrapper<GateCriterion>()
                .eq("project_id", projectId))) {
            existing.add(c.getCriterion());
        }
        List<String> created = new ArrayList<>();
        int skipped = 0;
        for (TemplateItem item : tpl.items()) {
            if (existing.contains(item.criterion())) {
                skipped++;
                continue;
            }
            GateCriterion c = new GateCriterion();
            c.setProjectId(projectId);
            c.setStageGateId(stageGateId);
            c.setDomain(item.domain());
            c.setCriterion(item.criterion());
            c.setEvidenceReq(item.evidenceReq());
            c.setIsRedline(item.redline() ? 1 : 0);
            created.add(criterionService.create(c).getCode());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created.size());
        out.put("skipped", skipped);
        out.put("codes", created);
        return out;
    }
}
