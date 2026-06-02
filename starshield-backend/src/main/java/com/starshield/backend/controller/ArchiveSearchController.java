package com.starshield.backend.controller;

import com.starshield.backend.common.Result;
import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import com.starshield.backend.dto.ArchiveSearchHit;
import com.starshield.backend.entity.ChatMessageLog;
import com.starshield.backend.service.ArchiveBackfillService;
import com.starshield.backend.service.ArchiveSearchService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 百万级发言检索中台接口。
 */
@RestController
@RequestMapping("/api/archive")
@CrossOrigin(origins = "*")
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.API})
public class ArchiveSearchController {

    private final ArchiveSearchService archiveSearchService;
    private final ArchiveBackfillService archiveBackfillService;

    public ArchiveSearchController(ArchiveSearchService archiveSearchService,
                                   ArchiveBackfillService archiveBackfillService) {
        this.archiveSearchService = archiveSearchService;
        this.archiveBackfillService = archiveBackfillService;
    }

    /**
     * 组合条件检索。
     *
     * @author AI (under P6 supervision)
     */
    @GetMapping("/search")
    public Result<List<ChatMessageLog>> search(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String playerId,
                                               @RequestParam(required = false) String decision,
                                               @RequestParam(required = false) String labels,
                                               @RequestParam(required = false) LocalDateTime startTime,
                                               @RequestParam(required = false) LocalDateTime endTime,
                                               @RequestParam(defaultValue = "1") Integer page,
                                               @RequestParam(defaultValue = "200") Integer limit) {
        return Result.success(archiveSearchService.search(
                keyword,
                playerId,
                decision,
                labels,
                startTime,
                endTime,
                page,
                limit
        ));
    }

    /**
     * 组合条件检索，返回 ES 高亮片段。
     *
     * @author AI (under P6_ES_Search supervision)
     */
    @GetMapping("/search/highlight")
    public Result<List<ArchiveSearchHit>> searchWithHighlight(@RequestParam(required = false) String keyword,
                                                              @RequestParam(required = false) String playerId,
                                                              @RequestParam(required = false) String decision,
                                                              @RequestParam(required = false) String labels,
                                                              @RequestParam(required = false) LocalDateTime startTime,
                                                              @RequestParam(required = false) LocalDateTime endTime,
                                                              @RequestParam(defaultValue = "1") Integer page,
                                                              @RequestParam(defaultValue = "200") Integer limit) {
        return Result.success(archiveSearchService.searchWithHighlight(
                keyword,
                playerId,
                decision,
                labels,
                startTime,
                endTime,
                page,
                limit
        ));
    }

    /**
     * 归档聚合分析。
     *
     * @author AI (under P6_ES_Search supervision)
     */
    @GetMapping("/analysis")
    public Result<Map<String, Object>> analysis(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String playerId,
                                                @RequestParam(required = false) String decision,
                                                @RequestParam(required = false) String labels,
                                                @RequestParam(required = false) LocalDateTime startTime,
                                                @RequestParam(required = false) LocalDateTime endTime,
                                                @RequestParam(defaultValue = "5") Integer topHitLimit) {
        return Result.success(archiveSearchService.analyze(
                keyword,
                playerId,
                decision,
                labels,
                startTime,
                endTime,
                topHitLimit
        ));
    }

    /**
     * 从 MySQL 回填归档数据到 ES。
     *
     * @author AI (under P6_ES_Search supervision)
     */
    @PostMapping("/reindex")
    public Result<Map<String, Object>> reindex(@RequestParam(defaultValue = "500") Integer batchSize,
                                               @RequestParam(defaultValue = "10000") Integer maxRows) {
        return Result.success(archiveBackfillService.backfillToEs(batchSize, maxRows));
    }
}
