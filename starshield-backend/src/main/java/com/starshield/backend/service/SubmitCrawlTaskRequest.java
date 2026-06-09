package com.starshield.backend.service;

import lombok.Data;
import java.util.List;
import jakarta.validation.constraints.*;

@Data
public class SubmitCrawlTaskRequest {
    @NotBlank
    private String type;

    @NotEmpty
    private List<@NotBlank String> targets;

    @Min(1)
    @Max(100000)
    private Integer targetCount;

    @Min(1)
    @Max(300)
    private Integer rps;

    /** 可选：B 站 Cookie（含 SESSDATA），仅用于本次任务，不落库 */
    @Size(max = 8192)
    private String cookie;
}