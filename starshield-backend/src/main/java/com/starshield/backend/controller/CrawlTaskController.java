package com.starshield.backend.controller;

import com.starshield.backend.common.Result;
import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import com.starshield.backend.entity.CrawlTask;
import com.starshield.backend.service.CrawlTaskService;
import com.starshield.backend.service.SubmitCrawlTaskRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/crawl")
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.API})
public class CrawlTaskController {

    @Autowired
    private CrawlTaskService crawlTaskService;

    /**
     * 提交爬取任务
     */
    @PostMapping("/tasks")
    public Result<Long> submit(@RequestBody SubmitCrawlTaskRequest req) {
        Long taskId = crawlTaskService.submit(req);
        return Result.success(taskId);
    }

    /**
     * 最近任务列表
     */
    @GetMapping("/tasks")
    public Result<List<CrawlTask>> list(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(crawlTaskService.listTasks(limit));
    }

    /**
     * 查询任务状态
     */
    @GetMapping("/tasks/{id}")
    public Result<CrawlTask> status(@PathVariable Long id) {
        CrawlTask task = crawlTaskService.getStatus(id);
        return Result.success(task);
    }

    /**
     * 终止任务
     */
    @PostMapping("/tasks/{id}/stop")
    public Result<Void> stop(@PathVariable Long id) {
        crawlTaskService.stop(id);
        return Result.success();
    }
}