package cn.kong.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 下载文件相关配置。
 *
 * <p>配置前缀 {@code reader.download}，在 application.yml 中配置。
 *
 * <ul>
 *   <li>{@code temp-dir} — 临时文件存放目录，默认系统临时目录下的 {@code reader-downloads}</li>
 *   <li>{@code expire-hours} — 文件过期时间（小时），默认 24 小时后自动清理</li>
 *   <li>{@code base-url} — 服务外部访问基础 URL，用于拼接下载链接；
 *       未配置时自动从请求头推断（部署到服务器时建议显式配置）</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "reader.download")
public class DownloadProperties {

    /** 临时文件存放目录 */
    private String tempDir = System.getProperty("java.io.tmpdir") + "reader-downloads";

    /** 文件过期时间（小时），默认 24 */
    private int expireHours = 24;

    /** 服务外部访问基础 URL（如 https://reader.example.com），用于拼接下载链接 */
    private String baseUrl = "";

    public String getTempDir() {
        return tempDir;
    }

    public void setTempDir(String tempDir) {
        this.tempDir = tempDir;
    }

    public int getExpireHours() {
        return expireHours;
    }

    public void setExpireHours(int expireHours) {
        this.expireHours = expireHours;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
