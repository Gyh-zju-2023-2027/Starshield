package com.starshield.backend.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval;
import co.elastic.clients.elasticsearch._types.aggregations.DateHistogramBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshield.backend.dto.ArchiveSearchHit;
import com.starshield.backend.entity.ChatMessageLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * @author AI (under P6_ES_Search supervision)
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

    /**
     * 组合检索，并返回 ES 高亮片段。
     *
     * @author AI (under P6_ES_Search supervision)
     */
    public List<ArchiveSearchHit> searchWithHighlight(String keyword,
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
                List<ArchiveSearchHit> results = searchWithHighlightFromEs(keyword, playerId, decision, labels, startTime, endTime, pageNo, pageSize);
                log.info("[ArchiveSearchHighlight] path=ES page={} limit={} keyword={} playerId={} hits={}",
                        pageNo, pageSize, keyword, playerId, results.size());
                return results;
            } catch (Exception e) {
                log.warn("[ArchiveSearchHighlight] ES query failed, fallback to MySQL. page={}, limit={}", pageNo, pageSize, e);
            }
        }

        List<ArchiveSearchHit> results = searchFromMysql(keyword, playerId, decision, labels, startTime, endTime, pageNo, pageSize).stream()
                .map(message -> new ArchiveSearchHit()
                        .setMessage(message)
                        .setHighlightContent(message.getContent()))
                .collect(Collectors.toList());
        log.info("[ArchiveSearchHighlight] path=MYSQL page={} limit={} keyword={} playerId={} hits={}",
                pageNo, pageSize, keyword, playerId, results.size());
        return results;
    }

    /**
     * 归档聚合分析。
     *
     * @author AI (under P6_ES_Search supervision)
     */
    public Map<String, Object> analyze(String keyword,
                                       String playerId,
                                       String decision,
                                       String labels,
                                       LocalDateTime startTime,
                                       LocalDateTime endTime,
                                       Integer topHitLimit) {
        int topSize = Math.max(1, Math.min(20, topHitLimit == null ? 5 : topHitLimit));

        if (esEnabled && elasticsearchClient != null) {
            try {
                Map<String, Object> results = analyzeFromEs(keyword, playerId, decision, labels, startTime, endTime, topSize);
                log.info("[ArchiveAnalyze] path=ES keyword={} playerId={} topHits={}",
                        keyword, playerId, topSize);
                return results;
            } catch (Exception e) {
                log.warn("[ArchiveAnalyze] ES aggregation failed, fallback to MySQL. topHits={}", topSize, e);
            }
        }

        Map<String, Object> results = analyzeFromMysql(keyword, playerId, decision, labels, startTime, endTime, topSize);
        log.info("[ArchiveAnalyze] path=MYSQL keyword={} playerId={} topHits={}",
                keyword, playerId, topSize);
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
                .map(Hit::source)
                .map(this::toChatLog)
                .collect(Collectors.toList());
    }

    private List<ArchiveSearchHit> searchWithHighlightFromEs(String keyword,
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
                    .sort(sort -> sort.field(field -> field.field(ARCHIVE_ID_SORT_FIELD).order(SortOrder.Desc)))
                    .highlight(highlight -> highlight
                            .preTags("<mark>")
                            .postTags("</mark>")
                            .fields("content", field -> field.numberOfFragments(0))
                            .fields("hit_words", field -> field.numberOfFragments(0))
                            .fields("labels", field -> field.numberOfFragments(0)));

            if (searchAfter != null && !searchAfter.isEmpty()) {
                requestBuilder.searchAfter(searchAfter);
            }

            response = elasticsearchClient.search(requestBuilder.build(), JsonNode.class);
            List<Hit<JsonNode>> hits = response.hits().hits();
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
                .map(this::toArchiveSearchHit)
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

    private Map<String, Object> analyzeFromEs(String keyword,
                                              String playerId,
                                              String decision,
                                              String labels,
                                              LocalDateTime startTime,
                                              LocalDateTime endTime,
                                              int topSize) throws IOException {
        Query query = buildEsQuery(keyword, playerId, decision, labels, startTime, endTime);

        SearchResponse<JsonNode> aggregationResponse = elasticsearchClient.search(request -> request
                .index(ARCHIVE_INDEX)
                .query(query)
                .size(0)
                .aggregations("platform_distribution", aggregation -> aggregation
                        .terms(terms -> terms.field("platform.keyword").size(20)))
                .aggregations("time_trend", aggregation -> aggregation
                        .dateHistogram(histogram -> histogram
                                .field("create_time")
                                .calendarInterval(CalendarInterval.Day)
                                .minDocCount(1)
                                .format("yyyy-MM-dd"))),
                JsonNode.class);

        SearchResponse<JsonNode> topHitsResponse = elasticsearchClient.search(request -> request
                .index(ARCHIVE_INDEX)
                .query(query)
                .size(topSize)
                .sort(sort -> sort.field(field -> field.field("risk_score").order(SortOrder.Desc)))
                .sort(sort -> sort.field(field -> field.field("create_time").order(SortOrder.Desc)))
                .sort(sort -> sort.field(field -> field.field(ARCHIVE_ID_SORT_FIELD).order(SortOrder.Desc))),
                JsonNode.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platformDistribution", extractPlatformDistribution(aggregationResponse.aggregations().get("platform_distribution")));
        result.put("timeTrend", extractTimeTrend(aggregationResponse.aggregations().get("time_trend")));
        result.put("topHits", topHitsResponse.hits().hits().stream()
                .map(Hit::source)
                .map(this::toChatLog)
                .collect(Collectors.toList()));
        return result;
    }

    private Map<String, Object> analyzeFromMysql(String keyword,
                                                 String playerId,
                                                 String decision,
                                                 String labels,
                                                 LocalDateTime startTime,
                                                 LocalDateTime endTime,
                                                 int topSize) {
        List<ChatMessageLog> sample = searchFromMysql(keyword, playerId, decision, labels, startTime, endTime, 1, 1000);

        Map<String, Long> platformDistribution = sample.stream()
                .collect(Collectors.groupingBy(
                        item -> item.getPlatform() == null ? "UNKNOWN" : item.getPlatform(),
                        LinkedHashMap::new,
                        Collectors.counting()));

        DateTimeFormatter dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        Map<String, Long> timeTrend = sample.stream()
                .filter(item -> item.getCreateTime() != null)
                .collect(Collectors.groupingBy(
                        item -> item.getCreateTime().format(dayFormatter),
                        LinkedHashMap::new,
                        Collectors.counting()));

        List<ChatMessageLog> topHits = sample.stream()
                .sorted(Comparator
                        .comparing((ChatMessageLog item) -> item.getRiskScore() == null ? 0 : item.getRiskScore()).reversed()
                        .thenComparing(ChatMessageLog::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(topSize)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platformDistribution", platformDistribution);
        result.put("timeTrend", toBucketList(timeTrend));
        result.put("topHits", topHits);
        return result;
    }

    private Map<String, Long> extractPlatformDistribution(Aggregate aggregate) {
        Map<String, Long> buckets = new LinkedHashMap<>();
        if (aggregate == null || !aggregate.isSterms()) {
            return buckets;
        }
        for (StringTermsBucket bucket : aggregate.sterms().buckets().array()) {
            buckets.put(bucket.key().stringValue(), bucket.docCount());
        }
        return buckets;
    }

    private List<Map<String, Object>> extractTimeTrend(Aggregate aggregate) {
        List<Map<String, Object>> buckets = new ArrayList<>();
        if (aggregate == null || !aggregate.isDateHistogram()) {
            return buckets;
        }
        for (DateHistogramBucket bucket : aggregate.dateHistogram().buckets().array()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("time", bucket.keyAsString());
            item.put("count", bucket.docCount());
            buckets.add(item);
        }
        return buckets;
    }

    private List<Map<String, Object>> toBucketList(Map<String, Long> buckets) {
        return buckets.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("time", entry.getKey());
                    item.put("count", entry.getValue());
                    return item;
                })
                .collect(Collectors.toList());
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
                .setStatus(toInteger(source.get("status")))
                .setDecision(toText(source.get("decision")))
                .setRiskScore(toInteger(source.get("risk_score"), source.get("riskScore")))
                .setLabels(toText(source.get("labels")))
                .setHitWords(toText(source.get("hit_words"), source.get("hitWords")))
                .setAiAnalysisResult(toText(source.get("ai_analysis_result"), source.get("aiAnalysisResult")))
                .setReasonTag(toText(source.get("reason_tag"), source.get("reasonTag")))
                .setCreateTime(toLocalDateTime(source.get("create_time"), source.get("createTime")));
    }

    private ArchiveSearchHit toArchiveSearchHit(Hit<JsonNode> hit) {
        ChatMessageLog message = toChatLog(hit.source());
        List<String> contentHighlights = hit.highlight() == null
                ? Collections.emptyList()
                : hit.highlight().getOrDefault("content", Collections.emptyList());

        return new ArchiveSearchHit()
                .setMessage(message)
                .setHighlights(hit.highlight() == null ? Collections.emptyMap() : hit.highlight())
                .setHighlightContent(contentHighlights.isEmpty() ? message.getContent() : contentHighlights.get(0));
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
            boolQuery.filter(query -> query.term(term -> term.field("player_id.keyword").value(playerId)));
        }

        if (hasText(decision)) {
            boolQuery.filter(query -> query.term(term -> term.field("decision.keyword").value(decision)));
        }

        if (hasText(labels)) {
            boolQuery.filter(query -> query.wildcard(wildcard -> wildcard
                    .field("labels.keyword")
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
