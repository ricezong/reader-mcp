package cn.kong.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 缓存配置属性（配置前缀 reader.cache）。
 * <p>控制各层缓存的过期时间和最大容量，过期后自动清除。
 */
@Component
@ConfigurationProperties(prefix = "reader.cache")
public class CacheProperties {

    /** 搜索结果缓存过期时间（分钟），默认 10 */
    private int searchExpireMinutes = 10;

    /** 书籍详情缓存过期时间（分钟），默认 60 */
    private int detailExpireMinutes = 60;

    /** 章节目录缓存过期时间（分钟），默认 120 */
    private int chapterListExpireMinutes = 120;

    /** 单章正文缓存过期时间（分钟），默认 30 */
    private int contentExpireMinutes = 30;

    /** 缓存最大条目数（每种缓存独立计算），默认 500 */
    private int maxSize = 500;

    // ---------- getters / setters ----------

    public int getSearchExpireMinutes() {
        return searchExpireMinutes;
    }

    public void setSearchExpireMinutes(int searchExpireMinutes) {
        this.searchExpireMinutes = searchExpireMinutes;
    }

    public int getDetailExpireMinutes() {
        return detailExpireMinutes;
    }

    public void setDetailExpireMinutes(int detailExpireMinutes) {
        this.detailExpireMinutes = detailExpireMinutes;
    }

    public int getChapterListExpireMinutes() {
        return chapterListExpireMinutes;
    }

    public void setChapterListExpireMinutes(int chapterListExpireMinutes) {
        this.chapterListExpireMinutes = chapterListExpireMinutes;
    }

    public int getContentExpireMinutes() {
        return contentExpireMinutes;
    }

    public void setContentExpireMinutes(int contentExpireMinutes) {
        this.contentExpireMinutes = contentExpireMinutes;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }
}
