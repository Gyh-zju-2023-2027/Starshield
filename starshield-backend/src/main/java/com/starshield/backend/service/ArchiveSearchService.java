package com.starshield.backend.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshield.backend.entity.ChatMessageLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 检索服务（ES 优先，MySQL 兜底）。
 */
@Service
public class ArchiveSearchService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveSearchService.class);
    private static final String ARCHIVE_INDEX = "chat_message_archive";
    private static final String ARCHIVE_ID_SORT_FIELD = "id.keyword";
    private static final DateTimeFormatter ES_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final DateTimeFormatter MYSQL_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChatMessageService chatMessageService;
    private final ElasticsearchClient elasticsearchClient;

    @Value("${starshield.archive.es-enabled:false}")
    private boolean esEnabled;

    public ArchiveSearchService(ChatMessageService chatMessageService,
                                @Autowired(required = false) ElasticsearchClient elasticsearchClient) {
        this.chatMessageService = chatMessageService;
        this.elasticsearchClient = elasticsearchClient;
    }

    /**
     * 组合检索。
     *
     * @author AI (under P6 supervision)
     */
    public List<ChatMessageLog> search(String keyword,
                                       String playerId,
                                       String decision,
                                       String labels,
                                       LocalDateTime startTime,
                                       LocalDateTime endTime,
                                       Integer page,
                                       Integer limit) {
        int pageNo = Math.max(1, page == null ? 1 : page);
        int pageSize = Math.max(1, Math.min(1000, limit == null ? 200 : limit));

        if (esEnabled && elasticsearchClient != null) {
            try {
                List<ChatMessageLog> results = searchFromEs(keyword, playerId, decision, labels, startTime, endTime, pageNo, pageSize);
                log.info("[ArchiveSearch] path=ES page={} limit={} keyword={} playerId={} hits={}",
                        pageNo, pageSize, keyword, playerId, results.size());
                return results;
            } catch (Exception e) {
                log.warn("[ArchiveSearch] ES query failed, fallback to MySQL. page={}, limit={}", pageNo, pageSize, e);
            }
        }
        List<ChatMessageLog> results = searchFromMysql(keyword, playerId, decision, labels, startTime, endTime, pageNo, pageSize);
        log.info("[ArchiveSearch] path=MYSQL page={} limit={} keyword={} playerId={} hits={}",
                pageNo, pageSize, keyword, playerId, results.size());
        return results;
    }

    private List<ChatMessageLog> searchFromEs(String keyword,
                                              String playerId,
                                              String decision,
                                              String labels,
                                              LocalDateTime startTime,
                                              LocalDateTime endTime,
                                              int page,
                                              int size) throws IOException {
        Query query = buildEsQuery(keyword, playerId, decision, labels, startTime, endTime);
        List<FieldValue> searchAfter = null;
        SearchResponse<JsonNode> response = null;

        for (int currentPage = 1; currentPage <= page; currentPage++) {
            SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                    .index(ARCHIVE_INDEX)
                    .query(query)
                    .size(size)
                    .sort(sort -> sort.field(field -> field.field("create_time").order(SortOrder.Desc)))
                    .sort(sort -> sort.field(field -> field.field(ARCHIVE_ID_SORT_FIELD).order(SortOrder.Desc)));

            if (searchAfter != null && !searchAfter.isEmpty()) {
                requestBuilder.searchAfter(searchAfter);
            }

            response = elasticsearchClient.search(requestBuilder.build(), JsonNode.class);
            List<co.elastic.clients.elasticsearch.core.search.Hit<JsonNode>> hits = response.hits().hits();
            if (hits.isEmpty()) {
                return Collections.emptyList();
            }

            if (currentPage < page) {
                searchAfter = hits.get(hits.size() - 1).sort();
                if (searchAfter == null || searchAfter.isEmpty()) {
                    return Collections.emptyList();
                }
            }
        }

        if (response == null) {
            return Collections.emptyList();
        }

        return response.hits().hits().stream()
            .map(co.elastic.clients.elasticsearch.core.search.Hit::source)
            .map(this::toChatLog)
                .collect(Collectors.toList());
    }

    private List<ChatMessageLog> searchFromMysql(String keyword,
                                                 String playerId,
                                                 String decision,
                                                 String labels,
                                                 LocalDateTime startTime,
                                                 LocalDateTime endTime,
                                                 int page,
                                                 int size) {
        int offset = Math.max(page - 1, 0) * size;
        LambdaQueryWrapper<ChatMessageLog> query = new LambdaQueryWrapper<>();
        query.like(keyword != null && !keyword.isBlank(), ChatMessageLog::getContent, keyword)
                .eq(playerId != null && !playerId.isBlank(), ChatMessageLog::getPlayerId, playerId)
                .eq(decision != null && !decision.isBlank(), ChatMessageLog::getDecision, decision)
                .like(labels != null && !labels.isBlank(), ChatMessageLog::getLabels, labels)
                .ge(startTime != null, ChatMessageLog::getCreateTime, startTime)
                .le(endTime != null, ChatMessageLog::getCreateTime, endTime)
                .orderByDesc(ChatMessageLog::getCreateTime)
                .last("limit " + offset + "," + size);
        return chatMessageService.list(query);
    }

    private ChatMessageLog toChatLog(JsonNode source) {
        if (source == null || source.isMissingNode()) {
            return new ChatMessageLog();
        }
        return new ChatMessageLog()
                .setId(toLong(source.get("id")))
                .setPlayerId(toText(source.get("player_id"), source.get("playerId")))
                .setContent(toText(source.get("content")))
                .setPlatform(toText(source.get("platform")))
                .setDecision(toText(source.get("decision")))
                .setRiskScore(toInteger(source.get("risk_score"), source.get("riskScore")))
                .setLabels(toText(source.get("labels")))
                .setCreateTime(toLocalDateTime(source.get("create_time"), source.get("createTime")));
    }

    private Query buildEsQuery(String keyword,
                               String playerId,
                               String decision,
                               String labels,
                               LocalDateTime startTime,
                               LocalDateTime endTime) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        if (hasText(keyword)) {
            boolQuery.must(query -> query.match(match -> match.field("content").query(keyword)));
        }

        if (hasText(playerId)) {
            boolQuery.filter(query -> query.term(term -> term.field("player_id").value(playerId)));
        }

        if (hasText(decision)) {
            boolQuery.filter(query -> query.term(term -> term.field("decision").value(decision)));
        }

        if (hasText(labels)) {
            boolQuery.filter(query -> query.wildcard(wildcard -> wildcard
                    .field("labels")
                    .value("*" + escapeWildcard(labels) + "*")
                    .caseInsensitive(true)));
        }

        if (startTime != null || endTime != null) {
            boolQuery.filter(query -> query.range(range -> {
                range.field("create_time");
                if (startTime != null) {
                    range.gte(JsonData.of(formatEsDateTime(startTime)));
                }
                if (endTime != null) {
                    range.lte(JsonData.of(formatEsDateTime(endTime)));
                }
                return range;
            }));
        }

        return Query.of(query -> query.bool(boolQuery.build()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String formatEsDateTime(LocalDateTime value) {
        return value.format(ES_DATE_TIME_FORMATTER);
    }

    private String escapeWildcard(String value) {
        return value.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("?", "\\?");
    }

    private String toText(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && !candidate.isNull()) {
                return candidate.asText();
            }
        }
        return null;
    }

    private Integer toInteger(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && !candidate.isNull()) {
                return candidate.isInt() ? candidate.intValue() : Integer.valueOf(candidate.asText());
            }
        }
        return null;
    }

    private Long toLong(JsonNode candidate) {
        if (candidate == null || candidate.isNull()) {
            return null;
        }
        try {
            return Long.valueOf(candidate.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private LocalDateTime toLocalDateTime(JsonNode... candidates) {
        String value = toText(candidates);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(value, ES_DATE_TIME_FORMATTER);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(value, MYSQL_DATE_TIME_FORMATTER);
        } catch (Exception ignored) {
            return null;
        }
    }
}
