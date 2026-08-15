package cn.kong.reader.service;

import cn.kong.app.engine.ReaderService;
import cn.kong.app.engine.dto.BookDetail;
import cn.kong.app.engine.dto.ChapterContent;
import cn.kong.app.engine.dto.ChapterInfo;
import cn.kong.app.engine.dto.SearchResult;
import cn.kong.app.engine.dto.SourceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 小说/漫画业务逻辑层：封装搜索、详情、目录、正文、下载等能力。
 * <p>直接透传 {@link ReaderService} 返回的 DTO 对象，不做 Map 转换。
 */
@Service
public class ReaderApi {

    private static final Logger log = LoggerFactory.getLogger(ReaderApi.class);

    /** 搜索关键词最大长度 */
    private static final int MAX_KEYWORD_LENGTH = 100;

    private final ReaderService service = ReaderService.getInstance();

    private final TempFileService fileService;

    @Value("${reader.max-search-results:50}")
    private int maxSearchResults;

    @Value("${reader.max-download-chapters:200}")
    private int maxDownloadChapters;

    public ReaderApi(TempFileService fileService) {
        this.fileService = fileService;
    }

    // ---------- 源管理 ----------

    /**
     * 列出所有书源（小说 + 漫画）。
     *
     * @return 书源信息列表
     */
    public List<SourceInfo> listSources() {
        return service.listAllSources();
    }

    /**
     * 列出小说源。
     *
     * @return 小说源信息列表
     */
    public List<SourceInfo> listNovelSources() {
        return service.listNovelSources();
    }

    /**
     * 列出漫画源。
     *
     * @return 漫画源信息列表
     */
    public List<SourceInfo> listComicSources() {
        return service.listComicSources();
    }

    // ---------- 搜索 ----------

    /**
     * 按关键词搜索小说（聚合所有小说源）。
     *
     * @param keyword 搜索关键词
     * @return 搜索结果列表，每条结果包含 source 字段供后续操作使用
     */
    public List<SearchResult> searchNovel(String keyword) {
        validateKeyword(keyword);
        return limitResults(service.searchNovel(keyword));
    }

    /**
     * 按关键词搜索小说（聚合所有小说源，支持分页）。
     *
     * @param keyword 搜索关键词
     * @param page    页码（从 1 开始）
     * @return 搜索结果列表
     */
    public List<SearchResult> searchNovel(String keyword, int page) {
        validateKeyword(keyword);
        validatePage(page);
        return limitResults(service.searchNovel(keyword, page));
    }

    /**
     * 按关键词搜索漫画（聚合所有漫画源）。
     *
     * @param keyword 搜索关键词
     * @return 搜索结果列表，每条结果包含 source 字段供后续操作使用
     */
    public List<SearchResult> searchComic(String keyword) {
        validateKeyword(keyword);
        return limitResults(service.searchComic(keyword));
    }

    /**
     * 按关键词搜索漫画（聚合所有漫画源，支持分页）。
     *
     * @param keyword 搜索关键词
     * @param page    页码（从 1 开始）
     * @return 搜索结果列表
     */
    public List<SearchResult> searchComic(String keyword, int page) {
        validateKeyword(keyword);
        validatePage(page);
        return limitResults(service.searchComic(keyword, page));
    }

    /**
     * 按关键词在指定书源中搜索（支持分页）。
     * <p>引擎的 {@code search(keyword, source, page)} 方法会根据 source 简称定位到具体书源并搜索。
     * source 为 null 或空字符串时等同于聚合搜索。
     *
     * @param keyword 搜索关键词
     * @param source  书源简称（如 80、dubu、godamanga），为空则聚合搜索
     * @param page    页码（从 1 开始）
     * @return 搜索结果列表，每条结果包含 source 字段供后续操作使用
     */
    public List<SearchResult> searchBySource(String keyword, String source, int page) {
        validateKeyword(keyword);
        validatePage(page);
        if (source == null || source.isBlank()) {
            // 未指定书源，走聚合搜索
            return limitResults(service.search(keyword, page));
        }
        return limitResults(service.search(keyword, source, page));
    }

