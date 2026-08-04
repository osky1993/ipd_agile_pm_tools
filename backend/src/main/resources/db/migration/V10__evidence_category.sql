-- 证据分类：EVIDENCE（正式证据，占 EV 编号、记审计、进治理页列表）
--           ATTACHMENT（描述附件如粘贴截图，AT- 随机码，不打扰证据清单）
ALTER TABLE evidence
  ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT 'EVIDENCE' COMMENT '证据分类 EVIDENCE/ATTACHMENT';

CREATE INDEX idx_ev_category ON evidence (project_id, category);
