package cn.kong.reader;

import cn.kong.app.engine.ReaderService;
import cn.kong.app.engine.dto.BookDetail;
import cn.kong.app.engine.dto.ChapterInfo;
import cn.kong.app.engine.dto.SearchResult;
import cn.kong.app.engine.dto.SourceInfo;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全书源逐源逐关键词详细测试
 * <p>
 * 测试矩阵：
 *   小说源 (动态扫描) × 小说关键词 (4): 斗破苍穹, 斗罗大陆, 大主宰, 神通者
 *   漫画源 (动态扫描) × 漫画关键词 (4): 斗破苍穹, 偷星九月天, 一人之下, 大主宰
 * <p>
 * 书源列表由 ReaderService 动态扫描 classpath 的 sources/ 目录获取，
 * 无需在测试中硬编码书源简称，新增/删除源文件自动适应。
 * <p>
 * 每个组合验证：搜索 → 详情 → 目录 → 正文 四个环节，并通过日志断言确实获取到对应信息。
 * <p>
 * 测试结果通过 TestResultCollector 收集，最终汇总输出。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ReaderServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ReaderServiceTest.class);

    private static ReaderService service;

    // 小说源简称（从 service 动态获取）
    private static List<String> novelSources;
    // 小说测试关键词
    private static final String[] NOVEL_KEYWORDS = {"斗破苍穹", "斗罗大陆", "大主宰", "神通者"};

    // 漫画源简称（从 service 动态获取）
    private static List<String> comicSources;
    // 漫画测试关键词
    private static final String[] COMIC_KEYWORDS = {"斗破苍穹", "偷星九月天", "一人之下", "大主宰"};

    // 四个测试环节
    private static final String[] STEPS = {"搜索", "详情", "目录", "正文"};

    // 测试结果收集器
    private static final List<TestRecord> records = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        service = ReaderService.getInstance();
        // 从 service 动态获取书源列表
        novelSources = new ArrayList<>();
        for (SourceInfo s : service.listNovelSources()) {
            novelSources.add(s.getSource());
        }
        comicSources = new ArrayList<>();
        for (SourceInfo s : service.listComicSources()) {
            comicSources.add(s.getSource());
        }
        int novelPoints = novelSources.size() * NOVEL_KEYWORDS.length * 4;
        int comicPoints = comicSources.size() * COMIC_KEYWORDS.length * 4;
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║       Reader-Engine 全书源逐源逐关键词详细测试启动           ║");
        log.info("║   小说: {} 源 × {} 关键词 = {} 组合 × 4 环节 = {} 验证点      ║",
                novelSources.size(), NOVEL_KEYWORDS.length,
                novelSources.size() * NOVEL_KEYWORDS.length, novelPoints);
        log.info("║   漫画: {} 源 × {} 关键词 = {} 组合 × 4 环节 = {} 验证点      ║",
                comicSources.size(), COMIC_KEYWORDS.length,
                comicSources.size() * COMIC_KEYWORDS.length, comicPoints);
        log.info("║   合计: {} 个验证点                                        ║", novelPoints + comicPoints);
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    @AfterAll
    static void summary() {
        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║                    测试结果汇总                              ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");

        int pass = 0, fail = 0;
        for (TestRecord r : records) {
            if (r.pass) pass++; else fail++;
        }
        log.info("  总验证点: {} | 通过: {} | 失败: {}", records.size(), pass, fail);

        // ---------- 按环节汇总 ----------
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("  按环节统计 (通过/失败):");
        Map<String, int[]> byStep = new LinkedHashMap<>();
        for (String step : STEPS) {
            byStep.put(step, new int[2]);
        }
        for (TestRecord r : records) {
            int[] arr = byStep.get(r.step);
            if (arr != null) {
                if (r.pass) arr[0]++; else arr[1]++;
            }
        }
        for (Map.Entry<String, int[]> e : byStep.entrySet()) {
            int[] v = e.getValue();
            String line = String.format("    %-6s  %3d / %3d  %s", e.getKey(), v[0], v[1], v[1] == 0 ? "✓" : "✗");
            log.info(line);
        }

        // ---------- 按书源汇总 ----------
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("  按书源统计 (搜索/详情/目录/正文 各环节 通过·失败):");

        // 收集所有出现过的书源（保持顺序）
        List<String> allSources = new ArrayList<>();
        allSources.addAll(novelSources);
        allSources.addAll(comicSources);

        // source -> step -> [pass, fail]
        Map<String, Map<String, int[]>> bySource = new LinkedHashMap<>();
        for (String src : allSources) {
            Map<String, int[]> stepMap = new LinkedHashMap<>();
            for (String step : STEPS) {
                stepMap.put(step, new int[2]);
            }
            bySource.put(src, stepMap);
        }
        for (TestRecord r : records) {
            Map<String, int[]> stepMap = bySource.get(r.source);
            if (stepMap != null) {
                int[] arr = stepMap.get(r.step);
                if (arr != null) {
                    if (r.pass) arr[0]++; else arr[1]++;
                }
            }
        }

        // 表头
        String header = String.format("    %-10s  %-14s  %-14s  %-14s  %-14s  %-8s  %s",
                "书源", "搜索", "详情", "目录", "正文", "总计", "状态");
        log.info(header);
        log.info("    {}", repeat("─", 82));

        for (String src : allSources) {
            Map<String, int[]> stepMap = bySource.get(src);

            int srcPass = 0, srcFail = 0;
            String[] cells = new String[STEPS.length];
            for (int i = 0; i < STEPS.length; i++) {
                int[] v = stepMap.get(STEPS[i]);
                srcPass += v[0];
                srcFail += v[1];
                cells[i] = v[0] + "·" + v[1] + (v[1] > 0 ? "✗" : "");
            }

            String total = srcPass + "·" + srcFail;
            String mark = srcFail == 0 ? "✓" : "✗";

            String row = String.format("    [%-8s]  %-14s  %-14s  %-14s  %-14s  %-8s  %s",
                    src, cells[0], cells[1], cells[2], cells[3], total, mark);
            log.info(row);
        }

        // ---------- 按分类×环节汇总 ----------
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("  按分类×环节统计 (通过/失败):");
        Map<String, int[]> byCategory = new LinkedHashMap<>();
        for (String cat : new String[]{"小说", "漫画"}) {
            for (String step : STEPS) {
                byCategory.put(cat + "-" + step, new int[2]);
            }
        }
        for (TestRecord r : records) {
            String key = r.category + "-" + r.step;
            int[] arr = byCategory.get(key);
            if (arr != null) {
                if (r.pass) arr[0]++; else arr[1]++;
            }
        }
        for (Map.Entry<String, int[]> e : byCategory.entrySet()) {
            int[] v = e.getValue();
            String line = String.format("    %-10s  %3d / %3d  %s", e.getKey(), v[0], v[1], v[1] == 0 ? "✓" : "✗");
            log.info(line);
        }

        // ---------- 失败明细 ----------
        log.info("╠══════════════════════════════════════════════════════════════╣");
        if (fail > 0) {
            log.info("  失败明细:");
            for (TestRecord r : records) {
                if (!r.pass) {
                    log.info("    [{}] {} | {} | {} | {}", r.category, r.source, r.keyword, r.step, r.reason);
                }
            }
        } else {
            log.info("  全部通过，无失败项 ✓");
        }
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    // ==================== 小说源测试 ====================

    @Test
    @Order(1)
    @DisplayName("小说源列表加载")
    public void testNovelSourcesLoaded() {
        log.info("========== 验证小说源加载 ==========");
        List<SourceInfo> novels = service.listNovelSources();
        assertTrue(novels.size() > 0, "小说源数量应大于 0");
        for (SourceInfo s : novels) {
            log.info("  小说源: {} | {} | type={}", s.getSource(), s.getName(), s.getTypeDesc());
        }
    }

    @Test
    @Order(2)
    @DisplayName("小说源逐源逐关键词完整测试")
    public void testNovelMatrix() {
        log.info("");
        log.info("██████████ 小说源逐源逐关键词测试 ██████████");
        for (String source : novelSources) {
            for (String keyword : NOVEL_KEYWORDS) {
                testOneCombination(source, keyword, "小说");
            }
        }
    }

    // ==================== 漫画源测试 ====================

    @Test
    @Order(3)
    @DisplayName("漫画源列表加载")
    public void testComicSourcesLoaded() {
        log.info("========== 验证漫画源加载 ==========");
        List<SourceInfo> comics = service.listComicSources();
        assertTrue(comics.size() > 0, "漫画源数量应大于 0");
        for (SourceInfo s : comics) {
            log.info("  漫画源: {} | {} | type={}", s.getSource(), s.getName(), s.getTypeDesc());
        }
    }

    @Test
    @Order(4)
    @DisplayName("漫画源逐源逐关键词完整测试")
    public void testComicMatrix() {
        log.info("");
        log.info("██████████ 漫画源逐源逐关键词测试 ██████████");
        for (String source : comicSources) {
            for (String keyword : COMIC_KEYWORDS) {
                testOneCombination(source, keyword, "漫画");
            }
        }
    }

    // ==================== 核心测试逻辑 ====================

    /**
     * 测试一个 (source, keyword) 组合的完整流程：搜索 → 详情 → 目录 → 正文
     */
    private void testOneCombination(String source, String keyword, String category) {
        log.info("");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("测试组合: [{}] 源 [{}] 关键词 [{}]", category, source, keyword);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // ---------- Step 1: 搜索 ----------
        String bookUrl = null;
        try {
            log.info("  [1/4] 搜索...");
            List<SearchResult> results = service.search(keyword, source);
            log.info("  搜索结果数: {}", results.size());

            if (results.isEmpty()) {
                record(category, "搜索", source, keyword, false, "搜索结果为空");
                log.warn("  ✗ 搜索失败: 无结果");
                return;
            }

            // 打印前 3 条结果
            for (int i = 0; i < Math.min(results.size(), 3); i++) {
                SearchResult r = results.get(i);
                log.info("    [{}] {} | {} | url={}", i + 1, r.getName(), r.getAuthor(), r.getBookUrl());
            }

            // 找到名称包含关键词的结果（更精确的匹配验证）
            SearchResult matched = null;
            for (SearchResult r : results) {
                if (r.getName() != null && r.getName().contains(keyword)) {
                    matched = r;
                    break;
                }
            }
            if (matched == null) {
                matched = results.get(0);
                log.info("  未找到名称完全包含关键词的结果，使用首条结果");
            }
            bookUrl = matched.getBookUrl();
            log.info("  ✓ 搜索成功: 选中《{}》by {} | url={}", matched.getName(), matched.getAuthor(), bookUrl);
            record(category, "搜索", source, keyword, true,
                    "结果数=" + results.size() + ", 选中=" + matched.getName());
        } catch (Exception e) {
            // SourceException.getMessage() 已包含完整格式化信息，直接输出
            record(category, "搜索", source, keyword, false, e.getMessage());
            log.error("  {}", e.getMessage());
            return;
        }

        // ---------- Step 2: 详情 ----------
        try {
            log.info("  [2/4] 获取详情...");
            BookDetail detail = service.getBookDetail(bookUrl, source);
            assertNotNull(detail, "详情不应为 null");

            boolean nameOk = detail.getName() != null && !detail.getName().trim().isEmpty();
            boolean authorOk = detail.getAuthor() != null && !detail.getAuthor().trim().isEmpty();
            boolean introOk = detail.getIntro() != null && !detail.getIntro().trim().isEmpty();
            boolean coverOk = detail.getCoverUrl() != null && !detail.getCoverUrl().trim().isEmpty();

            log.info("  书名: {}", detail.getName());
            log.info("  作者: {}", detail.getAuthor());
            log.info("  封面: {}", detail.getCoverUrl());
            log.info("  分类: {}", detail.getKind());
            log.info("  字数: {}", detail.getWordCount());
            log.info("  最新章节: {}", detail.getLatestChapterTitle());
            String introPreview = detail.getIntro() == null ? "" :
                    (detail.getIntro().length() > 120 ?
                            detail.getIntro().substring(0, 120) + "..." : detail.getIntro());
            log.info("  简介: {}", introPreview);

            // 关键字段断言：书名和作者必须非空
            if (!nameOk || !authorOk) {
                record(category, "详情", source, keyword, false,
                        "关键字段缺失: name=" + detail.getName() + ", author=" + detail.getAuthor());
                log.warn("  ✗ 详情失败: 关键字段缺失");
                return;
            }
            log.info("  ✓ 详情成功: name={}, author={}, intro长度={}, cover={}",
                    detail.getName(), detail.getAuthor(),
                    detail.getIntro() == null ? 0 : detail.getIntro().length(),
                    coverOk ? "有" : "无");
            record(category, "详情", source, keyword, true,
                    "name=" + detail.getName() + ", author=" + detail.getAuthor() +
                            ", intro=" + (introOk ? "有" : "无") + ", cover=" + (coverOk ? "有" : "无"));
        } catch (Exception e) {
            // SourceException.getMessage() 已包含完整格式化信息，直接输出
            record(category, "详情", source, keyword, false, e.getMessage());
            log.error("  {}", e.getMessage());
            return;
        }

        // ---------- Step 3: 目录 ----------
        List<ChapterInfo> chapters;
        try {
            log.info("  [3/4] 获取目录...");
            chapters = service.getChapterList(bookUrl, source);
            log.info("  章节数: {}", chapters.size());

            if (chapters.isEmpty()) {
                record(category, "目录", source, keyword, false, "目录为空");
                log.warn("  ✗ 目录失败: 为空");
                return;
            }

            // 打印前 5 章
            for (int i = 0; i < Math.min(chapters.size(), 5); i++) {
                ChapterInfo c = chapters.get(i);
                log.info("    [{}] {} | {}", c.getIndex(), c.getTitle(), c.getUrl());
            }
            // 打印最后 1 章
            if (chapters.size() > 5) {
                ChapterInfo last = chapters.get(chapters.size() - 1);
                log.info("    ... [{}] {} | {}", last.getIndex(), last.getTitle(), last.getUrl());
            }

            // 断言：首章标题和 URL 必须非空
            ChapterInfo first = chapters.get(0);
            boolean titleOk = first.getTitle() != null && !first.getTitle().trim().isEmpty();
            boolean urlOk = first.getUrl() != null && !first.getUrl().trim().isEmpty();
            if (!titleOk || !urlOk) {
                record(category, "目录", source, keyword, false,
                        "首章字段缺失: title=" + first.getTitle() + ", url=" + first.getUrl());
                log.warn("  ✗ 目录失败: 首章字段缺失");
                return;
            }
            log.info("  ✓ 目录成功: 共 {} 章, 首章={}", chapters.size(), first.getTitle());
            record(category, "目录", source, keyword, true,
                    "章节数=" + chapters.size() + ", 首章=" + first.getTitle());
        } catch (Exception e) {
            // SourceException.getMessage() 已包含完整格式化信息，直接输出
            record(category, "目录", source, keyword, false, e.getMessage());
            log.error("  {}", e.getMessage());
            return;
        }

        // ---------- Step 4: 正文 ----------
        try {
            log.info("  [4/4] 获取正文...");
            String content = service.getContent(bookUrl, source, 0);
            int len = content == null ? 0 : content.length();
            log.info("  正文长度: {}", len);

            if (len == 0) {
                record(category, "正文", source, keyword, false, "正文为空");
                log.warn("  ✗ 正文失败: 为空");
                return;
            }

            if (category.equals("漫画")) {
                int imgCount = content.split("<img").length - 1;
                log.info("  图片数: {}", imgCount);
                String preview = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                log.info("  正文预览: {}", preview);
                if (imgCount == 0) {
                    record(category, "正文", source, keyword, false, "漫画正文无图片");
                    log.warn("  ✗ 正文失败: 漫画正文无图片");
                    return;
                }
                log.info("  ✓ 正文成功: 长度={}, 图片数={}", len, imgCount);
                record(category, "正文", source, keyword, true,
                        "长度=" + len + ", 图片数=" + imgCount);
            } else {
                String preview = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                log.info("  正文预览: {}", preview);
                log.info("  ✓ 正文成功: 长度={}", len);
                record(category, "正文", source, keyword, true, "长度=" + len);
            }
        } catch (Exception e) {
            // SourceException.getMessage() 已包含完整格式化信息，直接输出
            record(category, "正文", source, keyword, false, e.getMessage());
            log.error("  {}", e.getMessage());
        }
    }

    private static void record(String category, String step, String source, String keyword, boolean pass, String reason) {
        records.add(new TestRecord(category, step, source, keyword, pass, reason));
    }

    private static class TestRecord {
        final String category;
        final String step;
        final String source;
        final String keyword;
        final boolean pass;
        final String reason;
        TestRecord(String category, String step, String source, String keyword, boolean pass, String reason) {
            this.category = category;
            this.step = step;
            this.source = source;
            this.keyword = keyword;
            this.pass = pass;
            this.reason = reason;
        }
    }

    /** Java 8 兼容的字符串重复方法 */
    private static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
