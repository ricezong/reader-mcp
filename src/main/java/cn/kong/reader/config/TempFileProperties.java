package cn.kong.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 临时文件相关配置（配置前缀 reader.download）。
 */
@Component
@ConfigurationProperties(prefix = "reader.download")
public class TempFileProperties {

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
