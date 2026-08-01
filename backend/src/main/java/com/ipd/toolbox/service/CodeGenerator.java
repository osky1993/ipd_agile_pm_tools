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

    public CodeGenerator(CodeSeqMapper mapper) {
        this.mapper = mapper;
    }

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
