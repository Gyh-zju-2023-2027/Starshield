package com.starshield.backend.service;

import com.starshield.backend.entity.CrawlTask;

import java.util.List;

public interface CrawlTaskService {

    /**
     * 提交爬取任务
     */
    Long submit(SubmitCrawlTaskRequest req);

    /**
     * 获取最近 N 个任务列表
     */
    List<CrawlTask> listTasks(int limit);

    /**
     * 获取任务状态
     */
    CrawlTask getStatus(Long id);

    /**
     * 终止任务
     */
    void stop(Long id);

    /**
     * 更新进度（供 Python 回调使用）
     */
    void updateProgress(Long taskId, Integer fetched, Integer pushed);

    /**
     * 标记任务失败
     */
    void markFailed(Long taskId, String errorMsg);
}

