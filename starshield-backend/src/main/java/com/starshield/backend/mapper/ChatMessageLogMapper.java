package com.starshield.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshield.backend.entity.ChatMessageLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 玩家发言记录 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus BaseMapper，开箱即得 CRUD 方法。
 * 复杂查询可在此接口中添加自定义方法，并在 resources/mapper/ 下编写 XML。
 */
@Mapper
public interface ChatMessageLogMapper extends BaseMapper<ChatMessageLog> {

    @Select("SELECT HOUR(create_time) as hr, COUNT(*) as cnt " +
            "FROM chat_message_log " +
            "WHERE create_time >= #{start} AND create_time < #{end} " +
            "GROUP BY HOUR(create_time)")
    List<Map<String, Object>> countHourlyBuckets(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT player_id as playerId, COUNT(*) as violationCount " +
            "FROM chat_message_log " +
            "WHERE create_time >= #{start} AND create_time < #{end} AND decision = 'BLOCK' " +
            "GROUP BY player_id ORDER BY violationCount DESC LIMIT 5")
    List<Map<String, Object>> countTopPlayers(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT hit_words FROM chat_message_log " +
            "WHERE create_time >= #{start} AND create_time < #{end} AND hit_words IS NOT NULL AND hit_words != ''")
    List<String> selectHitWords(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
