package com.starshield.backend.service;

import com.starshield.backend.archive.ChatMessageIndex;
import com.starshield.backend.archive.ChatMessageIndexRepository;
import com.starshield.backend.entity.ChatMessageLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 检索归档服务（双写至 ES）。
 */
@Service
public class ArchiveSyncService {

    private final ChatMessageIndexRepository indexRepository;

    @Value("${starshield.archive.es-enabled:false}")
    private boolean esEnabled;

    public ArchiveSyncService(@Autowired(required = false) ChatMessageIndexRepository indexRepository) {
        this.indexRepository = indexRepository;
    }

    /**
     * 同步一条记录到 ES。
     *
     * @author AI (under P6 supervision)
     */
    public void syncToEs(ChatMessageLog log) {
        if (!esEnabled || indexRepository == null || log == null || log.getId() == null) {
            return;
        }

        ChatMessageIndex index = new ChatMessageIndex()
                .setId(String.valueOf(log.getId()))
                .setPlayerId(log.getPlayerId())
                .setContent(log.getContent())
                .setPlatform(log.getPlatform())
                .setDecision(log.getDecision())
                .setRiskScore(log.getRiskScore())
                .setLabels(log.getLabels())
                .setCreateTime(log.getCreateTime());

        indexRepository.save(index);
    }
}
