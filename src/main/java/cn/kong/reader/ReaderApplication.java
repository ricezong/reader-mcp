package cn.kong.reader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot 启动类：启动 reader-mcp 服务（默认端口 8081）。
 */
@SpringBootApplication
@EnableScheduling
public class ReaderApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReaderApplication.class, args);
    }
}
