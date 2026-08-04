-- 路标图日期字段：阶段/DCP 的计划与预测评审日、产品版本的计划与实际发布日。
-- DCP 实际评审日不设列（由 decision.decided_at 推得，避免双源）。
ALTER TABLE stage_gate
  ADD COLUMN plan_date DATE NULL COMMENT 'DCP 计划评审日',
  ADD COLUMN forecast_date DATE NULL COMMENT 'DCP 预测评审日';

ALTER TABLE product_version
  ADD COLUMN plan_release_date DATE NULL COMMENT '计划发布日',
  ADD COLUMN actual_release_date DATE NULL COMMENT '实际发布日';
