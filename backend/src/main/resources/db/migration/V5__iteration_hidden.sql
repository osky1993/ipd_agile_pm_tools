-- Sprint 看板体验：迭代支持隐藏（不删数据，仅默认不在看板展示）
ALTER TABLE iteration ADD COLUMN hidden TINYINT NOT NULL DEFAULT 0 COMMENT '1=在看板隐藏';
