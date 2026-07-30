package com.binance.web.service;

import com.binance.web.config.BinanceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Binance 现货交易服务 - 签名请求、下单、查询账户
 *
 * 对接 Binance Spot Testnet（https://testnet.binance.vision）
 * 支持 Market Order 买卖，真实资金操作
 */
@Slf4j
@Service
public class BinanceTradeService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private BinanceProperties binanceProperties;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String HMAC_SHA256 = "HmacSHA256";

    // ==================== 下单 ====================

    /**
     * 市价买入（按 USDT 金额）
     * @param symbol        交易对，如 BTCUSDT
     * @param quoteOrderQty USDT 金额
     * @return {orderId, symbol, executedQty, cummulativeQuoteQty, price, status} 或 null
     */
    public Map<String, String> marketBuy(String symbol, double quoteOrderQty) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol.toUpperCase());
        params.put("side", "BUY");
        params.put("type", "MARKET");
        params.put("quoteOrderQty", formatDecimal(quoteOrderQty));
        return placeOrder(params);
    }

    /**
     * 市价卖出（按币种数量）
     * @param symbol   交易对，如 BTCUSDT
     * @param quantity 卖出数量
     * @return {orderId, symbol, executedQty, cummulativeQuoteQty, price, status} 或 null
     */
    public Map<String, String> marketSell(String symbol, double quantity) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol.toUpperCase());
        params.put("side", "SELL");
        params.put("type", "MARKET");
        params.put("quantity", formatDecimal(quantity));
        return placeOrder(params);
    }

    /**
     * 下单并解析返回
     */
    private Map<String, String> placeOrder(Map<String, String> params) {
        try {
            String symbol = params.get("symbol");
            String side = params.get("side");
            JsonNode resp = signedPost("/api/v3/order", params);
            if (resp == null) return null;

            Map<String, String> result = new LinkedHashMap<>();
            result.put("orderId", String.valueOf(resp.get("orderId").asLong()));
            result.put("symbol", resp.get("symbol").asText());
            result.put("status", resp.get("status").asText());
            result.put("side", resp.get("side").asText());

            // 解析成交信息
            JsonNode fills = resp.get("fills");
            double totalQty = 0;
            double totalQuoteQty = 0;
            if (fills != null && fills.isArray() && fills.size() > 0) {
                for (JsonNode fill : fills) {
                    totalQty += fill.get("qty").asDouble();
                    totalQuoteQty += fill.get("qty").asDouble() * fill.get("price").asDouble();
                }
            }
            // 市价单可能 fills 为空（刚提交），用 origQty 和 cummulativeQuoteQty
            if (resp.has("executedQty")) {
                totalQty = resp.get("executedQty").asDouble();
            }
            if (resp.has("cummulativeQuoteQty")) {
                totalQuoteQty = resp.get("cummulativeQuoteQty").asDouble();
            }

            result.put("executedQty", formatDecimal(totalQty));
            result.put("quoteQty", formatDecimal(totalQuoteQty));
            result.put("avgPrice", totalQty > 0 ? formatDecimal(totalQuoteQty / totalQty) : "0");

            log.info("Binance 下单: {} {} orderId={} 成交={} 均价={} status={}",
                    symbol, side, result.get("orderId"), result.get("executedQty"),
                    result.get("avgPrice"), result.get("status"));

            return result;
        } catch (Exception e) {
            log.error("下单失败 {} {}: {}", params.get("symbol"), params.get("side"), e.getMessage());
            return null;
        }
    }

    // ==================== 查询订单 ====================

    /**
     * 查询订单状态
     */
    public Map<String, String> getOrder(String symbol, long orderId) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol", symbol.toUpperCase());
            params.put("orderId", String.valueOf(orderId));
            JsonNode resp = signedGet("/api/v3/order", params);
            if (resp == null) return null;

            Map<String, String> result = new LinkedHashMap<>();
            result.put("orderId", String.valueOf(resp.get("orderId").asLong()));
            result.put("symbol", resp.get("symbol").asText());
            result.put("status", resp.get("status").asText());
            result.put("side", resp.get("side").asText());
            result.put("executedQty", resp.get("executedQty").asText());
            result.put("cummulativeQuoteQty", resp.get("cummulativeQuoteQty").asText());
            result.put("price", resp.has("price") ? resp.get("price").asText() : "0");
            return result;
        } catch (Exception e) {
            log.debug("查询订单 {} {} 失败: {}", symbol, orderId, e.getMessage());
            return null;
        }
    }

    // ==================== 账户信息 ====================

    /**
     * 获取账户余额
     * @return {USDT: "5000.00", BTC: "0.01", ...}
     */
    public Map<String, String> getBalances() {
        Map<String, String> balances = new LinkedHashMap<>();
        try {
            JsonNode resp = signedGet("/api/v3/account", Collections.emptyMap());
            // log.info("接口获取账户余额: {}", resp);
            if (resp == null) return balances;

            JsonNode list = resp.get("balances");
            if (list != null && list.isArray()) {
                for (JsonNode b : list) {
                    double free = b.get("free").asDouble();
                    double locked = b.get("locked").asDouble();
                    if (free > 0 || locked > 0) {
                        balances.put(b.get("asset").asText(), formatDecimal(free));
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取账户余额失败: {}", e.getMessage());
        }
        return balances;
    }

    /**
     * 获取指定资产余额
     */
    public double getAssetBalance(String asset) {
        Map<String, String> balances = getBalances();
        String val = balances.get(asset.toUpperCase());
        if (val != null) {
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * 获取 USDT 余额
     */
    public double getUsdtBalance() {
        var asset = getAssetBalance("USDT");
        log.info("获取实际币安账户余额显示USDT： {}", asset);
        return asset;
    }

    // ==================== 签名请求 ====================

    /**
     * 带签名的 POST 请求
     */
    private JsonNode signedPost(String path, Map<String, String> params) {
        return signedRequest(HttpMethod.POST, path, params);
    }

    /**
     * 带签名的 GET 请求
     */
    private JsonNode signedGet(String path, Map<String, String> params) {
        return signedRequest(HttpMethod.GET, path, params);
    }

    /**
     * 通用签名请求
     */
    private JsonNode signedRequest(HttpMethod method, String path, Map<String, String> params) {
        String apiKey = binanceProperties.getApiKey();
        String secretKey = binanceProperties.getSecretKey();

        if (apiKey == null || apiKey.isEmpty() || secretKey == null || secretKey.isEmpty()) {
            log.warn("未配置 Binance API Key/Secret，跳过签名请求 {}", path);
            return null;
        }

        // 添加 timestamp
        Map<String, String> signedParams = new LinkedHashMap<>(params);
        signedParams.put("timestamp", String.valueOf(System.currentTimeMillis()));

        // 构建 query string
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : signedParams.entrySet()) {
            if (query.length() > 0) query.append("&");
            query.append(entry.getKey()).append("=").append(urlEncode(entry.getValue()));
        }

        // 计算 HMAC-SHA256 签名
        String signature = hmacSha256(secretKey, query.toString());
        query.append("&signature=").append(signature);

        log.debug("签名请求: {} {}", method, path);

        // 多地址 fallback
        List<String> urls = buildFallbackUrls(path, query.toString());

        for (String url : urls) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-MBX-APIKEY", apiKey);
                headers.setContentType(MediaType.APPLICATION_JSON);

                HttpEntity<Void> entity = new HttpEntity<>(headers);
                ResponseEntity<String> resp;

                if (method == HttpMethod.GET) {
                    resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                } else {
                    // POST：将参数放在请求体中发送
                    resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
                }

                if (resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null) {
                    JsonNode node = objectMapper.readTree(resp.getBody());
                    // 检查是否有错误
                    if (node.has("code") && node.has("msg")) {
                        int code = node.get("code").asInt();
                        String msg = node.get("msg").asText();
                        log.error("Binance API 错误 [{}]: {}", code, msg);
                        return null;
                    }
                    return node;
                }
            } catch (HttpClientErrorException e) {
                String body = e.getResponseBodyAsString();
                log.warn("Binance 签名请求失败 {} status={}: {}", url, e.getStatusCode(), body);
            } catch (ResourceAccessException e) {
                log.warn("Binance 签名请求超时: {} - 请检查 base-url ({}) 是否为网络可达",
                        url, binanceProperties.getBaseUrl());
            } catch (Exception e) {
                log.warn("Binance 签名请求异常: {} - {}", url, e.getMessage());
            }
        }

        return null;
    }

    private List<String> buildFallbackUrls(String path, String queryString) {
        List<String> urls = new ArrayList<>();
        urls.add(binanceProperties.getBaseUrl() + path + "?" + queryString);
        // 测试网不设置 fallback
        return urls;
    }

    // ==================== 工具方法 ====================

    /**
     * HMAC-SHA256 签名
     */
    private String hmacSha256(String key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 签名失败", e);
        }
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            return value;
        }
    }

    private String formatDecimal(double value) {
        if (value == (long) value) {
            return String.format("%d", (long) value);
        }
        // 根据大小决定小数位数
        if (Math.abs(value) < 0.001) {
            return String.format("%.8f", value);
        } else if (Math.abs(value) < 1) {
            return String.format("%.6f", value);
        } else {
            return String.format("%.4f", value);
        }
    }

    /**
     * 通用 GET 请求（非签名，用于公开接口）
     */
    public JsonNode publicGet(String path) {
        try {
            String fullUrl = binanceProperties.getBaseUrl() + path;
            ResponseEntity<String> resp = restTemplate.getForEntity(fullUrl, String.class);
            if (resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null) {
                return objectMapper.readTree(resp.getBody());
            }
        } catch (Exception e) {
            log.debug("公开请求失败 {}: {}", path, e.getMessage());
        }
        return null;
    }
}
