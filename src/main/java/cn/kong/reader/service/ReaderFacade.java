package cn.kong.reader.service;

import cn.kong.app.engine.ReaderService;
import cn.kong.app.engine.dto.BookDetail;
import cn.kong.app.engine.dto.ChapterContent;
import cn.kong.app.engine.dto.ChapterInfo;
import cn.kong.app.engine.dto.SearchResult;
import cn.kong.app.engine.dto.SourceInfo;
import cn.kong.reader.config.CacheProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 增强门面层：在 {@link ReaderService} 引擎之上提供缓存增强和业务增值。
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li><b>搜索结果</b> — TTL 10 分钟，避免相同关键词短时间重复 HTTP 请求</li>
 *   <li><b>书籍详情</b> — TTL 1 小时，引擎内部只缓存 Book 对象，详情 DTO 每次都重新请求</li>
 *   <li><b>章节目录</b> — TTL 2 小时，目录相对稳定，减少请求</li>
 *   <li><b>单章正文</b> — TTL 30 分钟，用户可能反复阅读同一章</li>
 * </ul>
 *
 * <p>缓存使用 {@link ConcurrentHashMap} + 过期时间戳实现，后台线程定期清理过期条目。
 *
 * <h3>其他增强</h3>
 * <ul>
 *   <li>参数校验 — 关键词长度、页码合法性</li>
 *   <li>搜索结果数量限制 — 防止返回过多结果</li>
 *   <li>下载章节数限制 — 防止滥用</li>
 *   <li>章节序号 1-based 对外 — 内部转换为引擎的 0-based</li>
 *   <li>批量下载 → 写入临时文件 → 返回下载链接</li>
 * </ul>
 */
@Service
public class ReaderFacade {

    private static final Logger log = LoggerFactory.getLogger(ReaderFacade.class);

    /** 搜索关键词最大长度 */
    private static final int MAX_KEYWORD_LENGTH = 100;

    private final ReaderService service;
    private final TempFileService fileService;
    private final CacheProperties cacheProperties;

    @Value("${reader.max-search-results:50}")
    private int maxSearchResults;

    @Value("${reader.max-download-chapters:200}")
    private int maxDownloadChapters;

    // ---------- 缓存定义 ----------

    /** 搜索结果缓存：key = keyword|source|page */
    private final ConcurrentHashMap<String, CacheEntry<List<SearchResult>>> searchCache = new ConcurrentHashMap<>();
    /** 书籍详情缓存：key = source|bookUrl */
    private final ConcurrentHashMap<String, CacheEntry<BookDetail>> detailCache = new ConcurrentHashMap<>();
    /** 章节目录缓存：key = source|bookUrl */
    private final ConcurrentHashMap<String, CacheEntry<List<ChapterInfo>>> chapterCache = new ConcurrentHashMap<>();
    /** 单章正文缓存：key = source|bookUrl|chapterIndex(0-based) */
    private final ConcurrentHashMap<String, CacheEntry<String>> contentCache = new ConcurrentHashMap<>();

