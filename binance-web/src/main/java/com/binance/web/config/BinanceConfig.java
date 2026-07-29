package com.binance.web.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Proxy;

@Slf4j
@Configuration
public class BinanceConfig {

    @Autowired
    private BinanceProperties binanceProperties;

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(30000);

        BinanceProperties.Proxy proxyConf = binanceProperties.getProxy();
        if (proxyConf.isEnabled()) {
            Proxy proxy = new Proxy(
                    Proxy.Type.HTTP,
                    new InetSocketAddress(proxyConf.getHost(), proxyConf.getPort())
            );
            factory.setProxy(proxy);
            log.info("Binance RestTemplate 已启用代理: {}:{}", proxyConf.getHost(), proxyConf.getPort());
        } else {
            log.info("Binance RestTemplate 未启用代理，直连");
        }

        return new RestTemplate(factory);
    }

    /**
     * 启动时自检代理连通性
     */
    @PostConstruct
    public void checkProxyConnectivity() {
        BinanceProperties.Proxy proxyConf = binanceProperties.getProxy();
        if (!proxyConf.isEnabled()) {
            return;
        }

        SimpleClientHttpRequestFactory testFactory = new SimpleClientHttpRequestFactory();
        testFactory.setConnectTimeout(5000);
        testFactory.setReadTimeout(10000);
        testFactory.setProxy(new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress(proxyConf.getHost(), proxyConf.getPort())));
        RestTemplate testRest = new RestTemplate(testFactory);

        // 1. 测试代理本身是否可达
        try {
            ResponseEntity<String> pingResp = testRest.getForEntity(
                    binanceProperties.getBaseUrl() + "/api/v3/ping", String.class);
            if (pingResp.getStatusCode() == HttpStatus.OK) {
                log.info("代理自检通过：{}:{} ping Binance API 成功", proxyConf.getHost(), proxyConf.getPort());
                return;
            }
        } catch (Exception e) {
            // 连不上代理或代理连不上 Binance
            String cause = e.getMessage();
            if (cause != null && cause.contains("Connection refused")) {
                log.error("代理自检失败：无法连接代理 {}:{}，请确认代理软件已启动", proxyConf.getHost(), proxyConf.getPort());
            } else if (cause != null && cause.contains("timed out")) {
                log.error("代理自检失败：连接代理 {}:{} 超时，请检查代理端口是否正确", proxyConf.getHost(), proxyConf.getPort());
            } else {
                log.error("代理自检失败：{}:{} → {}", proxyConf.getHost(), proxyConf.getPort(), cause);
            }
            return;
        }

        // 2. 测试 Binance API 是否响应正常（非 451）
        try {
            ResponseEntity<String> priceResp = testRest.getForEntity(
                    binanceProperties.getBaseUrl() + "/api/v3/ticker/price?symbol=BTCUSDT", String.class);
            String body = priceResp.getBody();
            if (body != null && body.contains("\"price\"")) {
                log.info("代理自检通过：BTCUSDT 数据获取成功");
                return;
            }
            if (priceResp.getStatusCodeValue() == 451) {
                log.error("代理自检失败：Binance 返回 451（地域受限），代理出口 IP 位于受限地区，请更换代理节点");
            } else {
                log.warn("代理自检：Binance 返回异常状态 {}，响应：{}", priceResp.getStatusCodeValue(), body);
            }
        } catch (Exception e) {
            log.warn("代理自检：获取 BTCUSDT 价格失败：{}", e.getMessage());
        }
    }
}
