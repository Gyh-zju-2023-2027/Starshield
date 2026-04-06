package com.starshield.backend.archive;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;

/**
 * ES 聊天归档索引文档。
 */
@Data
@Accessors(chain = true)
@Document(indexName = "chat_message_archive")
public class ChatMessageIndex {

    @Id
    private String id;

    @Field(type = FieldType.Keyword, name = "player_id")
    private String playerId;

    @Field(type = FieldType.Text, name = "content")
    private String content;

    @Field(type = FieldType.Keyword, name = "platform")
    private String platform;

    @Field(type = FieldType.Keyword, name = "decision")
    private String decision;

    @Field(type = FieldType.Integer, name = "risk_score")
    private Integer riskScore;

    @Field(type = FieldType.Keyword, name = "labels")
    private String labels;

    @Field(type = FieldType.Date, name = "create_time", format = DateFormat.date_hour_minute_second_millis)
    private LocalDateTime createTime;
}
