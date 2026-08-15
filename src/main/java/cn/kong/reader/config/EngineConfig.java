package cn.kong.reader.config;

import cn.kong.app.engine.ReaderService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 引擎配置：将 {@link ReaderService} 单例注册为 Spring Bean，支持依赖注入。
 */
@Configuration
public class EngineConfig {

    @Bean
    public ReaderService readerService() {
        return ReaderService.getInstance();
    }
}
