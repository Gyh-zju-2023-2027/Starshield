package com.starshield.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshield.backend.archive.ChatMessageIndex;
import com.starshield.backend.archive.ChatMessageIndexRepository;
import com.starshield.backend.entity.ChatMessageLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 检索服务（ES 优先，MySQL 兜底）。
 */
@Service
public class ArchiveSearchService {

    private final ChatMessageService chatMessageService;
    private final ChatMessageIndexRepository chatMessageIndexRepository;

    @Value("${starshield.archive.es-enabled:false}")
    private boolean esEnabled;

    public ArchiveSearchService(ChatMessageService chatMessageService,
                                ChatMessageIndexRepository chatMessageIndexRepository) {
        this.chatMessageService = chatMessageService;
        this.chatMessageIndexRepository = chatMessageIndexRepository;
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

        if (esEnabled) {
            return searchFromEs(keyword, playerId, decision, labels, startTime, endTime, pageNo, pageSize);
        }
        return searchFromMysql(keyword, playerId, decision, labels, startTime, endTime, pageNo, pageSize);
    }

    private List<ChatMessageLog> searchFromEs(String keyword,
                                              String playerId,
                                              String decision,
                                              String labels,
                                              LocalDateTime startTime,
                                              LocalDateTime endTime,
                                              int page,
                                              int size) {
        int fetchSize = Math.min(5000, size * 5);
        List<ChatMessageIndex> indices = chatMessageIndexRepository
                .findAll(PageRequest.of(Math.max(page - 1, 0), fetchSize, Sort.by(Sort.Direction.DESC, "createTime")))
                .getContent();

        return indices.stream()
                .filter(x -> containsIgnoreCase(x.getContent(), keyword))
                .filter(x -> equalsIfPresent(x.getPlayerId(), playerId))
                .filter(x -> equalsIfPresent(x.getDecision(), decision))
                .filter(x -> containsIgnoreCase(x.getLabels(), labels))
                .filter(x -> geIfPresent(x.getCreateTime(), startTime))
                .filter(x -> leIfPresent(x.getCreateTime(), endTime))
                .limit(size)
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

    private ChatMessageLog toChatLog(ChatMessageIndex index) {
        Long id = null;
        try {
            id = index.getId() == null ? null : Long.valueOf(index.getId());
        } catch (Exception ignored) {
        }
        return new ChatMessageLog()
                .setId(id)
                .setPlayerId(index.getPlayerId())
                .setContent(index.getContent())
                .setPlatform(index.getPlatform())
                .setDecision(index.getDecision())
                .setRiskScore(index.getRiskScore())
                .setLabels(index.getLabels())
                .setCreateTime(index.getCreateTime());
    }

    private boolean containsIgnoreCase(String source, String target) {
        if (target == null || target.isBlank()) {
            return true;
        }
        if (source == null) {
            return false;
        }
        return source.toLowerCase().contains(target.toLowerCase());
    }

    private boolean equalsIfPresent(String source, String target) {
        if (target == null || target.isBlank()) {
            return true;
        }
        return target.equals(source);
    }

    private boolean geIfPresent(LocalDateTime source, LocalDateTime target) {
        if (target == null) {
            return true;
        }
        if (source == null) {
            return false;
        }
        return !source.isBefore(target);
    }

    private boolean leIfPresent(LocalDateTime source, LocalDateTime target) {
        if (target == null) {
            return true;
        }
        if (source == null) {
            return false;
        }
        return !source.isAfter(target);
    }
}
