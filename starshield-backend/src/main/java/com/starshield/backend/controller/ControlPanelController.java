package com.starshield.backend.controller;

import com.starshield.backend.common.Result;
import com.starshield.backend.service.ControlPanelService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 动态规则与 Prompt 管理接口。
 */
@RestController
@RequestMapping("/api/control")
@CrossOrigin(origins = "*")
public class ControlPanelController {

    private final ControlPanelService controlPanelService;

    public ControlPanelController(ControlPanelService controlPanelService) {
        this.controlPanelService = controlPanelService;
    }

    /**
     * 获取敏感词。
     *
     * @author AI (under P1/P3 supervision)
     */
    @GetMapping("/rules/sensitive-words")
    public Result<List<String>> getSensitiveWords() {
        return Result.success(controlPanelService.getSensitiveWords());
    }

    /**
     * 更新敏感词。
     *
     * @author AI (under P1/P3 supervision)
     */
    @PutMapping("/rules/sensitive-words")
    public Result<Void> replaceSensitiveWords(@RequestBody Map<String, List<String>> body) {
        List<String> words = body.getOrDefault("words", List.of());
        controlPanelService.replaceSensitiveWords(words);
        return Result.success("更新成功", null);
    }

    /**
     * 获取 Prompt。
     *
     * @author AI (under P1/P3 supervision)
     */
    @GetMapping("/prompt")
    public Result<Map<String, String>> getPrompt() {
        return Result.success(Map.of("prompt", controlPanelService.getPrompt()));
    }

    /**
     * 更新 Prompt。
     *
     * @author AI (under P1/P3 supervision)
     */
    @PutMapping("/prompt")
    public Result<Void> setPrompt(@RequestBody Map<String, @NotBlank String> body) {
        controlPanelService.setPrompt(body.getOrDefault("prompt", ""));
        return Result.success("更新成功", null);
    }
}
