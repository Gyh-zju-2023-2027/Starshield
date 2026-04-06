package com.starshield.backend.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.starshield.backend.entity.ModerationAuditLog;
import com.starshield.backend.mapper.ModerationAuditLogMapper;
import com.starshield.backend.service.ModerationAuditLogService;
import org.springframework.stereotype.Service;

/**
 * 审核审计服务实现。
 */
@Service
public class ModerationAuditLogServiceImpl extends ServiceImpl<ModerationAuditLogMapper, ModerationAuditLog>
        implements ModerationAuditLogService {
}
