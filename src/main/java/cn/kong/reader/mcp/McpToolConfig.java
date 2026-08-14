package cn.kong.reader.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册配置。
 *
 * <p>将 {@link ReaderMcpTools} 包装为 {@link ToolCallbackProvider} bean，
 * Spring AI MCP Server 自动配置会收集容器中所有 ToolCallbackProvider，
 * 将其中的 {@code @Tool} 方法注册为 MCP 工具（tools/list 可发现、tools/call 可调用）。
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
