package cn.kong.reader.mcp;

import cn.kong.app.engine.dto.BookDetail;
import cn.kong.app.engine.dto.ChapterInfo;
import cn.kong.app.engine.dto.SearchResult;
import cn.kong.app.engine.dto.SourceInfo;
import cn.kong.reader.service.ReaderFacade;
import cn.kong.reader.service.ReaderFacade.DownloadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 工具定义：将搜索、详情、目录、正文、下载能力暴露为 MCP 工具。
 * <p>返回值为引擎 DTO 对象，由 Spring 自动序列化为 JSON。
 */
@Component
public class ReaderMcpTools {

    private static final Logger log = LoggerFactory.getLogger(ReaderMcpTools.class);

    private final ReaderFacade readerFacade;

    public ReaderMcpTools(ReaderFacade readerFacade) {
        this.readerFacade = readerFacade;
    }

    /** 列出书源（可选按类型筛选） */
    @Tool(name = "list_sources", description = "列出当前所有可用的书源，可按类型筛选。返回书源简称（source）、名称、类型、类型描述。后续操作通过 source 简称指定书源")
    public List<SourceInfo> listSources(
            @ToolParam(description = "书源类型筛选：novel=仅小说源，comic=仅漫画源，不传或空字符串=全部") String type) {
        try {
            if (type == null || type.isBlank()) {
                return readerFacade.listSources();
            }
            String t = type.trim().toLowerCase();
            if ("novel".equals(t)) {
                return readerFacade.listNovelSources();
            } else if ("comic".equals(t)) {
                return readerFacade.listComicSources();
            }
            return readerFacade.listSources();
        } catch (Exception e) {
            log.error("MCP list_sources 调用失败: type={}", type, e);
            throw new RuntimeException("获取书源列表失败，请稍后重试");
        }
    }

    /** 按关键词搜索小说（聚合所有小说源或指定单源，支持分页） */
    @Tool(name = "search_novel", description = "按关键词搜索小说。默认聚合所有小说源并行搜索；若传入 source 简称则只搜索该源。支持分页，不传 page 默认第 1 页。返回结果包含 source 字段供后续操作使用")
    public List<SearchResult> search(
            @ToolParam(description = "搜索关键词") String keyword,
            @ToolParam(description = "书源简称（可选，如 80、dubu）。传入则只搜索该源，不传则聚合所有小说源") String source,
            @ToolParam(description = "页码（可选，默认 1，从 1 开始）") Integer page) {
        try {
            int p = (page == null || page < 1) ? 1 : page;
            if (source != null && !source.isBlank()) {
                return readerFacade.searchBySource(keyword, source, p);
            }
            return readerFacade.searchNovel(keyword, p);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP search_novel 调用失败: keyword={}, source={}, page={}", keyword, source, page, e);
            throw new RuntimeException("搜索失败，请稍后重试或更换书源");
        }
    }

    /** 按关键词搜索漫画（聚合所有漫画源或指定单源，支持分页） */
    @Tool(name = "search_comic", description = "按关键词搜索漫画。默认聚合所有漫画源并行搜索；若传入 source 简称则只搜索该源。支持分页，不传 page 默认第 1 页。返回结果包含 source 字段供后续操作使用")
    public List<SearchResult> searchComic(
            @ToolParam(description = "搜索关键词") String keyword,
            @ToolParam(description = "书源简称（可选，如 godamanga、manhuatai）。传入则只搜索该源，不传则聚合所有漫画源") String source,
            @ToolParam(description = "页码（可选，默认 1，从 1 开始）") Integer page) {
        try {
            int p = (page == null || page < 1) ? 1 : page;
            if (source != null && !source.isBlank()) {
                return readerFacade.searchBySource(keyword, source, p);
            }
            return readerFacade.searchComic(keyword, p);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP search_comic 调用失败: keyword={}, source={}, page={}", keyword, source, page, e);
            throw new RuntimeException("搜索漫画失败，请稍后重试或更换书源");
        }
    }

