CREATE TABLE IF NOT EXISTS `daily_report_cache` (
    `date` DATE NOT NULL COMMENT '报告日期 primary key',
    `payload_json` JSON NOT NULL COMMENT '报告内容JSON序列化格式',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    PRIMARY KEY (`date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日AI治理战报缓存表';