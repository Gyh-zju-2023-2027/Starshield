package com.starshield.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshield.backend.entity.CrawlTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CrawlTaskMapper extends BaseMapper<CrawlTask> {
    // ✅ 什么都不用加，用 MyBatis-Plus 自带方法
}