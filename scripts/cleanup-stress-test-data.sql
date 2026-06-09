-- 仅保留 bilichat-ingest 抓取的真实 B 站评论（player_id 以 BILI_ 开头）
-- 删除 Locust 压测（vu*_p*）、安全测试（sec_* / p1 等）及合成数据

USE starshield;

-- 1. 删除非真实评论
DELETE FROM chat_message_log
WHERE player_id NOT LIKE 'BILI\_%' ESCAPE '\\'
   OR player_id IS NULL;

-- 2. 清理已无关联消息的审计记录
DELETE m FROM moderation_audit_log m
LEFT JOIN chat_message_log c ON m.message_id = c.id
WHERE c.id IS NULL;

-- 3. 战报缓存含压测期错误统计，清空后访问 /api/reports/daily 会重建
DELETE FROM daily_report_cache;
