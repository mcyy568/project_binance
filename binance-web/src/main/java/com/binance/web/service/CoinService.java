package com.binance.web.service;

import com.binance.web.config.BinanceProperties;
import com.binance.web.entity.CoinInfo;
import com.binance.web.entity.KlineData;
import com.binance.web.mapper.KlineMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class CoinService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private BinanceProperties binanceProperties;

    @Autowired(required = false)
    private KlineMapper klineMapper;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 备选 API 地址列表 */
    private static final String[] FALLBACK_URLS = {
            "https://api.binance.com",
            "https://api1.binance.com",
            "https://api3.binance.com"
    };

    /** 当前可用的 base URL（随 fallback 结果更新） */
    private volatile String activeBaseUrl;

    /** 缓存币种列表 (symbol -> CoinInfo) */
    private final Map<String, CoinInfo> coinCache = new ConcurrentHashMap<>();

    /** 定时任务：刷新所有 K 线数据 */
    public void refreshAllKlines() {
        List<CoinInfo> coins = getCoinList();
        if (coins.isEmpty()) {
            log.warn("币种列表为空，跳过 K线刷新");
            return;
        }
        int count = 0;
        for (CoinInfo coin : coins) {
            try {
                getKlines(coin.getSymbol(), "15m", 100);
                count++;
            } catch (Exception e) {
                log.warn("刷新 {} K线失败: {}", coin.getSymbol(), e.getMessage());
            }
        }
        log.info("定时刷新完成，刷新了 {} 个币种的 K线数据", count);
    }

    /** 获取缓存的所有交易对 */
    public Set<String> getCachedSymbols() {
        return new HashSet<>(coinCache.keySet());
    }

    /** 获取单个交易对信息（缓存为空时自动刷新） */
    public CoinInfo getCoinInfo(String symbol) {
        if (coinCache.isEmpty()) {
            refreshCoinList();
        }
        CoinInfo info = coinCache.get(symbol.toUpperCase());
        if (info == null) {
            // 缓存中不存在，尝试强制刷新后重试
            log.debug("{} 不在缓存中，尝试刷新交易对列表", symbol);
            refreshCoinList();
            info = coinCache.get(symbol.toUpperCase());
        }
        return info;
    }

    /** 获取币种列表 */
    public List<CoinInfo> getCoinList() {
        List<CoinInfo> cachedList = new ArrayList<>(coinCache.values());
        if (!cachedList.isEmpty()) {
            return cachedList;
        }
        return refreshCoinList();
    }

    /** 强制刷新币种列表 */
    public List<CoinInfo> refreshCoinList() {
        try {
            Map<String, String> priceMap = fetchAllPrices();
            List<CoinInfo> list = fetchExchangeInfo(priceMap);

            coinCache.clear();
            for (CoinInfo coin : list) {
                coinCache.put(coin.getSymbol(), coin);
            }
            log.info("刷新币种列表成功，共 {} 个 USDT 交易对", list.size());
            return list;
        } catch (Exception e) {
            log.warn("刷新币种列表失败: {}", e.getMessage());
            // 返回缓存中的数据（如果有）
            List<CoinInfo> fallback = new ArrayList<>(coinCache.values());
            if (!fallback.isEmpty()) {
                log.info("使用缓存数据，共 {} 个交易对", fallback.size());
            }
            return fallback;
        }
    }

    /** 获取指定币种的 K 线数据 */
    public List<KlineData> getKlines(String symbol, String interval, int limit) {
        List<KlineData> result = new ArrayList<>();

        // 先从 DB 查
        if (klineMapper != null) {
            try {
                List<KlineData> dbData = klineMapper.selectBySymbol(symbol.toUpperCase());
                if (dbData != null && !dbData.isEmpty()) {
                    result.addAll(dbData);
                    log.debug("从 DB 获取 {} 的 K线数据，共 {} 条", symbol, result.size());
                    return result;
                }
            } catch (Exception e) {
                log.warn("从 DB 查询 K线数据失败: {}", e.getMessage());
            }
        }

        // DB 为空则调 Binance API
        try {
            String path = "/api/v3/klines?symbol=" + symbol.toUpperCase()
                    + "&interval=" + interval + "&limit=" + limit;
            String body = callBinanceApi(path);
            if (body == null) return result;
            JsonNode root = objectMapper.readTree(body);

            for (JsonNode item : root) {
                KlineData kd = new KlineData();
                kd.setSymbol(symbol.toUpperCase());
                kd.setOpenTime(item.get(0).asLong());
                kd.setOpen(item.get(1).asText());
                kd.setHigh(item.get(2).asText());
                kd.setLow(item.get(3).asText());
                kd.setClose(item.get(4).asText());
                kd.setVolume(item.get(5).asText());
                kd.setCloseTime(item.get(6).asLong());
                kd.setQuoteAssetVolume(item.get(7).asText());
                kd.setNumberOfTrades(item.get(8).asInt());
                kd.setTakerBuyBaseAssetVolume(item.get(9).asText());
                kd.setTakerBuyQuoteAssetVolume(item.get(10).asText());
                result.add(kd);
            }

            // 写入 DB
            if (klineMapper != null && !result.isEmpty()) {
                try {
                    klineMapper.batchInsertIgnore(result);
                    klineMapper.deleteExpired(symbol.toUpperCase(), 500);
                } catch (Exception e) {
                    log.warn("K线数据写入 DB 失败: {}", e.getMessage());
                }
            }
            log.info("从 Binance API 获取 {} 的 K线数据，共 {} 条", symbol, result.size());
        } catch (Exception e) {
            log.error("获取 {} K线数据失败: {}", symbol, e.getMessage());
        }
        return result;
    }

    // ==================== 私有方法 ====================

    /** 调用 Binance API，支持多地址 fallback */
    private String callBinanceApi(String path) {
        List<String> urls = new ArrayList<>();
        // 优先用上次成功的 baseUrl
        if (activeBaseUrl != null && !activeBaseUrl.equals(binanceProperties.getBaseUrl())) {
            urls.add(activeBaseUrl);
        }
        urls.add(binanceProperties.getBaseUrl());
        for (String fb : FALLBACK_URLS) {
            if (!urls.contains(fb)) {
                urls.add(fb);
            }
        }

        for (String baseUrl : urls) {
            int shortHash = Math.abs(baseUrl.hashCode() % 10000);
            String shortName = baseUrl.replace("https://", "");
            try {
                String fullUrl = baseUrl + path;
                ResponseEntity<String> resp = restTemplate.getForEntity(fullUrl, String.class);
                if (resp.getStatusCode() == HttpStatus.OK) {
                    if (!baseUrl.equals(activeBaseUrl)) {
                        log.info("Binance API 切换到: {} (hash={})", shortName, shortHash);
                        activeBaseUrl = baseUrl;
                    }
                    return resp.getBody();
                }
                log.warn("{} 返回非 200 状态: {}", shortName, resp.getStatusCodeValue());
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 451) {
                    log.warn("{} 返回 451（地域受限），尝试下一个地址", shortName);
                } else {
                    log.warn("{} 返回 HTTP {}: {}", shortName, e.getStatusCode().value(), e.getMessage());
                }
            } catch (ResourceAccessException e) {
                log.warn("{} 网络不通: {}，尝试下一个地址", shortName, e.getMessage());
            } catch (Exception e) {
                log.warn("{} 请求异常: {}", shortName, e.getMessage());
            }
        }
        log.error("所有 Binance API 地址均无法访问，请检查代理是否正常");
        return null;
    }

    /** 获取所有币种价格 */
    private Map<String, String> fetchAllPrices() {
        Map<String, String> priceMap = new HashMap<>();
        try {
            String body = callBinanceApi("/api/v3/ticker/price");
            if (body == null) return priceMap;
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode item : root) {
                String symbol = item.get("symbol").asText();
                String price = item.get("price").asText();
                priceMap.put(symbol, price);
            }
            log.debug("获取价格成功，共 {} 个", priceMap.size());
        } catch (Exception e) {
            log.error("解析价格数据失败: {}", e.getMessage(), e);
        }
        return priceMap;
    }

    /** 获取交易对信息并过滤 USDT */
    private List<CoinInfo> fetchExchangeInfo(Map<String, String> priceMap) {
        List<CoinInfo> list = new ArrayList<>();
        try {
            String body = callBinanceApi("/api/v3/exchangeInfo");
            if (body == null) return list;
            JsonNode root = objectMapper.readTree(body);
            JsonNode symbols = root.get("symbols");

            if (symbols != null && symbols.isArray()) {
                for (JsonNode s : symbols) {
                    String status = s.get("status").asText();
                    if (!"TRADING".equals(status)) continue;

                    String quoteAsset = s.get("quoteAsset").asText();
                    if (!"USDT".equals(quoteAsset)) continue;

                    CoinInfo coin = new CoinInfo();
                    coin.setSymbol(s.get("symbol").asText());
                    coin.setBaseAsset(s.get("baseAsset").asText());
                    coin.setQuoteAsset(quoteAsset);
                    if (s.hasNonNull("baseAssetPrecision")) {
                        coin.setBaseAssetPrecision(s.get("baseAssetPrecision").asLong());
                    }
                    if (s.hasNonNull("quoteAssetPrecision")) {
                        coin.setQuoteAssetPrecision(s.get("quoteAssetPrecision").asLong());
                    }
                    // 解析 LOT_SIZE 过滤器
                    JsonNode filters = s.get("filters");
                    if (filters != null && filters.isArray()) {
                        for (JsonNode f : filters) {
                            if ("LOT_SIZE".equals(f.get("filterType").asText())) {
                                coin.setStepSize(f.get("stepSize").asText());
                                coin.setMinQty(f.get("minQty").asText());
                                coin.setMaxQty(f.get("maxQty").asText());
                                break;
                            }
                        }
                    }
                    coin.setPrice(priceMap.getOrDefault(coin.getSymbol(), "0"));
                    list.add(coin);
                }
            }
            log.debug("获取交易对信息成功，USDT 交易对共 {} 个", list.size());
        } catch (Exception e) {
            log.error("解析交易对数据失败: {}", e.getMessage(), e);
        }
        return list;
    }
}
