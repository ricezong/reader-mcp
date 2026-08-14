package cn.kong.reader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 小说/漫画下载器 MCP 服务 Spring Boot 启动类。
 *
 * <p>启动后提供 MCP（Model Context Protocol）服务，默认端口 8081（参见 application.yml）。
 * AI 客户端可通过 MCP 协议调用小说/漫画搜索、详情、目录、正文和批量下载等工具。
 * 底层引擎 reader-engine 内置 4 个小说源 + 4 个漫画源，开箱即用。
 */
@SpringBootApplication
@EnableScheduling
public class ReaderApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReaderApplication.class, args);
    }
}
