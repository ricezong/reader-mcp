package cn.kong.reader.task;

import cn.kong.reader.service.DownloadFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理过期下载文件任务。
 *
 * <p>每小时执行一次，清理超过过期时间的临时文件。
 * 文件默认 24 小时过期（由 {@code reader.download.expire-hours} 配置）。
 */
@Component
public class FileCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(FileCleanupTask.class);

    private final DownloadFileManager fileManager;

    public FileCleanupTask(DownloadFileManager fileManager) {
        this.fileManager = fileManager;
    }

    /**
     * 每小时清理一次过期文件。
     */
    @Scheduled(fixedDelay = 3600_000L) // 1 小时 = 3600000 毫秒
    public void cleanExpiredFiles() {
        try {
            fileManager.cleanExpiredFiles();
        } catch (Exception e) {
            log.error("定时清理过期文件任务异常", e);
        }
    }
}
