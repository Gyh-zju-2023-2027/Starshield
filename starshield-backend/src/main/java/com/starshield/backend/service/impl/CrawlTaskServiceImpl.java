package com.starshield.backend.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshield.backend.entity.CrawlTask;
import com.starshield.backend.mapper.CrawlTaskMapper;
import com.starshield.backend.service.CrawlTaskService;
import com.starshield.backend.service.SubmitCrawlTaskRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class CrawlTaskServiceImpl implements CrawlTaskService {

    @Autowired
    private CrawlTaskMapper crawlTaskMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Long, Process> runningProcesses = new ConcurrentHashMap<>();

    /** ✅ 使用 JDK 自带线程池 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @Override
    public Long submit(SubmitCrawlTaskRequest req) {
        CrawlTask task = new CrawlTask();
        task.setType(req.getType());
        task.setTargetsJson(toJson(req.getTargets()));
        task.setTargetCount(req.getTargetCount());
        task.setFetchedCount(0);
        task.setPushedCount(0);
        task.setStatus("pending");
        task.setCreateTime(LocalDateTime.now());
        crawlTaskMapper.insert(task);

        int rps = req.getRps() != null ? req.getRps() : 20;
        executor.submit(() -> runPython(task.getId(), rps));
        return task.getId();
    }

    @Override
    public List<CrawlTask> listTasks(int limit) {
        return crawlTaskMapper.selectList(
                Wrappers.<CrawlTask>lambdaQuery()
                        .orderByDesc(CrawlTask::getCreateTime)
                        .last("LIMIT " + Math.min(limit, 100)));
    }

    @Override
    public CrawlTask getStatus(Long id) {
        return crawlTaskMapper.selectById(id);
    }

    @Override
    public void stop(Long id) {
        Process p = runningProcesses.get(id);
        if (p != null) {
            p.destroy();
            runningProcesses.remove(id);
        }

        CrawlTask task = crawlTaskMapper.selectById(id);
        if (task != null && !"finished".equals(task.getStatus())) {
            task.setStatus("failed");
            task.setErrorMsg("用户手动终止");
            task.setFinishTime(LocalDateTime.now());
            crawlTaskMapper.updateById(task);
        }
    }

    @Override
    public void updateProgress(Long taskId, Integer fetched, Integer pushed) {
        crawlTaskMapper.update(null, Wrappers.<CrawlTask>lambdaUpdate()
                .eq(CrawlTask::getId, taskId)
                .set(CrawlTask::getFetchedCount, fetched)
                .set(CrawlTask::getPushedCount, pushed));
    }

    @Override
    public void markFailed(Long taskId, String errorMsg) {
        crawlTaskMapper.update(null, Wrappers.<CrawlTask>lambdaUpdate()
                .eq(CrawlTask::getId, taskId)
                .set(CrawlTask::getStatus, "failed")
                .set(CrawlTask::getErrorMsg, errorMsg)
                .set(CrawlTask::getFinishTime, LocalDateTime.now()));
    }

    private void runPython(Long taskId, int rps) {
        CrawlTask task = crawlTaskMapper.selectById(taskId);
        if (task == null) {
            log.error("Task not found: {}", taskId);
            return;
        }

        Process process = null;
        try {
            crawlTaskMapper.update(null, Wrappers.<CrawlTask>lambdaUpdate()
                    .eq(CrawlTask::getId, taskId)
                    .set(CrawlTask::getStatus, "running"));

            List<String> targets = objectMapper.readValue(task.getTargetsJson(), List.class);

            // ✅ 计算项目根目录（Starshield/）
            String projectRoot = new java.io.File(
                System.getProperty("user.dir")
            ).getParent();

            String scriptPath = projectRoot + "/bilichat-ingest/ingest_comments.py";

            List<String> cmd = new ArrayList<>();
            cmd.add("python3");
            cmd.add(scriptPath);
            cmd.add("--from-task");
            cmd.add(taskId.toString());
            cmd.add("--target-count");
            cmd.add(task.getTargetCount().toString());
            cmd.add("--rps");
            cmd.add(String.valueOf(rps));

            if ("live".equals(task.getType())) {
                cmd.add("--type");
                cmd.add("live");
                for (String target : targets) {
                    cmd.add("--live-room-id");
                    cmd.add(target);
                }
            } else {
                for (String target : targets) {
                    cmd.add("--bvid");
                    cmd.add(target);
                }
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            // ✅ 关键：设置工作目录为项目根目录
            pb.directory(new java.io.File(projectRoot));
            pb.redirectErrorStream(true);

            process = pb.start();
            runningProcesses.put(taskId, process);

            boolean pythonReportedError = false;

            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[PYTHON] {}", line);

                try {
                    JsonNode node = objectMapper.readTree(line);
                    String event = node.get("event").asText();

                    if ("progress".equals(event)) {
                        // 抓取进度有 fetched 无 pushed；推送进度有 pushed 无 fetched
                        if (node.has("fetched")) {
                            int fetched = node.get("fetched").asInt();
                            int pushed = node.has("pushed") ? node.get("pushed").asInt() : 0;
                            updateProgress(taskId, fetched, pushed);
                        } else if (node.has("pushed")) {
                            int pushed = node.get("pushed").asInt();
                            crawlTaskMapper.update(null, Wrappers.<CrawlTask>lambdaUpdate()
                                    .eq(CrawlTask::getId, taskId)
                                    .set(CrawlTask::getPushedCount, pushed));
                        }
                    } 
                    else if ("fetch_done".equals(event)) {
                        int fetched = node.get("fetched").asInt();
                        crawlTaskMapper.update(null, Wrappers.<CrawlTask>lambdaUpdate()
                                .eq(CrawlTask::getId, taskId)
                                .set(CrawlTask::getFetchedCount, fetched));
                    } 
                    else if ("error".equals(event)) {
                        String msg = node.has("message") ? node.get("message").asText() : "未知错误";
                        pythonReportedError = true;
                        markFailed(taskId, msg);
                    }
                    else if ("push_start".equals(event) || "finished".equals(event)) {
                        int pushed = node.get("pushed").asInt();
                        crawlTaskMapper.update(null, Wrappers.<CrawlTask>lambdaUpdate()
                                .eq(CrawlTask::getId, taskId)
                                .set(CrawlTask::getPushedCount, pushed));
                    }
                } catch (Exception e) {
                    log.warn("Parse failed: {}", line, e);
                }
            }

            int exitCode = process.waitFor();
            if (!pythonReportedError) {
                if (exitCode == 0) {
                    crawlTaskMapper.update(null, Wrappers.<CrawlTask>lambdaUpdate()
                            .eq(CrawlTask::getId, taskId)
                            .set(CrawlTask::getStatus, "finished")
                            .set(CrawlTask::getFinishTime, LocalDateTime.now()));
                } else {
                    markFailed(taskId, "Python exit code=" + exitCode);
                }
            }

        } catch (Exception e) {
            log.error("Crawl task failed, taskId={}", taskId, e);
            markFailed(taskId, e.getMessage());
        } finally {
            if (process != null) {
                runningProcesses.remove(taskId);
            }
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}