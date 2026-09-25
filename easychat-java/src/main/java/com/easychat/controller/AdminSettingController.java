package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.SysSettingDto;
import com.easychat.entity.vo.Result;
import com.easychat.redis.RedisComponet;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;

@RestController("adminSettingController")
@RequestMapping("/admin")
public class AdminSettingController extends ABaseController {
    @Resource
    private AppConfig appConfig;

    @Resource
    private RedisComponet redisComponet;

    @PostMapping("/saveSysSetting")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Void> saveSysSetting(SysSettingDto sysSettingDto,
                                     MultipartFile robotFile,
                                     MultipartFile robotCover) throws IOException {
        if (robotFile != null) {
            String baseFolder = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE;
            File targetFileFolder = new File(baseFolder + Constants.FILE_FOLDER_AVATAR_NAME);
            if (!targetFileFolder.exists()) {
                targetFileFolder.mkdirs();
            }
            String filePath = targetFileFolder.getPath() + "/" + Constants.ROBOT_UID + Constants.IMAGE_SUFFIX;
            robotFile.transferTo(new File(filePath));
            robotCover.transferTo(new File(filePath + Constants.COVER_IMAGE_SUFFIX));
        }
        redisComponet.saveSysSetting(sysSettingDto);
        return success(null);
    }

    @PostMapping("/getSysSetting")
    @GlobalInterceptor(checkAdmin = true)
    public Result<SysSettingDto> getSysSetting() {
        SysSettingDto sysSettingDto = redisComponet.getSysSetting();
        return success(sysSettingDto);
    }
}
