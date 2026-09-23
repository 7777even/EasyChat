package com.easychat;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.spring.ApplicationContextProvider;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.servlet.MultipartConfigElement;
import java.io.File;


@EnableAsync
@SpringBootApplication(scanBasePackages = {"com.easychat"})
@MapperScan(basePackages = {"com.easychat.mappers"})
@EnableTransactionManagement
@EnableScheduling
public class EasyChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(EasyChatApplication.class, args);
    }

    @Bean
    @DependsOn({"applicationContextProvider"})
    MultipartConfigElement multipartConfigElement() {
        AppConfig appConfig = (AppConfig) ApplicationContextProvider.getBean("appConfig");
        MultipartConfigFactory factory = new MultipartConfigFactory();
        // Tomcat multipart location 目录必须存在，否则上传时 part.write 会抛 FileNotFoundException
        String tempLocation = appConfig.getProjectFolder() + Constants.FILE_FOLDER_TEMP;
        File tempFolder = new File(tempLocation);
        if (!tempFolder.exists()) {
            tempFolder.mkdirs();
        }
        factory.setLocation(tempLocation);
        return factory.createMultipartConfig();
    }
}
