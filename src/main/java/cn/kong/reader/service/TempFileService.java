package cn.kong.reader.service;

import cn.kong.reader.config.TempFileProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 临时文件管理服务：负责下载文件的创建、查询、过期清理和下载 URL 拼接。
 */
@Service
public class TempFileService {

    private static final Logger log = LoggerFactory.getLogger(TempFileService.class);

    private final TempFileProperties properties;

    /** 文件元数据：fileId → FileMeta */
    private final ConcurrentHashMap<String, FileMeta> fileMap = new ConcurrentHashMap<>();

    public TempFileService(TempFileProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        try {
            Path dir = Paths.get(properties.getTempDir());
            Files.createDirectories(dir);
            log.info("下载临时目录初始化: {}", dir.toAbsolutePath());
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
     * @return 文件元数据，不存在或已过期则返回 null
     */
    public FileMeta getFile(String fileId) {
        FileMeta meta = fileMap.get(fileId);
        if (meta == null) {
            return null;
        }
        if (Instant.now().isAfter(meta.expireAt)) {
            removeFile(fileId);
            return null;
        }
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
     * 清理所有过期文件及目录中的残留文件。
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
        try {
            Path dir = Paths.get(properties.getTempDir());
            if (Files.isDirectory(dir)) {
                Files.list(dir).forEach(path -> {
                    String name = path.getFileName().toString();
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
     * <p>优先使用配置的 base-url，未配置时使用 localhost 兜底。
     *
     * @param fileId 文件 ID
     * @return 完整下载 URL
     */
    public String buildDownloadUrl(String fileId) {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "http://localhost:8081";
            log.warn("未配置 reader.download.base-url，使用兜底: {}", baseUrl);
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/api/reader/download/" + fileId;
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
