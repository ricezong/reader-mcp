package cn.kong.reader.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理：统一处理所有 Controller 抛出的异常，返回正确的 HTTP 状态码。
 * <p>不影响 MCP 层异常（MCP 工具内部已有 try-catch）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 参数校验异常 — 返回 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("error", e.getMessage());
        return ResponseEntity.badRequest().body(result);
    }

    /**
     * 未找到异常 — 返回 404
     */
    @ExceptionHandler({IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("状态异常: {}", e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
    }

    /**
     * 索引越界异常 — 返回 400
     */
    @ExceptionHandler(IndexOutOfBoundsException.class)
    public ResponseEntity<Map<String, Object>> handleIndexOutOfBounds(IndexOutOfBoundsException e) {
        log.warn("索引越界: {}", e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("error", e.getMessage());
        return ResponseEntity.badRequest().body(result);
    }

    /**
     * 其他未捕获异常 — 返回 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("接口调用失败", e);
        Map<String, Object> result = new HashMap<>();
        result.put("error", "服务内部错误: " + e.getMessage());
        return ResponseEntity.internalServerError().body(result);
    }
}
