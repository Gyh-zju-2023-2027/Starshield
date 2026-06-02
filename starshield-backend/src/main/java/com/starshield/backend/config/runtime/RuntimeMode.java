package com.starshield.backend.config.runtime;

/**
 * 运行时部署模式。
 * <ul>
 *   <li>MONOLITH — 单体（本地开发默认）</li>
 *   <li>INGEST — 仅高并发接入 + MQ 投递</li>
 *   <li>WORKER — 仅 MQ 消费 + 审核落库</li>
 *   <li>API — 管理/检索/大屏 REST + WebSocket</li>
 * </ul>
 */
public enum RuntimeMode {
    MONOLITH,
    INGEST,
    WORKER,
    API
}
