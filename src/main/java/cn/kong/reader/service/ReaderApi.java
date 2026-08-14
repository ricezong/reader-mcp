package cn.kong.reader.service;

import cn.kong.app.engine.BookSourceManager;
import cn.kong.app.engine.ReaderEngine;
import io.legado.app.data.entities.Book;
import io.legado.app.data.entities.BookChapter;
import io.legado.app.data.entities.BookSource;
import io.legado.app.data.entities.SearchBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 小说/漫画 API 业务逻辑层：封装搜索/详情/目录/正文/下载，支持单源与多源聚合。
 *
 * <p>底层使用 {@link BookSourceManager}（高层管理 API）和 {@link ReaderEngine}（底层静态方法），
 * reader-engine 内部已通过 {@code ReaderEngineBridge.kt} 的 {@code runBlocking} 将 Kotlin suspend
 * 函数桥接为同步调用，本类无需手动管理协程。
 *
 * <p>BookSourceManager 是单例管理器，内置 4 个小说源 + 4 个漫画源，开箱即用。
 * 支持运行时通过 {@code importSources} 导入外部书源。
 *
 * <p>缓存策略：
 * <ul>
 *   <li>{@code searchCache} — 搜索结果缓存（LRU，上限 500 条），用于 resolveBook 时避免重复网络请求</li>
 *   <li>{@code chapterCache} — 章节列表缓存（LRU，上限 100 条）</li>
 * </ul>
 */
@Service
public class ReaderApi {

    private static final Logger log = LoggerFactory.getLogger(ReaderApi.class);

    /** 搜索缓存最大条目数 */
    private static final int MAX_SEARCH_CACHE_SIZE = 500;

    /** 章节列表缓存最大条目数 */
    private static final int MAX_CHAPTER_CACHE_SIZE = 100;

    /** 搜索关键词最大长度 */
    private static final int MAX_KEYWORD_LENGTH = 100;

    private final BookSourceManager manager = BookSourceManager.getInstance();

