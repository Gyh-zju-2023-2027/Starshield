package com.starshield.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshield.backend.entity.ModerationAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审核审计 Mapper。
 */
@Mapper
public interface ModerationAuditLogMapper extends BaseMapper<ModerationAuditLog> {
}
