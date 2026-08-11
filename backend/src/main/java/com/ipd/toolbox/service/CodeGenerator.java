package com.ipd.toolbox.service;

import com.ipd.toolbox.mapper.CodeSeqMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一编号生成器（T107）。格式：{项目代码}-{类型缩写}-{三位序号}，如 OVN1-REQ-001。
 * 通过 code_seq 表 + FOR UPDATE 行锁保证并发安全。
 */
@Service
public class CodeGenerator {

    private final CodeSeqMapper mapper;

    /**
     * 编号生成器依赖注入。
     * mapper 的 FOR UPDATE 计数器是并发安全的序号来源，服务不直接持有业务上下文。
     */
    public CodeGenerator(CodeSeqMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 在当前项目+类型维度上分配下一个序号。
     *
     * <p>口径：</p>
     * <ul>
     *   <li>先查询当前 `(projectId,typeAbbr)` 当前序号（FOR UPDATE）。</li>
     *   <li>若无历史，初始化为 1 并写入序号 2；若有历史，返回当前值并自增为下一次值。</li>
     *   <li>返回值格式：`项目代码-类型-三位序号`。</li>
     * </ul>
     * <p>并发边界：`mapper.selectForUpdate` + `update` 在事务内，默认借助数据库行锁避免并发发号重复。</p>
     * <p>失败策略：当前方法不 catch 异常，异常会向上传播并导致调用方回滚（包括调用方自身事务）。</p>
     *
     * @param projectId 所属项目
     * @param projectCode 项目代码（入参源自 project.code）
     * @param typeAbbr 工作项类型缩写，如 REQ/TSK/CHG 等
     * @return 按三位补齐后的业务编号
     */
    @Transactional
    public String next(Long projectId, String projectCode, String typeAbbr) {
        Integer cur = mapper.selectForUpdate(projectId, typeAbbr);
        int seq;
        if (cur == null) {
            seq = 1;
            mapper.insert(projectId, typeAbbr, 2);
        } else {
            seq = cur;
            mapper.update(projectId, typeAbbr, cur + 1);
        }
        return String.format("%s-%s-%03d", projectCode, typeAbbr, seq);
    }
}