    /**
     * 按作者搜索小说（聚合所有小说源，引擎自动过滤匹配作者的结果）。
     *
     * @param author 作者名
     * @return 匹配该作者的作品列表，每条结果包含 source 字段供后续操作使用
     */
    public List<SearchResult> searchNovelByAuthor(String author) {
        validateKeyword(author, "作者名");
        return limitResults(service.searchNovelByAuthor(author));
    }

    // ---------- 详情 ----------

    /**
     * 获取书籍详情。
     *
     * @param source  书源简称
     * @param bookUrl 书籍 URL
     * @return 书籍详情
     */
    public BookDetail getBookInfo(String source, String bookUrl) {
        return service.getBookDetail(bookUrl, source);
    }

    // ---------- 目录 ----------

    /**
     * 获取章节列表。
     *
     * @param source  书源简称
     * @param bookUrl 书籍 URL
     * @return 章节列表
     */
    public List<ChapterInfo> getChapterList(String source, String bookUrl) {
        return service.getChapterList(bookUrl, source);
    }

    // ---------- 单章正文 ----------

    /**
     * 获取单章正文。
     *
     * @param source       书源简称
     * @param bookUrl      书籍 URL
     * @param chapterIndex 章节序号（从 1 开始）
     * @return 章节正文内容（漫画为图片 HTML）
     */
    public String getBookContent(String source, String bookUrl, int chapterIndex) {
        if (chapterIndex < 1) {
            throw new IllegalArgumentException("章节序号不能小于 1");
        }
        // 引擎的 index 从 0 开始，对外从 1 开始，需要减 1
        return service.getContent(bookUrl, source, chapterIndex - 1);
    }

    // ---------- 批量下载 ----------

