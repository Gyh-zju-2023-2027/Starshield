package com.starshield.backend.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.starshield.backend.entity.ChatMessageLog;
import com.starshield.backend.mapper.ChatMessageLogMapper;
import com.starshield.backend.service.ChatMessageService;
import org.springframework.stereotype.Service;

/**
 * 玩家发言记录 Service 实现类
 * <p>
 * 继承 MyBatis-Plus ServiceImpl，获得批量插入等高效操作能力。
 */
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageLogMapper, ChatMessageLog>
        implements ChatMessageService {

}
