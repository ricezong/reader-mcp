package cn.kong.reader.controller;

import cn.kong.app.engine.dto.BookDetail;
import cn.kong.app.engine.dto.ChapterInfo;
import cn.kong.app.engine.dto.SearchResult;
import cn.kong.app.engine.dto.SourceInfo;
import cn.kong.reader.service.ReaderApi;
import cn.kong.reader.service.TempFileService;
import cn.kong.reader.service.TempFileService.FileMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Web 控制器：为前端页面提供搜索、详情、目录、正文、文件下载的 REST API。
 * <p>所有接口统一前缀 /api/reader。
 * <p>异常处理统一由 {@link GlobalExceptionHandler} 处理，此处不再声明。
 */
@RestController
@RequestMapping("/api/reader")
public class ReaderController {

    private static final Logger log = LoggerFactory.getLogger(ReaderController.class);

    private final ReaderApi readerApi;
    private final TempFileService fileService;

    public ReaderController(ReaderApi readerApi, TempFileService fileService) {
        this.readerApi = readerApi;
        this.fileService = fileService;
    }

    /** 列出所有书源（小说 + 漫画） */
    @GetMapping("/sources")
    public List<SourceInfo> listSources() {
        return readerApi.listSources();
    }

    /** 列出小说源 */
    @GetMapping("/sources/novel")
    public List<SourceInfo> listNovelSources() {
        return readerApi.listNovelSources();
    }

    /** 列出漫画源 */
    @GetMapping("/sources/comic")
    public List<SourceInfo> listComicSources() {
        return readerApi.listComicSources();
    }

    /**
     * 搜索小说（支持分页，可指定书源）。
     *
     * @param keyword 搜索关键词
     * @param page    页码（从 1 开始，默认 1）
     * @param source  书源简称（可选，为空则聚合所有小说源）
     * @return 搜索结果列表
     */
    @GetMapping("/search/novel")
    public List<SearchResult> searchNovel(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String source) {
        if (source != null && !source.isBlank()) {
            return readerApi.searchBySource(keyword, source, page);
        }
        return readerApi.searchNovel(keyword, page);
    }

    /**
     * 搜索漫画（支持分页，可指定书源）。
     *
     * @param keyword 搜索关键词
     * @param page    页码（从 1 开始，默认 1）
     * @param source  书源简称（可选，为空则聚合所有漫画源）
     * @return 搜索结果列表
     */
    @GetMapping("/search/comic")
    public List<SearchResult> searchComic(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String source) {
        if (source != null && !source.isBlank()) {
            return readerApi.searchBySource(keyword, source, page);
        }
        return readerApi.searchComic(keyword, page);
    }

    /**
     * 按作者搜索小说（聚合所有小说源）。
     *
     * @param author 作者名
     * @return 搜索结果列表
     */
    @GetMapping("/search/novel/by-author")
    public List<SearchResult> searchNovelByAuthor(@RequestParam String author) {
        return readerApi.searchNovelByAuthor(author);
    }

    /**
     * 按作者搜索漫画（聚合所有漫画源）。
     *
     * @param author 作者名
     * @return 搜索结果列表
     */
    @GetMapping("/search/comic/by-author")
    public List<SearchResult> searchComicByAuthor(@RequestParam String author) {
        return readerApi.searchComicByAuthor(author);
    }

    /**
     * 获取书籍详情。
     *
     * @param source  书源简称
     * @param bookUrl 书籍 URL
     * @return 书籍详情
     */
    @GetMapping("/book/info")
    public BookDetail bookInfo(
            @RequestParam String source,
            @RequestParam String bookUrl) {
        return readerApi.getBookInfo(source, bookUrl);
    }

    /**
     * 获取章节目录。
     *
     * @param source  书源简称
     * @param bookUrl 书籍 URL
     * @return 章节列表
     */
    @GetMapping("/book/chapters")
    public List<ChapterInfo> chapters(
            @RequestParam String source,
            @RequestParam String bookUrl) {
        return readerApi.getChapterList(source, bookUrl);
    }

    /**
     * 获取单章正文。
     *
     * @param source       书源简称
     * @param bookUrl      书籍 URL
     * @param chapterIndex 章节序号（从 1 开始）
     * @return 正文内容（漫画为图片 HTML）
     */
    @GetMapping("/book/content")
    public Map<String, Object> content(
            @RequestParam String source,
            @RequestParam String bookUrl,
            @RequestParam int chapterIndex) {
        String text = readerApi.getBookContent(source, bookUrl, chapterIndex);
        Map<String, Object> result = new HashMap<>();
        result.put("content", text);
        result.put("isComic", text != null && text.contains("<img"));
        return result;
    }

    /**
     * 下载文件（文件过期后返回 404）。
     *
     * @param fileId 文件 ID
     * @return 文件资源（以附件形式下载）
     */
    @GetMapping("/download/{fileId}")
    public ResponseEntity<?> downloadFile(@PathVariable String fileId) {
        FileMeta meta = fileService.getFile(fileId);
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