    /** 后台清理线程池 */
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reader-cache-cleanup");
        t.setDaemon(true);
        return t;
    });

    public ReaderFacade(TempFileService fileService, ReaderService readerService, CacheProperties cacheProperties) {
        this.fileService = fileService;
        this.service = readerService;
        this.cacheProperties = cacheProperties;
    }

    @PostConstruct
    public void initCacheCleanup() {
        // 每 5 分钟清理一次过期缓存
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredCaches, 5, 5, TimeUnit.MINUTES);
        log.info("缓存初始化: searchTTL={}min, detailTTL={}min, chapterTTL={}min, contentTTL={}min, maxSize={}",
                cacheProperties.getSearchExpireMinutes(),
                cacheProperties.getDetailExpireMinutes(),
                cacheProperties.getChapterListExpireMinutes(),
                cacheProperties.getContentExpireMinutes(),
                cacheProperties.getMaxSize());
    }

    // ==================== 源管理（无缓存，直接透传） ====================

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

    // ==================== 搜索（带缓存） ====================

    /**
     * 按关键词搜索小说（聚合所有小说源）。
     *
     * @param keyword 搜索关键词
     * @return 搜索结果列表，每条结果包含 source 字段供后续操作使用
     */
    public List<SearchResult> searchNovel(String keyword) {
        validateKeyword(keyword);
        return searchWithCache("novel|" + keyword + "|1", () -> limitResults(service.searchNovel(keyword)));
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
        return searchWithCache("novel|" + keyword + "|" + page, () -> limitResults(service.searchNovel(keyword, page)));
    }

    /**
     * 按关键词搜索漫画（聚合所有漫画源）。
     *
     * @param keyword 搜索关键词
     * @return 搜索结果列表，每条结果包含 source 字段供后续操作使用
     */
    public List<SearchResult> searchComic(String keyword) {
        validateKeyword(keyword);
        return searchWithCache("comic|" + keyword + "|1", () -> limitResults(service.searchComic(keyword)));
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
        return searchWithCache("comic|" + keyword + "|" + page, () -> limitResults(service.searchComic(keyword, page)));
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
            return searchWithCache("all|" + keyword + "|" + page, () -> limitResults(service.search(keyword, page)));
        }
        return searchWithCache("src|" + source + "|" + keyword + "|" + page,
                () -> limitResults(service.search(keyword, source, page)));
    }

    /**
     * 按作者搜索小说（聚合所有小说源，引擎自动过滤匹配作者的结果）。
     *
     * @param author 作者名
     * @return 匹配该作者的作品列表，每条结果包含 source 字段供后续操作使用
     */
    public List<SearchResult> searchNovelByAuthor(String author) {
        validateKeyword(author, "作者名");
        return searchWithCache("novelByAuthor|" + author, () -> limitResults(service.searchNovelByAuthor(author)));
    }

    /**
     * 按作者搜索漫画（聚合所有漫画源，引擎自动过滤匹配作者的结果）。
     *
     * @param author 作者名
     * @return 匹配该作者的作品列表，每条结果包含 source 字段供后续操作使用
     */
    public List<SearchResult> searchComicByAuthor(String author) {
        validateKeyword(author, "作者名");
        return searchWithCache("comicByAuthor|" + author, () -> limitResults(service.searchComicByAuthor(author)));
    }

    // ==================== 详情（带缓存） ====================

    /**
     * 获取书籍详情。
     * <p>缓存 TTL 1 小时。引擎内部只缓存 Book 对象用于获取目录/正文，
     * 但 {@code getBookDetail} 每次都重新请求详情页，此处缓存减少 HTTP 请求。
     *
     * @param source  书源简称
     * @param bookUrl 书籍 URL
     * @return 书籍详情
     */
    public BookDetail getBookInfo(String source, String bookUrl) {
        String key = source + "|" + bookUrl;
        CacheEntry<BookDetail> entry = detailCache.get(key);
        if (entry != null && !entry.isExpired()) {
            log.debug("详情缓存命中: source={}, bookUrl={}", source, bookUrl);
            return entry.value;
        }
        BookDetail detail = service.getBookDetail(bookUrl, source);
        putCache(detailCache, key, detail, cacheProperties.getDetailExpireMinutes());
        return detail;
    }

    // ==================== 目录（带缓存） ====================

    /**
     * 获取章节列表。
     * <p>缓存 TTL 2 小时。目录在书籍不更新的情况下是稳定的。
     *
     * @param source  书源简称
     * @param bookUrl 书籍 URL
     * @return 章节列表
     */
    public List<ChapterInfo> getChapterList(String source, String bookUrl) {
        String key = source + "|" + bookUrl;
        CacheEntry<List<ChapterInfo>> entry = chapterCache.get(key);
        if (entry != null && !entry.isExpired()) {
            log.debug("目录缓存命中: source={}, bookUrl={}", source, bookUrl);
            return entry.value;
        }
        List<ChapterInfo> chapters = service.getChapterList(bookUrl, source);
        putCache(chapterCache, key, chapters, cacheProperties.getChapterListExpireMinutes());
        return chapters;
    }

    // ==================== 单章正文（带缓存） ====================

    /**
     * 获取单章正文。
     * <p>缓存 TTL 30 分钟。用户可能反复阅读同一章。
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
        int engineIndex = chapterIndex - 1;
        String key = source + "|" + bookUrl + "|" + engineIndex;
        CacheEntry<String> entry = contentCache.get(key);
        if (entry != null && !entry.isExpired()) {
            log.debug("正文缓存命中: source={}, bookUrl={}, chapterIndex={}", source, bookUrl, chapterIndex);
            return entry.value;
        }
        String content = service.getContent(bookUrl, source, engineIndex);
        // 漫画正文清洗：剥离阅读 App 自定义的 src header 元数据 (如 ",{"headers":{...}}")
        content = sanitizeComicContent(content);
        putCache(contentCache, key, content, cacheProperties.getContentExpireMinutes());
        return content;
    }

    /**
     * 清洗漫画正文：剥离 <img src> 中拼接的阅读 App 自定义 header 元数据。
     * <p>引擎返回的漫画图片 URL 可能附带 ",{"headers":{"Referer": "xxx"}} 格式的元数据，
     * 浏览器无法识别，需要剥离只保留纯净的图片 URL。
     * <p>示例：
     * <pre>
     * src="https://s1.bzmh.net/.../1.jpg,{"headers":{"Referer": "https://tw.baozimh.com/"}}"
     * → src="https://s1.bzmh.net/.../1.jpg"
     * </pre>
     *
     * @param content 原始正文内容
     * @return 清洗后的正文内容
     */
    private String sanitizeComicContent(String content) {
        if (content == null || content.isEmpty() || !content.contains("<img")) {
            return content;
        }
        // 匹配 src="URL,{"headers":{...}}" 中拼接的阅读 App 自定义 header 元数据
        // 引擎返回的漫画 src 格式: src="https://xxx.jpg,{"headers":{"Referer": "https://xxx/"}}"
        // 需要剥离 ,{"headers":{...}} 只保留纯净 URL
        //
        // 正则解释：
        //   (src\s*=\s*["'])       — 捕获 src=" 或 src='
        //   (https?://[^"',]+)     — 捕获 URL 主体（到第一个逗号为止）
        //   ,\{.*\}                — 匹配 ,{...}（贪婪，匹配到最后一个 }，覆盖嵌套 JSON）
        //   (["'])                 — 捕获结束引号
        String pattern = "(src\\s*=\\s*[\"'])(https?://[^\"',]+),\\{.*\\}([\"'])";
        String cleaned = content.replaceAll(pattern, "$1$2$3");
        if (!cleaned.equals(content)) {
            log.debug("漫画正文 src 清洗完成，剥离了 header 元数据");
        }
        return cleaned;
    }

    // ==================== 批量下载 ====================

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
        if (end < 1) throw new IllegalArgumentException("结束章节不能小于 1");
        if (start > end) throw new IllegalArgumentException("起始章节不能大于结束章节");

        // 获取详情和目录（使用缓存的详情和目录）
        BookDetail detail = getBookInfo(source, bookUrl);
        List<ChapterInfo> chapters = getChapterList(source, bookUrl);
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

        // 使用引擎的批量下载 API（index 从 0 开始，end 是 exclusive）
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

                // 将下载到的正文也放入缓存
                String cacheKey = source + "|" + bookUrl + "|" + ch.getIndex();
                putCache(contentCache, cacheKey, content, cacheProperties.getContentExpireMinutes());
            }
            sb.append("\n\n");
        }

        // 写入临时文件
        String txtFileName = bookName + "_" + start + "-" + to + ".txt";
        TempFileService.FileMeta fileMeta;
        long fileSize;
        try {
            fileMeta = fileService.createFile(txtFileName);
            Path filePath = Paths.get(fileMeta.getFilePath());
            Files.write(filePath, sb.toString().getBytes(StandardCharsets.UTF_8));
            // 使用实际文件大小（字节数），而非字符数
            fileSize = Files.size(filePath);
        } catch (IOException e) {
            log.error("写入下载文件失败: fileName={}", txtFileName, e);
            throw new RuntimeException("写入下载文件失败: " + e.getMessage());
        }

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

    // ==================== 缓存管理 ====================

    /**
     * 清除指定书籍的所有缓存（详情、目录、正文）。
     * <p>当书籍更新或用户要求刷新时调用。
     *
     * @param source  书源简称
     * @param bookUrl 书籍 URL
     */
    public void evictBookCache(String source, String bookUrl) {
        String prefix = source + "|" + bookUrl;
        detailCache.remove(prefix);
        chapterCache.remove(prefix);
        // 清除该书籍所有章节的正文缓存
        contentCache.keySet().removeIf(key -> key.startsWith(prefix));
        log.info("已清除书籍缓存: source={}, bookUrl={}", source, bookUrl);
    }

    /**
     * 清除所有缓存。
     */
    public void evictAllCache() {
        searchCache.clear();
        detailCache.clear();
        chapterCache.clear();
        contentCache.clear();
        log.info("已清除所有缓存");
    }

    /**
     * 获取缓存统计信息。
     *
     * @return 缓存统计 Map
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("search", searchCache.size());
        stats.put("detail", detailCache.size());
        stats.put("chapter", chapterCache.size());
        stats.put("content", contentCache.size());
        stats.put("total", searchCache.size() + detailCache.size() + chapterCache.size() + contentCache.size());
        return stats;
    }

    // ==================== 内部缓存方法 ====================

    /**
     * 搜索缓存统一入口。
     */
    private List<SearchResult> searchWithCache(String key, SearchSupplier supplier) {
        CacheEntry<List<SearchResult>> entry = searchCache.get(key);
        if (entry != null && !entry.isExpired()) {
            log.debug("搜索缓存命中: key={}", key);
            return entry.value;
        }
        List<SearchResult> results = supplier.get();
        putCache(searchCache, key, results, cacheProperties.getSearchExpireMinutes());
        return results;
    }

    /**
     * 放入缓存，检查容量上限。
     */
    private <V> void putCache(ConcurrentHashMap<String, CacheEntry<V>> cache,
                              String key, V value, int expireMinutes) {
        if (value == null) return;
        if (cache.size() >= cacheProperties.getMaxSize()) {
            evictOldestEntries(cache, cache.size() / 4 + 1); // 清除 1/4 最旧条目
        }
        cache.put(key, new CacheEntry<>(value, Instant.now().plusSeconds(expireMinutes * 60L)));
    }

    /**
     * 清除最旧的条目（按过期时间排序，优先清除最快过期的）。
     */
    private <V> void evictOldestEntries(ConcurrentHashMap<String, CacheEntry<V>> cache, int count) {
        cache.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().expireAt))
                .limit(count)
                .map(Map.Entry::getKey)
                .forEach(cache::remove);
    }

    /**
     * 定期清理所有过期缓存条目。
     */
    private void cleanupExpiredCaches() {
        int searchBefore = searchCache.size();
        int detailBefore = detailCache.size();
        int chapterBefore = chapterCache.size();
        int contentBefore = contentCache.size();

        searchCache.entrySet().removeIf(e -> e.getValue().isExpired());
        detailCache.entrySet().removeIf(e -> e.getValue().isExpired());
        chapterCache.entrySet().removeIf(e -> e.getValue().isExpired());
        contentCache.entrySet().removeIf(e -> e.getValue().isExpired());

        int searchCleaned = searchBefore - searchCache.size();
        int detailCleaned = detailBefore - detailCache.size();
        int chapterCleaned = chapterBefore - chapterCache.size();
        int contentCleaned = contentBefore - contentCache.size();
        int totalCleaned = searchCleaned + detailCleaned + chapterCleaned + contentCleaned;

        if (totalCleaned > 0) {
            log.info("缓存清理完成: 清除 {} 条过期条目 (search={}, detail={}, chapter={}, content={})",
                    totalCleaned, searchCleaned, detailCleaned, chapterCleaned, contentCleaned);
        }
    }

    // ==================== 工具方法 ====================

    @FunctionalInterface
    private interface SearchSupplier {
        List<SearchResult> get();
    }

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

    // ==================== 缓存条目内部类 ====================

    /**
     * 缓存条目：持有值和过期时间。
     */
    private static class CacheEntry<V> {
        final V value;
        final Instant expireAt;

        CacheEntry(V value, Instant expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expireAt);
        }
    }

    // ==================== 下载结果 DTO ====================

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