    /** 按作者搜索小说（聚合所有小说源） */
    @Tool(name = "search_novel_by_author", description = "按作者名查询其全部小说作品，聚合所有小说源搜索，引擎自动过滤匹配作者的结果，返回结果包含 source 字段供后续操作使用")
    public List<SearchResult> searchByAuthor(
            @ToolParam(description = "作者名") String author) {
        try {
            return readerFacade.searchNovelByAuthor(author);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP search_novel_by_author 调用失败: author={}", author, e);
            throw new RuntimeException("按作者搜索失败，请稍后重试或更换书源");
        }
    }

    /** 按作者搜索漫画（聚合所有漫画源） */
    @Tool(name = "search_comic_by_author", description = "按作者名查询其全部漫画作品，聚合所有漫画源搜索，引擎自动过滤匹配作者的结果，返回结果包含 source 字段供后续操作使用")
    public List<SearchResult> searchComicByAuthor(
            @ToolParam(description = "作者名") String author) {
        try {
            return readerFacade.searchComicByAuthor(author);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP search_comic_by_author 调用失败: author={}", author, e);
            throw new RuntimeException("按作者搜索漫画失败，请稍后重试或更换书源");
        }
    }

    /** 获取书籍详情 */
    @Tool(name = "book_info", description = "获取书籍详情（书名、作者、简介、封面URL、字数、最新章节标题、来源简称等）。需要先通过搜索接口获取 source 和 bookUrl。")
    public BookDetail bookInfo(
            @ToolParam(description = "书源简称（如 80、dubu）") String source,
            @ToolParam(description = "书籍 URL") String bookUrl) {
        try {
            return readerFacade.getBookInfo(source, bookUrl);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP book_info 调用失败: source={}, bookUrl={}", source, bookUrl, e);
            throw new RuntimeException("获取书籍详情失败，请稍后重试");
        }
    }

    /** 获取章节目录 */
    @Tool(name = "chapters", description = "获取书籍章节目录（章节序号、标题、URL）")
    public List<ChapterInfo> chapters(
            @ToolParam(description = "书源简称") String source,
            @ToolParam(description = "书籍 URL") String bookUrl) {
        try {
            return readerFacade.getChapterList(source, bookUrl);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP chapters 调用失败: source={}, bookUrl={}", source, bookUrl, e);
            throw new RuntimeException("获取章节目录失败，请稍后重试");
        }
    }

    /** 获取单章正文（章节序号从 1 开始，内部转换为引擎的 0-based） */
    @Tool(name = "content", description = "获取指定章节的正文内容（章节序号从 1 开始）。小说返回纯文本，漫画返回包含 <img> 标签的 HTML。")
    public String content(
            @ToolParam(description = "书源简称") String source,
            @ToolParam(description = "书籍 URL") String bookUrl,
            @ToolParam(description = "章节序号（从 1 开始）") int chapterIndex) {
        try {
            return readerFacade.getBookContent(source, bookUrl, chapterIndex);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP content 调用失败: source={}, bookUrl={}, chapterIndex={}", source, bookUrl, chapterIndex, e);
            throw new RuntimeException("获取章节正文失败，请稍后重试");
        }
    }

    /** 批量下载章节正文并暂存为 TXT 文件 */
    @Tool(name = "download", description = "批量下载章节正文并暂存为 TXT 文件，返回文件下载 URL（24小时有效）、文件名、下载统计信息（成功/失败数、总字数）。单次上限受服务端配置限制。")
    public DownloadResult download(
            @ToolParam(description = "书源简称") String source,
            @ToolParam(description = "书籍 URL") String bookUrl,
            @ToolParam(description = "起始章节序号（从 1 开始）") int start,
            @ToolParam(description = "结束章节序号") int end) {
        try {
            return readerFacade.downloadChapters(source, bookUrl, start, end);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP download 调用失败: source={}, bookUrl={}, start={}, end={}", source, bookUrl, start, end, e);
            throw new RuntimeException("批量下载失败，请稍后重试或减少下载章节数");
        }
    }
}
