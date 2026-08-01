-- 角色种子数据（对应 docs/03-角色权限矩阵.md）
INSERT INTO sys_role (code, name) VALUES
  ('ADMIN',    '系统管理员'),
  ('PM',       '项目经理'),
  ('PO',       '产品负责人'),
  ('DEV',      '开发'),
  ('QA',       '测试'),
  ('QUALITY',  '质量负责人'),
  ('REVIEWER', '评审角色');

-- admin 用户及其角色分配由启动初始化器 DataInitializer 完成（BCrypt 哈希密码），
-- 避免在 SQL 中硬编码密码哈希。
