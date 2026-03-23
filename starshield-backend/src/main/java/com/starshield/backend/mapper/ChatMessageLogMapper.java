package com.starshield.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshield.backend.entity.ChatMessageLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 玩家发言记录 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus BaseMapper，开箱即得 CRUD 方法。
 * 复杂查询可在此接口中添加自定义方法，并在 resources/mapper/ 下编写 XML。
 */
@Mapper
public interface ChatMessageLogMapper extends BaseMapper<ChatMessageLog> {

}
