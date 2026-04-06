package com.starshield.backend.controller;

import com.starshield.backend.common.Result;
import com.starshield.backend.entity.ChatMessageLog;
import com.starshield.backend.service.ArchiveSearchService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 百万级发言检索中台接口。
 */
@RestController
@RequestMapping("/api/archive")
@CrossOrigin(origins = "*")
public class ArchiveSearchController {

    private final ArchiveSearchService archiveSearchService;

    public ArchiveSearchController(ArchiveSearchService archiveSearchService) {
        this.archiveSearchService = archiveSearchService;
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
}
