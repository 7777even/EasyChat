package com.easychat.service;

import com.easychat.entity.dto.WebRTCSignalDto;

/**
 * WebRTC 服务接口
 */
public interface WebRTCService {
    
    /**
     * 发送 WebRTC 信令
     */
    void sendSignal(WebRTCSignalDto signalDto);
}
