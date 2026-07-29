package com.binance.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "binance")
public class BinanceProperties {

    /** Binance API 基础地址 */
    private String baseUrl = "https://api.binance.com";

    /** API Key */
    private String apiKey;

    /** Secret Key */
    private String secretKey;

    /** HTTP 代理配置 */
    private Proxy proxy = new Proxy();

    /** 邮件通知配置 */
    private Notification notification = new Notification();

    @Data
    public static class Proxy {

        /** 是否启用代理 */
        private boolean enabled = false;

        /** 代理主机地址 */
        private String host = "127.0.0.1";

        /** 代理端口 */
        private int port = 7890;
    }

    @Data
    public static class Notification {
        /** 接收通知的邮箱地址 */
        private String email;
    }
}