    /** 搜索结果缓存：key = sourceUrl + "\u0001" + bookUrl（LRU，有上限） */
    private final Map<String, SearchBook> searchCache = Collections.synchronizedMap(
            new LinkedHashMap<String, SearchBook>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SearchBook> eldest) {
                    return size() > MAX_SEARCH_CACHE_SIZE;
                }
            });

    /** 章节列表缓存：key = sourceUrl + "\u0001" + bookUrl（LRU，有上限） */
    private final Map<String, List<BookChapter>> chapterCache = Collections.synchronizedMap(
            new LinkedHashMap<String, List<BookChapter>>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<BookChapter>> eldest) {
                    return size() > MAX_CHAPTER_CACHE_SIZE;
                }
            });

    /** per-key 锁，防止章节缓存击穿（Thundering Herd） */
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    @Value("${reader.max-search-results:50}")
    private int maxSearchResults;

    @Value("${reader.max-download-chapters:200}")
    private int maxDownloadChapters;

    // ---------- 源管理 ----------

    /**
     * 列出所有书源（小说 + 漫画）。
     *
     * @return 书源信息列表
     */
    public List<Map<String, Object>> listSources() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BookSource src : manager.listAllSources()) {
            result.add(sourceToMap(src));
        }
        return result;
    }

    /**
     * 列出小说源。
     *
     * @return 小说源信息列表
     */
    public List<Map<String, Object>> listNovelSources() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BookSource src : manager.listNovelSources()) {
            result.add(sourceToMap(src));
        }
        return result;
    }

    /**
     * 列出漫画源。
     *
     * @return 漫画源信息列表
     */
    public List<Map<String, Object>> listComicSources() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BookSource src : manager.listComicSources()) {
            result.add(sourceToMap(src));
        }
        return result;
    }

    private Map<String, Object> sourceToMap(BookSource src) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookSourceUrl", src.getBookSourceUrl());
        m.put("bookSourceName", src.getBookSourceName());
        m.put("bookSourceType", src.getBookSourceType());
        m.put("type", src.getBookSourceType() == 2 ? "comic" : "novel");
        return m;
    }

    // ---------- 搜索 ----------

    /**
     * 按关键词搜索小说。
     *
     * @param keyword   搜索关键词
     * @param sourceUrl 书源 URL，传 null 或 "all" 聚合搜索所有小说源
     * @return 搜索结果列表
     */
    public List<Map<String, Object>> searchNovel(String keyword, String sourceUrl) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("搜索关键词过长，最大 " + MAX_KEYWORD_LENGTH + " 字符");
        }
        if (sourceUrl == null || sourceUrl.isEmpty() || "all".equalsIgnoreCase(sourceUrl)) {
            return searchBooksToMaps(manager.searchNovel(keyword));
        }
        BookSource src = manager.getSource(sourceUrl);
        if (src == null) throw new IllegalArgumentException("未知书源 URL: " + sourceUrl);
        return searchBooksToMaps(manager.search(src, keyword));
    }

    /**
     * 按关键词搜索漫画。
     *
     * @param keyword   搜索关键词
     * @param sourceUrl 书源 URL，传 null 或 "all" 聚合搜索所有漫画源
     * @return 搜索结果列表
     */
    public List<Map<String, Object>> searchComic(String keyword, String sourceUrl) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("搜索关键词过长，最大 " + MAX_KEYWORD_LENGTH + " 字符");
        }
        if (sourceUrl == null || sourceUrl.isEmpty() || "all".equalsIgnoreCase(sourceUrl)) {
            return searchBooksToMaps(manager.searchComic(keyword));
        }
        BookSource src = manager.getSource(sourceUrl);
        if (src == null) throw new IllegalArgumentException("未知书源 URL: " + sourceUrl);
        return searchBooksToMaps(manager.search(src, keyword));
    }

    /**
     * 按关键词搜索全部源（小说 + 漫画）。
     *
     * @param keyword   搜索关键词
     * @param sourceUrl 书源 URL，传 null 或 "all" 聚合搜索所有源
     * @return 搜索结果列表
     */
    public List<Map<String, Object>> searchAll(String keyword, String sourceUrl) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("搜索关键词过长，最大 " + MAX_KEYWORD_LENGTH + " 字符");
        }
        if (sourceUrl == null || sourceUrl.isEmpty() || "all".equalsIgnoreCase(sourceUrl)) {
            return searchBooksToMaps(manager.searchAll(keyword));
        }
        BookSource src = manager.getSource(sourceUrl);
        if (src == null) throw new IllegalArgumentException("未知书源 URL: " + sourceUrl);
        return searchBooksToMaps(manager.search(src, keyword));
    }

    /**
     * 按作者搜索小说。
     *
     * @param author    作者名
     * @param sourceUrl 书源 URL，传 null 或 "all" 聚合搜索
     * @return 匹配该作者的作品列表
     */
    public List<Map<String, Object>> searchNovelByAuthor(String author, String sourceUrl) {
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("作者名不能为空");
        }
        if (author.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("作者名过长，最大 " + MAX_KEYWORD_LENGTH + " 字符");
        }

        // 先用作者名作为关键词搜索
        List<Map<String, Object>> searchResults;
        if (sourceUrl == null || sourceUrl.isEmpty() || "all".equalsIgnoreCase(sourceUrl)) {
            searchResults = searchBooksToMaps(manager.searchNovelByAuthor(author));
        } else {
            BookSource src = manager.getSource(sourceUrl);
            if (src == null) throw new IllegalArgumentException("未知书源 URL: " + sourceUrl);
            searchResults = searchBooksToMaps(manager.search(src, author));
        }

        // 按作者字段过滤
        String authorNorm = author.trim().toLowerCase();
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> book : searchResults) {
            Object bookAuthorObj = book.get("author");
            if (bookAuthorObj == null) continue;
            String bookAuthor = bookAuthorObj.toString().trim().toLowerCase();
            if (bookAuthor.contains(authorNorm) || authorNorm.contains(bookAuthor)) {
                filtered.add(book);
            }
        }
        return filtered;
    }

    private List<Map<String, Object>> searchBooksToMaps(List<SearchBook> books) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (books == null) return result;

        for (SearchBook b : books) {
            if (b.getBookUrl() == null || b.getBookUrl().isEmpty()) continue;
            if (b.getOrigin() != null && !b.getOrigin().isEmpty()) {
                searchCache.put(b.getOrigin() + "\u0001" + b.getBookUrl(), b);
            }
            result.add(searchBookToMap(b));
            if (result.size() >= maxSearchResults) break;
        }
        return result;
    }

    private Map<String, Object> searchBookToMap(SearchBook b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", b.getName());
        m.put("author", b.getAuthor());
        m.put("bookUrl", b.getBookUrl());
        m.put("intro", b.getIntro());
        m.put("kind", b.getKind());
        m.put("wordCount", b.getWordCount());
        m.put("coverUrl", b.getCoverUrl());
        m.put("origin", b.getOrigin());
        return m;
    }

    // ---------- 详情 ----------

    /**
     * 获取书籍详情。
     *
     * @param sourceUrl 书源 URL
     * @param bookUrl   书籍 URL
     * @return 书籍详情
     */
    public Map<String, Object> getBookInfo(String sourceUrl, String bookUrl) {
        Book book = resolveBook(sourceUrl, bookUrl);
        return bookToMap(book);
    }

    private Book resolveBook(String sourceUrl, String bookUrl) {
        BookSource src = manager.getSource(sourceUrl);
        if (src == null) throw new IllegalArgumentException("未知书源 URL: " + sourceUrl);

        // 优先从搜索缓存获取 SearchBook，再转为 Book
        SearchBook cached = searchCache.get(sourceUrl + "\u0001" + bookUrl);
        if (cached != null) {
            Book book = cached.toBook();
            // 刷新详情
            return ReaderEngine.getBookInfo(src, book);
        }
        return ReaderEngine.getBookInfo(src, bookUrl);
    }

    private Map<String, Object> bookToMap(Book book) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", book.getName());
        m.put("author", book.getAuthor());
        m.put("bookUrl", book.getBookUrl());
        m.put("tocUrl", book.getTocUrl());
        m.put("intro", book.getIntro());
        m.put("coverUrl", book.getCoverUrl());
        m.put("kind", book.getKind());
        m.put("wordCount", book.getWordCount());
        m.put("origin", book.getOrigin());
        return m;
    }

    // ---------- 目录 ----------

    /**
     * 获取章节列表（带缓存）。
     *
     * @param sourceUrl 书源 URL
     * @param bookUrl    书籍 URL
     * @return 包含 total 和 chapters 的目录信息
     */
    public Map<String, Object> getChapterList(String sourceUrl, String bookUrl) {
        Book book = resolveBook(sourceUrl, bookUrl);
        List<BookChapter> chapters = getChapters(sourceUrl, bookUrl, book);

        List<Map<String, Object>> chapterList = new ArrayList<>();
        if (chapters != null) {
            for (int i = 0; i < chapters.size(); i++) {
                BookChapter ch = chapters.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", i + 1);
                m.put("title", ch.getTitle());
                m.put("url", ch.getUrl());
                chapterList.add(m);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", chapterList.size());
        result.put("chapters", chapterList);
        return result;
    }

    /**
     * 获取章节列表，优先从缓存读取。
     * 使用 per-key 锁 + double-check 防止缓存击穿。
     */
    private List<BookChapter> getChapters(String sourceUrl, String bookUrl, Book book) {
        String cacheKey = sourceUrl + "\u0001" + bookUrl;

        // 快速路径：先查缓存（无锁）
        List<BookChapter> cached = chapterCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 慢速路径：per-key 锁防止缓存击穿
        Object lock = keyLocks.computeIfAbsent(cacheKey, k -> new Object());
        try {
            synchronized (lock) {
                cached = chapterCache.get(cacheKey);
                if (cached != null) {
                    return cached;
                }
                BookSource src = manager.getSource(sourceUrl);
                if (src == null) throw new IllegalArgumentException("未知书源 URL: " + sourceUrl);
                List<BookChapter> chapters = ReaderEngine.getChapterList(src, book);
                if (chapters != null) {
                    chapterCache.put(cacheKey, chapters);
                }
                return chapters;
            }
        } finally {
            keyLocks.remove(cacheKey);
        }
    }

    // ---------- 单章正文 ----------

    /**
     * 获取单章正文。
     *
     * @param sourceUrl    书源 URL
     * @param bookUrl      书籍 URL
     * @param chapterIndex 章节序号（从 1 开始）
     * @return 章节正文信息
     */
    public Map<String, Object> getBookContent(String sourceUrl, String bookUrl, int chapterIndex) {
        BookSource src = manager.getSource(sourceUrl);
        if (src == null) throw new IllegalArgumentException("未知书源 URL: " + sourceUrl);

        Book book = resolveBook(sourceUrl, bookUrl);
        List<BookChapter> chapters = getChapters(sourceUrl, bookUrl, book);
        if (chapters == null || chapters.isEmpty()) {
            throw new RuntimeException("章节目录为空");
        }
        if (chapterIndex < 1 || chapterIndex > chapters.size()) {
            throw new IllegalArgumentException("章节序号超出范围 1-" + chapters.size());
        }
        BookChapter ch = chapters.get(chapterIndex - 1);
        String content = ReaderEngine.getBookContent(src, book, ch);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", chapterIndex);
        m.put("title", ch.getTitle());
        m.put("content", content);
        m.put("length", content == null ? 0 : content.length());
        return m;
    }

    // ---------- 批量下载 ----------

    /**
     * 批量下载章节正文，返回拼接后的完整内容。
     *
     * <p>使用 BookSourceManager 的批量下载 API，引擎内部已处理并发和线程安全。
     *
     * @param sourceUrl 书源 URL
     * @param bookUrl   书籍 URL
     * @param start     起始章节序号（从 1 开始）
     * @param end       结束章节序号
     * @return 包含正文内容、下载统计信息的结果
     */
    public Map<String, Object> downloadChapters(String sourceUrl, String bookUrl, int start, int end) {
        BookSource src = manager.getSource(sourceUrl);
        if (src == null) throw new IllegalArgumentException("未知书源 URL: " + sourceUrl);
        if (start < 1) throw new IllegalArgumentException("起始章节不能小于 1");
        if (start > end) throw new IllegalArgumentException("起始章节不能大于结束章节");

        Book book = resolveBook(sourceUrl, bookUrl);
        List<BookChapter> chapters = getChapters(sourceUrl, bookUrl, book);
        if (chapters == null || chapters.isEmpty()) {
            throw new RuntimeException("章节目录为空");
        }
        int to = Math.min(chapters.size(), end);

        // 限制单次下载章节数
        if (to - start + 1 > maxDownloadChapters) {
            throw new IllegalArgumentException(
                    "单次下载章节数超出上限 " + maxDownloadChapters + "（请求 " + (to - start + 1) + " 章）");
        }

        // 截取要下载的章节
        List<BookChapter> toDownload = chapters.subList(start - 1, to);

        // 使用引擎的批量下载 API
        List<String> contents = manager.batchDownloadContent(book, toDownload);

        // 统计和拼接
        int successCount = 0;
        int failCount = 0;
        long totalLength = 0;
        String bookName = book.getName() != null ? book.getName() : "unknown";
        String authorName = book.getAuthor() != null ? book.getAuthor() : "未知";
        String sourceName = src.getBookSourceName() != null ? src.getBookSourceName() : sourceUrl;

        StringBuilder sb = new StringBuilder();
        sb.append("书名：").append(bookName).append("\n");
        sb.append("作者：").append(authorName).append("\n");
        sb.append("来源：").append(sourceName).append(" (").append(sourceUrl).append(")\n");
        sb.append("章节范围：第 ").append(start).append(" 章 ~ 第 ").append(to)
          .append(" 章（共 ").append(contents.size()).append(" 章）\n");
        sb.append("\n========================================\n\n");

        for (int i = 0; i < contents.size(); i++) {
            BookChapter ch = toDownload.get(i);
            String title = ch.getTitle() != null ? ch.getTitle() : "";
            String content = contents.get(i);

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

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", sb.toString());
        resp.put("bookName", bookName);
        resp.put("author", authorName);
        resp.put("sourceName", sourceName);
        resp.put("chapterRange", start + "-" + to);
        resp.put("totalChapters", contents.size());
        resp.put("successCount", successCount);
        resp.put("failCount", failCount);
        resp.put("totalLength", totalLength);
        return resp;
    }

    /** 清除所有缓存 */
    public void clearCaches() {
        searchCache.clear();
        chapterCache.clear();
        keyLocks.clear();
    }
}
