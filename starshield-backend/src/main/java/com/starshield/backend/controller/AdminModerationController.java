package com.starshield.backend.controller;

import com.starshield.backend.common.Result;
import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import com.starshield.backend.entity.ChatMessageLog;
import com.starshield.backend.entity.ModerationAuditLog;
import com.starshield.backend.service.AdminReviewService;
import com.starshield.backend.service.IdempotencyService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理后台审核工作流接口。
 */
@RestController
@RequestMapping("/api/admin/moderation")
@CrossOrigin(origins = "*")
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.API})
public class AdminModerationController {

    private final AdminReviewService adminReviewService;
    private final IdempotencyService idempotencyService;

    public AdminModerationController(AdminReviewService adminReviewService,
                                     IdempotencyService idempotencyService) {
        this.adminReviewService = adminReviewService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 生成幂等键。
     *
     * @author AI (under P5 supervision)
     */
    @GetMapping("/idempotency-key")
    public Result<Map<String, String>> createIdempotencyKey() {
        String key = idempotencyService.createKey();
        return Result.success(Map.of("idempotencyKey", key));
    }

    /**
     * 待审核列表。
     *
     * @author AI (under P5 supervision)
     */
    @GetMapping("/pending")
    public Result<List<ChatMessageLog>> pending(@RequestParam(defaultValue = "1") Integer page,
                                                @RequestParam(defaultValue = "20") Integer pageSize) {
        int size = Math.max(1, Math.min(200, pageSize));
        return Result.success(adminReviewService.queryPending(page, size));
    }

    /**
     * 查询审计日志。
     *
     * @author AI (under P5 supervision)
     */
    @GetMapping("/{id}/audit-logs")
    public Result<List<ModerationAuditLog>> auditLogs(@PathVariable Long id,
                                                      @RequestParam(required = false) String reasonTag,
                                                      @RequestParam(defaultValue = "20") Integer limit) {
        int size = Math.max(1, Math.min(100, limit));
        return Result.success(adminReviewService.queryAuditLogs(id, reasonTag, size));
    }

    /**
     * 确认封禁。
     *
     * @author AI (under P5 supervision)
     */
    @PostMapping("/{id}/confirm-ban")
    public Result<Void> confirmBan(@PathVariable Long id,
                                   @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                   @RequestBody(required = false) Map<String, String> body) {
        if (!idempotencyService.consumeKey(idemKey)) {
            return Result.error(409, "幂等键无效或重复");
        }
        String operator = body == null ? "system" : body.getOrDefault("operator", "system");
        boolean ok = adminReviewService.confirmBan(id, operator);
        return ok ? Result.success("已确认封禁", null) : Result.error(404, "记录不存在");
    }

    /**
     * 解除封禁。
     *
     * @author AI (under P5 supervision)
     */
    @PostMapping("/{id}/release")
    public Result<Void> release(@PathVariable Long id,
                                @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                @RequestBody(required = false) Map<String, String> body) {
        if (!idempotencyService.consumeKey(idemKey)) {
            return Result.error(409, "幂等键无效或重复");
        }
        String operator = body == null ? "system" : body.getOrDefault("operator", "system");
        boolean ok = adminReviewService.release(id, operator);
        return ok ? Result.success("已解除封禁", null) : Result.error(404, "记录不存在");
    }

    /**
     * 批量处理请求体
     */
    public static class BatchReq {
        public List<Long> ids;
        public String decision;
        public String reasonTag;
        public String operator;
    }

    /**
     * 批量处理。
     *
     * @author AI (under P5 supervision)
     */
    @PostMapping("/batch")
    public Result<Map<Long, String>> batchProcess(@RequestBody BatchReq req) {
        if (req.ids == null || req.ids.isEmpty()) {
            return Result.error(400, "记录ID列表不能为空");
        }
        if (!"BLOCK".equals(req.decision) && !"PASS".equals(req.decision) && !"REVIEW".equals(req.decision)) {
            return Result.error(400, "无效的决策类型");
        }
        String operator = req.operator == null || req.operator.isBlank() ? "system" : req.operator;
        Map<Long, String> failures = adminReviewService.batchProcess(req.ids, req.decision, req.reasonTag, operator);
        if (failures.isEmpty()) {
            return Result.success("批量处理成功", failures);
        } else {
            Result<Map<Long, String>> res = Result.error(207, "部分或全部处理失败");
            res.setData(failures);
            return res;
        }
    }
}
