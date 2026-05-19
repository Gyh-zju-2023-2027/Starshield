package com.starshield.backend.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日AI治理战报缓存
 */
@Data
@TableName(value = "daily_report_cache", autoResultMap = true)
public class DailyReportCache {
    
    /**
     * 报告日期（主键）
     */
    @TableId
    private LocalDate date;
    
    /**
     * 构建好的战报详细内容，存放 JSON
     */
    private String payloadJson;
    
    /**
     * 记录生成的时间
     */
    private LocalDateTime createTime;
}
