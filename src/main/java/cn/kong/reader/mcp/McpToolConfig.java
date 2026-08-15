package cn.kong.reader.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册配置：将 {@link ReaderMcpTools} 注册为 MCP 工具提供者。
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider readerToolCallbackProvider(ReaderMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
