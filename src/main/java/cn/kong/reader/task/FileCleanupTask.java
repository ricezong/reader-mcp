package cn.kong.reader.task;

import cn.kong.reader.service.TempFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理过期下载文件任务（每小时执行一次）。
 */
@Component
public class FileCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(FileCleanupTask.class);

    private final TempFileService fileService;

    public FileCleanupTask(TempFileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 每小时清理一次过期文件。
     */
    @Scheduled(fixedDelay = 3600_000L) // 1 小时 = 3600000 毫秒
    public void cleanExpiredFiles() {
        try {
            fileService.cleanExpiredFiles();
        } catch (Exception e) {
            log.error("定时清理过期文件任务异常", e);
        }
    }
}
