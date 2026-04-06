package com.starshield.backend.archive;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * ES 归档仓储。
 */
public interface ChatMessageIndexRepository extends ElasticsearchRepository<ChatMessageIndex, String> {
}
