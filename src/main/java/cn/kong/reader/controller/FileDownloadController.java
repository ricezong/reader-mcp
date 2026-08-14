package cn.kong.reader.controller;

import cn.kong.reader.service.DownloadFileManager;
import cn.kong.reader.service.DownloadFileManager.FileMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Paths;

/**
 * 文件下载控制器：提供 HTTP 端点下载 MCP 工具生成的临时文件。
 *
 * <p>下载链接格式：{@code GET /downloads/{fileId}}
 *
 * <p>文件有过期时间（默认 24 小时），过期后返回 404。
 */
@RestController
@RequestMapping("/downloads")
public class FileDownloadController {

    private static final Logger log = LoggerFactory.getLogger(FileDownloadController.class);

    private final DownloadFileManager fileManager;

    public FileDownloadController(DownloadFileManager fileManager) {
        this.fileManager = fileManager;
    }

    /**
     * 下载文件。
     *
     * @param fileId 文件 ID
     * @return 文件资源（以附件形式下载）
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<?> downloadFile(@PathVariable String fileId) {
        FileMeta meta = fileManager.getFile(fileId);
        if (meta == null) {
            log.warn("文件不存在或已过期: fileId={}", fileId);
            return ResponseEntity.notFound().build();
        }

        var file = Paths.get(meta.getFilePath()).toFile();
        if (!file.exists()) {
            log.warn("文件不存在于磁盘: fileId={}, path={}", fileId, meta.getFilePath());
            return ResponseEntity.notFound().build();
        }

        FileSystemResource resource = new FileSystemResource(file);

        // 对中文文件名进行 URL 编码（RFC 5987）
        String encodedFileName;
        try {
            encodedFileName = java.net.URLEncoder.encode(meta.getFileName(), "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            encodedFileName = fileId + ".txt";
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileId + ".txt\"; filename*=UTF-8''" + encodedFileName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.length())
                .body(resource);
    }
}
