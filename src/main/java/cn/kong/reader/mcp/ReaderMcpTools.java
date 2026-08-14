package cn.kong.reader.mcp;

import cn.kong.reader.service.DownloadFileManager;
import cn.kong.reader.service.ReaderApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具组件：将小说/漫画下载器的搜索/详情/目录/正文/下载能力暴露为 MCP 工具。
 *
 * <p>使用 Spring AI 1.x 标准 {@code @Tool} 注解，方法参数均为基本类型
 * （JSON Schema 可描述），返回值 Map/List 可序列化为 JSON。
 * 由 {@link McpToolConfig} 中的 {@code MethodToolCallbackProvider} 收集并注册到
 * MCP Server，客户端即可通过 MCP 协议远程调用。
 *
 * <p>底层使用 reader-engine 引擎（BookSourceManager / ReaderEngine），
 * 内置 4 个小说源 + 4 个漫画源，支持运行时导入外部书源。
 */
@Component
public class ReaderMcpTools {

    private static final Logger log = LoggerFactory.getLogger(ReaderMcpTools.class);

    private final ReaderApi readerApi;
    private final DownloadFileManager fileManager;

    public ReaderMcpTools(ReaderApi readerApi, DownloadFileManager fileManager) {
        this.readerApi = readerApi;
        this.fileManager = fileManager;
    }

    /** 列出所有书源 */
    @Tool(name = "reader_list_sources", description = "列出当前所有可用的书源（小说源 + 漫画源），包含书源 URL、名称、类型")
    public List<Map<String, Object>> listSources() {
        try {
            return readerApi.listSources();
        } catch (Exception e) {
            log.error("MCP reader_list_sources 调用失败", e);
            throw new RuntimeException("获取书源列表失败，请稍后重试");
        }
    }

    /** 搜索小说 */
    @Tool(name = "reader_search", description = "按关键词搜索小说，可指定书源 URL；省略或传 all 则聚合所有小说源并行搜索")
    public List<Map<String, Object>> search(
            @ToolParam(description = "搜索关键词") String keyword,
            @ToolParam(description = "书源 URL，传 all 或不传表示聚合所有小说源", required = false) String sourceUrl) {
        try {
            return readerApi.searchNovel(keyword, sourceUrl);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP reader_search 调用失败: keyword={}, sourceUrl={}", keyword, sourceUrl, e);
            throw new RuntimeException("搜索失败，请稍后重试或更换书源");
        }
    }

    /** 搜索漫画 */
    @Tool(name = "reader_search_comic", description = "按关键词搜索漫画，可指定书源 URL；省略或传 all 则聚合所有漫画源并行搜索")
    public List<Map<String, Object>> searchComic(
            @ToolParam(description = "搜索关键词") String keyword,
            @ToolParam(description = "书源 URL，传 all 或不传表示聚合所有漫画源", required = false) String sourceUrl) {
        try {
            return readerApi.searchComic(keyword, sourceUrl);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP reader_search_comic 调用失败: keyword={}, sourceUrl={}", keyword, sourceUrl, e);
            throw new RuntimeException("搜索漫画失败，请稍后重试或更换书源");
        }
    }

    /** 按作者搜索作品 */
    @Tool(name = "reader_search_by_author", description = "按作者名查询其全部小说作品，可指定书源 URL；省略或传 all 则聚合所有小说源并行搜索，结果按作者字段过滤匹配")
    public List<Map<String, Object>> searchByAuthor(
            @ToolParam(description = "作者名") String author,
            @ToolParam(description = "书源 URL，传 all 或不传表示聚合所有小说源", required = false) String sourceUrl) {
        try {
            return readerApi.searchNovelByAuthor(author, sourceUrl);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP reader_search_by_author 调用失败: author={}, sourceUrl={}", author, sourceUrl, e);
            throw new RuntimeException("按作者搜索失败，请稍后重试或更换书源");
        }
    }

    /** 获取书籍详情 */
    @Tool(name = "reader_book_info", description = "获取书籍详情（作者、简介、封面、字数等）")
    public Map<String, Object> bookInfo(
            @ToolParam(description = "书源 URL") String sourceUrl,
            @ToolParam(description = "书籍 URL") String bookUrl) {
        try {
            return readerApi.getBookInfo(sourceUrl, bookUrl);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP reader_book_info 调用失败: sourceUrl={}, bookUrl={}", sourceUrl, bookUrl, e);
            throw new RuntimeException("获取书籍详情失败，请稍后重试");
        }
    }

    /** 获取章节目录 */
    @Tool(name = "reader_chapters", description = "获取书籍章节目录（章节序号、标题、URL）")
    public Map<String, Object> chapters(
            @ToolParam(description = "书源 URL") String sourceUrl,
            @ToolParam(description = "书籍 URL") String bookUrl) {
        try {
            return readerApi.getChapterList(sourceUrl, bookUrl);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP reader_chapters 调用失败: sourceUrl={}, bookUrl={}", sourceUrl, bookUrl, e);
            throw new RuntimeException("获取章节目录失败，请稍后重试");
        }
    }

    /** 获取单章正文 */
    @Tool(name = "reader_content", description = "获取指定章节的正文内容（章节序号从 1 开始）")
    public Map<String, Object> content(
            @ToolParam(description = "书源 URL") String sourceUrl,
            @ToolParam(description = "书籍 URL") String bookUrl,
            @ToolParam(description = "章节序号（从 1 开始）") int chapterIndex) {
        try {
            return readerApi.getBookContent(sourceUrl, bookUrl, chapterIndex);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP reader_content 调用失败: sourceUrl={}, bookUrl={}, chapterIndex={}", sourceUrl, bookUrl, chapterIndex, e);
            throw new RuntimeException("获取章节正文失败，请稍后重试");
        }
    }

    /** 批量下载章节正文 */
    @Tool(name = "reader_download", description = "批量下载章节正文并暂存为 TXT 文件，返回文件下载 URL（24小时有效）、文件名、下载统计信息（成功/失败数、总字数）。单次上限受服务端配置限制。")
    public Map<String, Object> download(
            @ToolParam(description = "书源 URL") String sourceUrl,
            @ToolParam(description = "书籍 URL") String bookUrl,
            @ToolParam(description = "起始章节序号（从 1 开始）") int start,
            @ToolParam(description = "结束章节序号") int end) {
        try {
            Map<String, Object> result = readerApi.downloadChapters(sourceUrl, bookUrl, start, end);
            // 构造下载 URL
            String fileId = (String) result.get("fileId");
            String downloadUrl = fileManager.buildDownloadUrl(fileId);
            result.put("downloadUrl", downloadUrl);
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP reader_download 调用失败: sourceUrl={}, bookUrl={}, start={}, end={}", sourceUrl, bookUrl, start, end, e);
            throw new RuntimeException("批量下载失败，请稍后重试或减少下载章节数");
        }
    }
}
