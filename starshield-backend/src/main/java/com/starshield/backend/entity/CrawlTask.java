package com.starshield.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 爬取任务表
 */
@Data
@TableName("crawl_task")
public class CrawlTask {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务类型：video-视频 live-直播间 */
    private String type;

    /** 目标列表（JSON数组字符串） */
    private String targetsJson;

    /** 目标抓取总条数 */
    private Integer targetCount;

    /** 已抓取条数 */
    private Integer fetchedCount;

    /** 已推送到MQ条数 */
    private Integer pushedCount;

    /** 任务状态：pending-待处理 running-运行中 finished-已完成 failed-失败 */
    private String status = "pending";

    /** 失败时的错误信息 */
    private String errorMsg;

    /** 任务创建时间 */
    private LocalDateTime createTime;

    /** 任务完成时间 */
    private LocalDateTime finishTime;
}