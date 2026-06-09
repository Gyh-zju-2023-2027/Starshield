package com.starshield.backend.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starshield.backend.entity.ChatPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 玩家发言上传请求体（接入层校验）。
 */
@Data
public class ChatMessageUploadRequest {

    @NotBlank(message = "playerId 不能为空")
    @Size(max = 64, message = "playerId 长度不能超过 64")
    private String playerId;

    @NotBlank(message = "content 不能为空")
    @Size(max = 10000, message = "content 长度不能超过 10000")
    private String content;

    @NotNull(message = "platform 不能为空")
    private ChatPlatform platform;

    /**
     * 可选：原始发言时间（爬虫/数据集导入时传入，未传则由后端填充当前时间）。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime createTime;
}