    /**
     * 批量下载章节正文，将内容写入临时 TXT 文件并返回文件信息。
     *
     * <p>使用 ReaderService 的批量下载 API，引擎内部已处理并发和 Book 缓存。
     * 下载完成后将拼接后的全文写入临时文件，文件 24 小时后自动过期。
     *
     * @param source  书源简称
     * @param bookUrl 书籍 URL
     * @param start   起始章节序号（从 1 开始）
     * @param end     结束章节序号
     * @return 包含文件 ID、文件名、下载统计信息的结果（不含正文内容）
     */
    public DownloadResult downloadChapters(String source, String bookUrl, int start, int end) {
        if (start < 1) throw new IllegalArgumentException("起始章节不能小于 1");
        if (start > end) throw new IllegalArgumentException("起始章节不能大于结束章节");

        // 获取详情和目录
        BookDetail detail = service.getBookDetail(bookUrl, source);
        List<ChapterInfo> chapters = service.getChapterList(bookUrl, source);
        if (chapters == null || chapters.isEmpty()) {
            throw new RuntimeException("章节目录为空");
        }

        int to = Math.min(chapters.size(), end);

        // 限制单次下载章节数
        if (to - start + 1 > maxDownloadChapters) {
            throw new IllegalArgumentException(
                    "单次下载章节数超出上限 " + maxDownloadChapters + "（请求 " + (to - start + 1) + " 章）");
        }

        String bookName = detail.getName() != null ? detail.getName() : "unknown";
        String authorName = detail.getAuthor() != null ? detail.getAuthor() : "未知";
        String sourceName = detail.getSourceName() != null ? detail.getSourceName() : source;

        // 使用引擎的批量下载 API（index 从 0 开始）
        List<ChapterContent> contents = service.batchDownload(bookUrl, source, start - 1, to);

        // 统计和拼接
        int successCount = 0;
        int failCount = 0;
        long totalLength = 0;

        StringBuilder sb = new StringBuilder();
        sb.append("书名：").append(bookName).append("\n");
        sb.append("作者：").append(authorName).append("\n");
        sb.append("来源：").append(sourceName).append(" (").append(source).append(")\n");
        sb.append("章节范围：第 ").append(start).append(" 章 ~ 第 ").append(to)
          .append(" 章（共 ").append(contents.size()).append(" 章）\n");
        sb.append("\n========================================\n\n");

        for (ChapterContent ch : contents) {
            String title = ch.getTitle() != null ? ch.getTitle() : "";
            String content = ch.getContent();

            sb.append(title).append("\n\n");

            if (content == null || content.isEmpty()) {
                sb.append("[本章内容为空]\n");
                failCount++;
            } else {
                sb.append(content).append("\n");
                successCount++;
                totalLength += content.length();
            }
            sb.append("\n\n");
        }

        // 写入临时文件
        String txtFileName = bookName + "_" + start + "-" + to + ".txt";
        TempFileService.FileMeta fileMeta;
        try {
            fileMeta = fileService.createFile(txtFileName);
            Files.write(Paths.get(fileMeta.getFilePath()),
                    sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("写入下载文件失败: fileName={}", txtFileName, e);
            throw new RuntimeException("写入下载文件失败: " + e.getMessage());
        }

        long fileSize = sb.length();

        DownloadResult result = new DownloadResult(
                fileMeta.getFileId(),
                txtFileName,
                fileSize,
                bookName,
                authorName,
                sourceName,
                start + "-" + to,
                contents.size(),
                successCount,
                failCount,
                totalLength,
                fileMeta.getExpireAt().toString()
        );
        result.setDownloadUrl(fileService.buildDownloadUrl(result.getFileId()));
        return result;
    }

    // ---------- 工具方法 ----------

    private void validateKeyword(String keyword) {
        validateKeyword(keyword, "搜索关键词");
    }

    private void validateKeyword(String keyword, String label) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException(label + "过长，最大 " + MAX_KEYWORD_LENGTH + " 字符");
        }
    }

    /** 校验页码（从 1 开始） */
    private void validatePage(int page) {
        if (page < 1) {
            throw new IllegalArgumentException("页码不能小于 1");
        }
    }

    /** 限制搜索结果数量 */
    private List<SearchResult> limitResults(List<SearchResult> results) {
        if (results == null) return Collections.emptyList();
        if (results.size() <= maxSearchResults) return results;
        return results.subList(0, maxSearchResults);
    }

    // ---------- 下载结果 DTO ----------

    /** 下载结果信息 */
    public static class DownloadResult {
        private String fileId;
        private String fileName;
        private long fileSize;
        private String bookName;
        private String author;
        private String sourceName;
        private String chapterRange;
        private int totalChapters;
        private int successCount;
        private int failCount;
        private long totalLength;
        private String expireAt;
        private String downloadUrl;

        public DownloadResult() {}

        public DownloadResult(String fileId, String fileName, long fileSize,
                              String bookName, String author, String sourceName,
                              String chapterRange, int totalChapters,
                              int successCount, int failCount,
                              long totalLength, String expireAt) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.bookName = bookName;
            this.author = author;
            this.sourceName = sourceName;
            this.chapterRange = chapterRange;
            this.totalChapters = totalChapters;
            this.successCount = successCount;
            this.failCount = failCount;
            this.totalLength = totalLength;
            this.expireAt = expireAt;
        }

        public String getFileId() { return fileId; }
        public void setFileId(String fileId) { this.fileId = fileId; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public long getFileSize() { return fileSize; }
        public void setFileSize(long fileSize) { this.fileSize = fileSize; }
        public String getBookName() { return bookName; }
        public void setBookName(String bookName) { this.bookName = bookName; }
        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        public String getSourceName() { return sourceName; }
        public void setSourceName(String sourceName) { this.sourceName = sourceName; }
        public String getChapterRange() { return chapterRange; }
        public void setChapterRange(String chapterRange) { this.chapterRange = chapterRange; }
        public int getTotalChapters() { return totalChapters; }
        public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }
        public int getSuccessCount() { return successCount; }
        public void setSuccessCount(int successCount) { this.successCount = successCount; }
        public int getFailCount() { return failCount; }
        public void setFailCount(int failCount) { this.failCount = failCount; }
        public long getTotalLength() { return totalLength; }
        public void setTotalLength(long totalLength) { this.totalLength = totalLength; }
        public String getExpireAt() { return expireAt; }
        public void setExpireAt(String expireAt) { this.expireAt = expireAt; }
        public String getDownloadUrl() { return downloadUrl; }
        public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
    }
}
