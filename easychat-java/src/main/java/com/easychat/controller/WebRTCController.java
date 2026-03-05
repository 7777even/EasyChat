package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.dto.WebRTCSignalDto;
import com.easychat.entity.vo.ResponseVO;
import com.easychat.service.WebRTCService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * WebRTC 通话控制器
 */
@RestController
@RequestMapping("/webrtc")
public class WebRTCController extends ABaseController {

    @Resource
    private WebRTCService webRTCService;

    /**
     * 发送 WebRTC 信令
     */
    @RequestMapping("/signal")
    @GlobalInterceptor
    public ResponseVO<String> sendSignal(HttpServletRequest request, @RequestBody WebRTCSignalDto signalDto) {
        TokenUserInfoDto userInfo = getTokenUserInfo(request);
        signalDto.setFromUserId(userInfo.getUserId());
        signalDto.setFromUserNickName(userInfo.getNickName());
        webRTCService.sendSignal(signalDto);
        return getSuccessResponseVO(null);
    }
}
