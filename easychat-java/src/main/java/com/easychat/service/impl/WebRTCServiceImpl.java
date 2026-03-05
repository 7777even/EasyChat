package com.easychat.service.impl;

import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.WebRTCSignalDto;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.service.WebRTCService;
import com.easychat.websocket.ChannelContextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * WebRTC 服务实现
 */
@Service
public class WebRTCServiceImpl implements WebRTCService {

    private static final Logger logger = LoggerFactory.getLogger(WebRTCServiceImpl.class);

    @Resource
    private ChannelContextUtils channelContextUtils;

    @Override
    public void sendSignal(WebRTCSignalDto signalDto) {
        logger.info("发送 WebRTC 信令: type={}, from={}, to={}, callId={}", 
            signalDto.getSignalType(), signalDto.getFromUserId(), 
            signalDto.getToId(), signalDto.getCallId());

        // 如果是群组通话，发送给所有成员
        if (Boolean.TRUE.equals(signalDto.getIsGroup()) && signalDto.getMembers() != null) {
            for (String memberId : signalDto.getMembers()) {
                // 不发送给自己
                if (!memberId.equals(signalDto.getFromUserId())) {
                    sendSignalToUser(signalDto, memberId);
                }
            }
        } else {
            // 一对一通话，发送给目标用户
            sendSignalToUser(signalDto, signalDto.getToId());
        }
    }

    /**
     * 发送信令给指定用户
     */
    private void sendSignalToUser(WebRTCSignalDto signalDto, String toUserId) {
        try {
            MessageSendDto<WebRTCSignalDto> messageSendDto = new MessageSendDto<>();
            messageSendDto.setMessageType(MessageTypeEnum.WEBRTC_SIGNAL.getType());
            messageSendDto.setContactId(toUserId);  // 直接使用 toUserId，应该已经包含前缀
            messageSendDto.setSendUserId(signalDto.getFromUserId());
            messageSendDto.setSendUserNickName(signalDto.getFromUserNickName());
            messageSendDto.setExtendData(signalDto);
            
            logger.info("准备发送 WebRTC 信令到用户: {}, 信令类型: {}", toUserId, signalDto.getSignalType());
            
            // 通过 ChannelContextUtils 发送消息
            channelContextUtils.sendMessage(messageSendDto);
            
            logger.info("WebRTC 信令发送成功");
        } catch (Exception e) {
            logger.error("发送 WebRTC 信令失败: toUserId={}, signalType={}", toUserId, signalDto.getSignalType(), e);
            throw e;
        }
    }
}
