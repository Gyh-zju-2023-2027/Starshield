package com.starshield.backend.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.starshield.backend.entity.ChatMessageLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 检索归档服务（双写至 ES）。
 */
@Service
public class ArchiveSyncService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveSyncService.class);
    private static final String ARCHIVE_INDEX = "chat_message_archive";
    private static final DateTimeFormatter ES_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final ElasticsearchClient elasticsearchClient;

    @Value("${starshield.archive.es-enabled:false}")
    private boolean esEnabled;

    public ArchiveSyncService(@Autowired(required = false) ElasticsearchClient elasticsearchClient) {
        this.elasticsearchClient = elasticsearchClient;
    }

    /**
     * 同步一条记录到 ES。
     *
     * @author AI (under P6 supervision)
     */
    public void syncToEs(ChatMessageLog chatMessageLog) {
        if (!esEnabled || elasticsearchClient == null || chatMessageLog == null || chatMessageLog.getId() == null) {
            return;
        }

        try {
            Map<String, Object> document = new HashMap<>();
            document.put("id", String.valueOf(chatMessageLog.getId()));
            document.put("player_id", chatMessageLog.getPlayerId());
            document.put("content", chatMessageLog.getContent());
            document.put("platform", chatMessageLog.getPlatform());
            document.put("decision", chatMessageLog.getDecision());
            document.put("risk_score", chatMessageLog.getRiskScore());
            document.put("labels", chatMessageLog.getLabels());
            document.put("create_time", chatMessageLog.getCreateTime() == null ? null : chatMessageLog.getCreateTime().format(ES_DATE_TIME_FORMATTER));

            elasticsearchClient.index(request -> request
                    .index(ARCHIVE_INDEX)
                    .id(String.valueOf(chatMessageLog.getId()))
                    .document(document));
        } catch (Exception e) {
            log.warn("[ArchiveSync] ES sync skipped due to indexing error. messageId={}", chatMessageLog.getId(), e);
        }
    }
}
