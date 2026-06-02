package com.starshield.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.starshield.backend.entity.ChatMessageLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归档回填服务。
 */
@Service
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.API})
public class ArchiveBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveBackfillService.class);

    private final ChatMessageService chatMessageService;
    private final ArchiveSyncService archiveSyncService;

    public ArchiveBackfillService(ChatMessageService chatMessageService,
                                  ArchiveSyncService archiveSyncService) {
        this.chatMessageService = chatMessageService;
        this.archiveSyncService = archiveSyncService;
    }

    /**
     * 从 MySQL 分页回填归档数据到 ES。
     *
     * @author AI (under P6_ES_Search supervision)
     */
    public Map<String, Object> backfillToEs(Integer batchSize, Integer maxRows) {
        int pageSize = Math.max(1, Math.min(1000, batchSize == null ? 500 : batchSize));
        int rowLimit = Math.max(1, Math.min(1_000_000, maxRows == null ? 10_000 : maxRows));
        int current = 1;
        int synced = 0;

        while (synced < rowLimit) {
            int remaining = rowLimit - synced;
            int currentSize = Math.min(pageSize, remaining);
            Page<ChatMessageLog> page = chatMessageService.page(
                    new Page<>(current, currentSize, false),
                    new LambdaQueryWrapper<ChatMessageLog>()
                            .orderByAsc(ChatMessageLog::getId)
            );
            List<ChatMessageLog> records = page.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }

            for (ChatMessageLog record : records) {
                archiveSyncService.syncToEs(record);
                synced++;
            }

            log.info("[ArchiveBackfill] synced={} batchSize={} lastPage={}", synced, records.size(), current);

            if (records.size() < currentSize) {
                break;
            }
            current++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("synced", synced);
        result.put("batchSize", pageSize);
        result.put("maxRows", rowLimit);
        return result;
    }
}
