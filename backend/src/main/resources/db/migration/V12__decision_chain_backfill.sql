-- 回填历史决策修订链：同 subject 按 id 先后串 prev_decision_id
-- （此前字段只读未写；此后由 DecisionService.record 自动维护）
UPDATE decision d JOIN (
    SELECT id, LAG(id) OVER (PARTITION BY subject_type, subject_id ORDER BY id) AS prev
    FROM decision
) t ON d.id = t.id
SET d.prev_decision_id = t.prev
WHERE d.prev_decision_id IS NULL AND t.prev IS NOT NULL;
