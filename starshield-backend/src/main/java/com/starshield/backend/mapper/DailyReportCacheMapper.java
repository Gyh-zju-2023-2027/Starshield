package com.starshield.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshield.backend.entity.DailyReportCache;
import org.apache.ibatis.annotations.Mapper;

/**
 * 每日战报缓存查询 Mapper
 */
@Mapper
public interface DailyReportCacheMapper extends BaseMapper<DailyReportCache> {
}
