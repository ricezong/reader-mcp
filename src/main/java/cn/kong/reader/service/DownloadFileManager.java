package cn.kong.reader.service;

import cn.kong.reader.config.DownloadProperties;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下载文件管理器：负责临时文件的创建、查询和过期清理。
 *
 * <p>文件存储在 {@link DownloadProperties#getTempDir()} 指定的目录下，
 * 每个文件分配一个唯一 fileId，通过 {@code /downloads/{fileId}} 端点提供下载。
 * 文件创建时记录时间戳，超过 {@link DownloadProperties#getExpireHours()} 小时后自动清理。
 */
@Service
public class DownloadFileManager {

    private static final Logger log = LoggerFactory.getLogger(DownloadFileManager.class);

    private final DownloadProperties properties;

    /** 文件元数据：fileId → FileMeta */
    private final ConcurrentHashMap<String, FileMeta> fileMap = new ConcurrentHashMap<>();

    public DownloadFileManager(DownloadProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        try {
            Path dir = Paths.get(properties.getTempDir());
            Files.createDirectories(dir);
            log.info("下载临时目录初始化: {}", dir.toAbsolutePath());

            // 启动时清理上次残留的文件
            cleanExpiredFiles();
        } catch (IOException e) {
            log.error("创建临时目录失败: {}", properties.getTempDir(), e);
            throw new RuntimeException("创建下载临时目录失败", e);
        }
    }

    /**
     * 创建临时文件并返回文件元数据。
     *
     * @param fileName 文件名（含扩展名）
     * @return 文件元数据
     * @throws IOException 文件创建失败
     */
    public FileMeta createFile(String fileName) throws IOException {
        String fileId = UUID.randomUUID().toString().replace("-", "");

        // 构造安全文件名：fileId_原文件名
        String safeFileName = fileId + "_" + sanitizeFileName(fileName);
        Path filePath = Paths.get(properties.getTempDir(), safeFileName);

        FileMeta meta = new FileMeta();
        meta.fileId = fileId;
        meta.fileName = fileName;
        meta.filePath = filePath.toString();
        meta.createdAt = Instant.now();
        meta.expireAt = meta.createdAt.plusSeconds(properties.getExpireHours() * 3600L);

        fileMap.put(fileId, meta);
        log.info("创建下载文件: fileId={}, fileName={}", fileId, fileName);
        return meta;
    }

    /**
     * 获取文件元数据。
     *
     * @param fileId 文件 ID
     * @return 文件元数据，不存在则返回 null
     */
    public FileMeta getFile(String fileId) {
        FileMeta meta = fileMap.get(fileId);
        if (meta == null) {
            return null;
        }
        // 检查文件是否过期
        if (Instant.now().isAfter(meta.expireAt)) {
            removeFile(fileId);
            return null;
        }
        // 检查文件是否还存在
        if (!Files.exists(Paths.get(meta.filePath))) {
            fileMap.remove(fileId);
            return null;
        }
        return meta;
    }

    /**
     * 删除文件及其元数据。
     *
     * @param fileId 文件 ID
     */
    public void removeFile(String fileId) {
        FileMeta meta = fileMap.remove(fileId);
        if (meta != null) {
            try {
                Files.deleteIfExists(Paths.get(meta.filePath));
                log.info("已删除下载文件: fileId={}, fileName={}", fileId, meta.fileName);
            } catch (IOException e) {
                log.warn("删除文件失败: fileId={}, path={}", fileId, meta.filePath, e);
            }
        }
    }

    /**
     * 清理所有过期文件。
     */
    public void cleanExpiredFiles() {
        Instant now = Instant.now();
        int count = 0;
        for (var entry : fileMap.entrySet()) {
            if (now.isAfter(entry.getValue().expireAt)) {
                removeFile(entry.getKey());
                count++;
            }
        }
        // 同时清理目录中不在 fileMap 中的残留文件
        try {
            Path dir = Paths.get(properties.getTempDir());
            if (Files.isDirectory(dir)) {
                Files.list(dir).forEach(path -> {
                    String name = path.getFileName().toString();
                    // 检查是否在 fileMap 中（通过文件名中的 fileId 前缀）
                    boolean found = fileMap.values().stream()
                            .anyMatch(meta -> Paths.get(meta.filePath).equals(path));
                    if (!found) {
                        try {
                            Files.deleteIfExists(path);
                            log.info("清理残留文件: {}", name);
                        } catch (IOException e) {
                            log.warn("清理残留文件失败: {}", name, e);
                        }
                    }
                });
            }
        } catch (IOException e) {
            log.warn("清理残留文件失败", e);
        }
        if (count > 0) {
            log.info("清理过期下载文件: {} 个", count);
        }
    }

    /**
     * 构造文件下载 URL。
     *
     * <p>优先使用配置的 {@code reader.download.base-url}；
     * 未配置时从当前 HTTP 请求上下文推断服务基础 URL。
     *
     * @param fileId 文件 ID
     * @return 完整下载 URL
     */
    public String buildDownloadUrl(String fileId) {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            // 从当前请求上下文推断
            HttpServletRequest request = getCurrentRequest();
            if (request != null) {
                String scheme = request.getScheme();
                String serverName = request.getServerName();
                int port = request.getServerPort();
                String contextPath = request.getContextPath();

                if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                    baseUrl = scheme + "://" + serverName;
                } else {
                    baseUrl = scheme + "://" + serverName + ":" + port;
                }
                baseUrl = baseUrl + contextPath;
            } else {
                // 无请求上下文时使用 localhost 兜底
                baseUrl = "http://localhost:8081";
                log.warn("无法获取请求上下文，使用兜底 base-url: {}", baseUrl);
            }
        }
        // 去掉末尾斜杠
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/downloads/" + fileId;
    }

    /**
     * 从 Spring 请求上下文获取当前 HTTP 请求。
     *
     * @return 当前 HttpServletRequest，无上下文时返回 null
     */
    private HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }

    /** 文件名净化：只保留字母、数字、中文、下划线、点、横线 */
    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "download.txt";
        }
        return fileName.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9._\\-]", "_");
    }

    /** 文件元数据 */
    public static class FileMeta {
        private String fileId;
        private String fileName;
        private String filePath;
        private Instant createdAt;
        private Instant expireAt;

        public String getFileId() { return fileId; }
        public String getFileName() { return fileName; }
        public String getFilePath() { return filePath; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getExpireAt() { return expireAt; }
    }
}
