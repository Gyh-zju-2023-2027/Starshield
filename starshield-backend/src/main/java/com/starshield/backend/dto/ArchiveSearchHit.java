package com.starshield.backend.dto;

import com.starshield.backend.entity.ChatMessageLog;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 归档检索命中项，包含原始消息与 ES 高亮片段。
 *
 * @author AI (under P6_ES_Search supervision)
 */
@Data
@Accessors(chain = true)
public class ArchiveSearchHit {

    private ChatMessageLog message;

    private Map<String, List<String>> highlights = Collections.emptyMap();

    private String highlightContent;
}
