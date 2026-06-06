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
}