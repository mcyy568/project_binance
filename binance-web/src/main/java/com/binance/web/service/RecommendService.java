package com.binance.web.service;

import com.binance.web.config.BinanceProperties;
import com.binance.web.entity.KlineData;
import com.binance.web.entity.Recommendation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 推荐服务 - 基于流程图的多层扫描逻辑
 * 每10分钟扫描全市场 USDT 永续合约，生成涨跌推荐
 */
@Slf4j
@Service
public class RecommendService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private BinanceProperties binanceProperties;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    /** 扫描常量 */
    private static final int TOP_N = 500;                    // 扫描成交量前N名
    private static final double MIN_VOLUME_USDT = 5_000_000; // 最小成交量500万USDT (LIGHT liquidity)
    private static final int MAX_RECOMMEND = 10;             // 最多推荐10个
    private static final int KLINE_LIMIT = 120;              // 取120根15m K线分析
    private static final double MIN_SCORE = 55;              // 最低评分门槛 (保守EV下界)

    /** 缓存最新推荐结果 */
    private final List<Recommendation> cachedRecommendations = Collections.synchronizedList(new ArrayList<>());

    /** 备选 API */
    private static final String[] FALLBACK_URLS = {
            "https://api.binance.com",
            "https://api1.binance.com",
            "https://api3.binance.com"
    };
    private volatile String activeBaseUrl;

    /** 获取最新推荐 */
    public List<Recommendation> getRecommendations() {
        return new ArrayList<>(cachedRecommendations);
    }

    // ==================== 核心扫描逻辑 ====================

    public void scanAndRecommend() {
        log.info("========== 开始 5 分钟推荐扫描 ==========");
        try {
            // Step A-B: 获取原始市场数据 (24hr ticker)
            List<Map<String, String>> topCoins = fetchTopVolumeCoins();
            if (topCoins.isEmpty()) {
                log.warn("无法获取市场数据，跳过本次扫描");
                return;
            }
            log.info("U0 全市场扫描: 获取到 {} 个USDT交易对 (Top{})", topCoins.size(), TOP_N);

            List<Recommendation> results = new ArrayList<>();

            for (Map<String, String> coin : topCoins) {
                String symbol = coin.get("symbol");
                try {
                    // 获取15m K线
                    List<KlineData> klines = fetchKlines(symbol, "15m", KLINE_LIMIT);
                    if (klines == null || klines.size() < 20) continue;

                    // === LIGHT 轻扫: 身份、完整性、流动性 ===
                    if (!lightScan(coin, klines)) continue;

                    // === MEDIUM 中扫: 结构、异动、压缩、启动 ===
                    MediumResult medium = mediumScan(klines, coin);
                    if (medium == null) continue;

                    // === DEEP 深扫: 量价、主动买入、市场状态 ===
                    DeepResult deep = deepScan(klines, medium);

                    // === T/O 捕捉前端 ===
                    int score = deep.totalScore;
                    if (score < MIN_SCORE) continue;

                    String direction = deep.direction;
                    String signal = score >= 80 ? "强信号" : score >= 65 ? "中等信号" : "弱信号";

                    Recommendation rec = Recommendation.builder()
                            .symbol(symbol)
                            .baseAsset(coin.getOrDefault("baseAsset", symbol.replace("USDT", "")))
                            .price(coin.getOrDefault("lastPrice", "0"))
                            .direction(direction)
                            .score(score)
                            .signal(signal)
                            .reason(deep.reason)
                            .volume(formatVolume(coin.getOrDefault("volume", "0")))
                            .change24h(coin.getOrDefault("priceChangePercent", "0"))
                            .recommendTime(deep.recommendTime)
                            .updateTime(DT_FMT.format(Instant.now()))
                            .build();

                    results.add(rec);
                } catch (Exception e) {
                    log.debug("分析 {} 异常: {}", symbol, e.getMessage());
                }
            }

            // 按评分排序，取前 MAX_RECOMMEND
            results.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
            if (results.size() > MAX_RECOMMEND) {
                results = results.subList(0, MAX_RECOMMEND);
            }

            cachedRecommendations.clear();
            cachedRecommendations.addAll(results);

            log.info("扫描完成: 推荐 {} 个币种", results.size());
            for (Recommendation r : results) {
                log.info("  {} | {} | 评分{} | {}", r.getSymbol(), r.getDirection(), r.getScore(), r.getReason());
            }
        } catch (Exception e) {
            log.error("推荐扫描失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 手动对单个币种评分 - 返回完整分析结果（包括评分<55的）
     */
    public Map<String, Object> scoreCoin(String symbol) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol.toUpperCase());
        result.put("baseAsset", symbol.toUpperCase().replace("USDT", ""));
        result.put("lightPass", false);
        result.put("mediumPass", false);
        result.put("mediumScore", 0);
        result.put("score", 0);
        result.put("direction", "-");
        result.put("signal", "无信号");
        result.put("reason", "");

        try {
            // 获取24hr ticker
            Map<String, String> coin = fetchSingleTicker(symbol);
            if (coin.isEmpty()) {
                result.put("reason", "无法获取行情数据");
                return result;
            }
            result.put("price", coin.getOrDefault("lastPrice", "0"));
            result.put("volume", formatVolume(coin.getOrDefault("volume", "0")));
            result.put("change24h", coin.getOrDefault("priceChangePercent", "0"));

            // 获取15m K线
            List<KlineData> klines = fetchKlines(symbol, "15m", KLINE_LIMIT);
            if (klines == null || klines.size() < 20) {
                result.put("reason", "K线数据不足（需>=20根）");
                return result;
            }

            // === LIGHT 轻扫 ===
            boolean lightOk = lightScan(coin, klines);
            result.put("lightPass", lightOk);
            if (!lightOk) {
                result.put("reason", "未通过轻扫（USDT交易对 + 流动性>=500万 + K线充足）");
                return result;
            }

            // === MEDIUM 中扫 ===
            MediumResult medium = mediumScan(klines, coin);
            result.put("mediumPass", medium != null);
            result.put("mediumScore", medium != null ? medium.mediumScore : 0);
            if (medium != null) {
                result.put("momentum", String.format("%.2f%%", medium.momentum));
                result.put("volumeRatio", String.format("%.2f", medium.volumeRatio));
                result.put("compression", String.format("%.4f", medium.compression));
                result.put("breakout", medium.breakout);
            }
            if (medium == null) {
                result.put("reason", "MEDIUM评分<20，未通过中扫");
                return result;
            }

            // === DEEP 深扫 ===
            DeepResult deep = deepScan(klines, medium);
            result.put("score", deep.totalScore);
            result.put("direction", deep.direction);
            result.put("reason", deep.reason);
            result.put("recommendTime", deep.recommendTime);

            String signal = deep.totalScore >= 80 ? "强信号" : deep.totalScore >= 65 ? "中等信号" : deep.totalScore >= 55 ? "弱信号" : "不推荐";
            result.put("signal", signal);

        } catch (Exception e) {
            log.error("手动评分 {} 异常: {}", symbol, e.getMessage(), e);
            result.put("reason", "评分异常: " + e.getMessage());
        }
        return result;
    }

    // ==================== LIGHT 轻扫 ====================

    private boolean lightScan(Map<String, String> coin, List<KlineData> klines) {
        // 身份验证: symbol 格式正确
        String symbol = coin.get("symbol");
        if (symbol == null || !symbol.endsWith("USDT")) return false;

        // 流动性验证: 24h成交量 >= 500万USDT
        double volume = parseDoubleSafe(coin.getOrDefault("volume", "0"));
        if (volume < MIN_VOLUME_USDT) return false;

        // 完整性: K线数量足够
        if (klines.size() < 20) return false;

        return true;
    }

    // ==================== MEDIUM 中扫 ====================

    private static class MediumResult {
        double momentum;       // 动量方向 (+做多倾向, -做空倾向)
        double volumeRatio;    // 当前成交量/均量
        double compression;    // 压缩程度 (0=极度压缩, 1=发散)
        boolean breakout;      // 是否启动突破
        int mediumScore;
    }

    private MediumResult mediumScan(List<KlineData> klines, Map<String, String> coin) {
        int n = klines.size();
        if (n < 20) return null;

        MediumResult r = new MediumResult();

        // 1. 结构分析: 短周期MA vs 长周期MA
        double ma7 = calcMA(klines, n - 7, n, "close");
        double ma25 = calcMA(klines, n - 25, n, "close");
        double ma50 = calcMA(klines, Math.max(0, n - 50), n, "close");
        r.momentum = (ma7 - ma25) / ma25 * 100; // 动量百分比

        // 2. 异动检测: 当前成交量 vs 20周期均量
        double avgVol = 0;
        int volCount = 0;
        for (int i = Math.max(0, n - 21); i < n - 1; i++) {
            avgVol += parseDoubleSafe(klines.get(i).getVolume());
            volCount++;
        }
        avgVol = volCount > 0 ? avgVol / volCount : 1;
        double curVol = parseDoubleSafe(klines.get(n - 1).getVolume());
        r.volumeRatio = curVol / Math.max(avgVol, 1);

        // 3. 压缩检测: 布林带宽 (价格波动幅度/均值)
        double avgClose = calcMA(klines, n - 20, n, "close");
        double stdDev = calcStd(klines, n - 20, n, "close", avgClose);
        r.compression = avgClose > 0 ? (stdDev / avgClose) : 0.02; // 越小越压缩

        // 4. 启动检测: 最近3根K线持续同向
        KlineData k1 = klines.get(n - 1);
        KlineData k2 = klines.get(n - 2);
        KlineData k3 = klines.get(n - 3);
        boolean upTrend = parseDoubleSafe(k1.getClose()) > parseDoubleSafe(k2.getClose())
                && parseDoubleSafe(k2.getClose()) > parseDoubleSafe(k3.getClose());
        boolean downTrend = parseDoubleSafe(k1.getClose()) < parseDoubleSafe(k2.getClose())
                && parseDoubleSafe(k2.getClose()) < parseDoubleSafe(k3.getClose());
        r.breakout = upTrend || downTrend;

        // MEDIUM 评分
        r.mediumScore = 0;
        if (Math.abs(r.momentum) > 0.5) r.mediumScore += 15;     // 有明确趋势
        if (r.volumeRatio > 1.5) r.mediumScore += 20;             // 放量
        if (r.compression < 0.015 && r.compression > 0.001) r.mediumScore += 15; // 压缩待突破
        if (r.breakout) r.mediumScore += 15;                       // 已启动

        if (r.mediumScore < 20) return null; // MEDIUM 不通过直接拒绝
        return r;
    }

    // ==================== DEEP 深扫 ====================

    private static class DeepResult {
        String direction;    // LONG / SHORT
        int totalScore;      // 综合评分 0-100
        String reason;       // 推荐理由
        String recommendTime; // 推荐购买时间
    }

    private DeepResult deepScan(List<KlineData> klines, MediumResult medium) {
        int n = klines.size();
        DeepResult r = new DeepResult();

        // 量价分析: 主动买入占比
        double totalTakerBuy = 0, totalVolume = 0;
        for (int i = Math.max(0, n - 10); i < n; i++) {
            totalTakerBuy += parseDoubleSafe(klines.get(i).getTakerBuyQuoteAssetVolume());
            totalVolume += parseDoubleSafe(klines.get(i).getQuoteAssetVolume());
        }
        double buyRatio = totalVolume > 0 ? totalTakerBuy / totalVolume : 0.5; // 主动买入比

        // 市场状态: 波动性、趋势一致性
        double ma7 = calcMA(klines, n - 7, n, "close");
        double ma25 = calcMA(klines, n - 25, n, "close");
        double latestClose = parseDoubleSafe(klines.get(n - 1).getClose());

        // 方向判断
        boolean bullishMomentum = medium.momentum > 0.2;
        boolean bearishMomentum = medium.momentum < -0.2;
        boolean highBuyPressure = buyRatio > 0.55;  // 主动买入占优
        boolean highSellPressure = buyRatio < 0.45; // 主动卖出占优

        StringBuilder reasonBuilder = new StringBuilder();
        int score = 0;

        if (bullishMomentum) {
            r.direction = "LONG";
            score += 25; // 动量基础分
            reasonBuilder.append("多头排列");

            if (highBuyPressure) {
                score += 20;
                reasonBuilder.append("，主动买入放量");
            }
            if (medium.volumeRatio > 2.0) {
                score += 15;
                reasonBuilder.append("，巨量突破");
            } else if (medium.volumeRatio > 1.3) {
                score += 10;
                reasonBuilder.append("，温和放量");
            }
            if (medium.compression < 0.012) {
                score += 15;
                reasonBuilder.append("，压縮启动");
            }
            if (latestClose > ma25 && ma7 > ma25) {
                score += 10;
                reasonBuilder.append("，趋势延续");
            }
        } else if (bearishMomentum) {
            r.direction = "SHORT";
            score += 25;
            reasonBuilder.append("空头排列");

            if (highSellPressure) {
                score += 20;
                reasonBuilder.append("，主动卖出放量");
            }
            if (medium.volumeRatio > 2.0) {
                score += 15;
                reasonBuilder.append("，恐慌放量");
            } else if (medium.volumeRatio > 1.3) {
                score += 10;
                reasonBuilder.append("，温和放量");
            }
            if (latestClose < ma25 && ma7 < ma25) {
                score += 10;
                reasonBuilder.append("，下跌延续");
            }
        } else {
            // 无明显方向，默认不做推荐
            r.direction = buyRatio > 0.5 ? "LONG" : "SHORT";
            score += 10;
            reasonBuilder.append("盘整待变");

            if (medium.compression < 0.01 && medium.breakout) {
                score += 20;
                reasonBuilder.append("，极压待爆");
            }
            if (buyRatio > 0.6) {
                score += 10;
                reasonBuilder.append("，主力吸筹");
            } else if (buyRatio < 0.4) {
                score += 10;
                reasonBuilder.append("，主力出货");
            }
        }

        r.totalScore = Math.min(score, 100);
        r.reason = reasonBuilder.toString();

        // 推荐购买时间
        r.recommendTime = calcRecommendTime(medium, buyRatio, latestClose, ma7, ma25);

        return r;
    }

    /** 根据分析结果计算推荐购买时间 */
    private String calcRecommendTime(MediumResult medium, double buyRatio,
                                      double latestClose, double ma7, double ma25) {
        boolean strongMomentum = Math.abs(medium.momentum) > 0.5;
        boolean compression = medium.compression < 0.012 && medium.compression > 0.001;
        boolean hugeVolume = medium.volumeRatio > 2.0;
        boolean warmVolume = medium.volumeRatio > 1.3;
        boolean alreadyBrokeOut = medium.breakout;
        boolean aboveMA7 = latestClose > ma7;
        boolean trendAlign = aboveMA7 && ma7 > ma25; // 多头排列

        if (strongMomentum && alreadyBrokeOut && hugeVolume && buyRatio > 0.55) {
            return "立即入场";
        }
        if (strongMomentum && warmVolume && aboveMA7) {
            return "当前即可";
        }
        if (compression && alreadyBrokeOut) {
            return "突破即追";
        }
        if (compression && !alreadyBrokeOut) {
            return "等待突破确认";
        }
        if (trendAlign && warmVolume) {
            return "回调至MA7入场";
        }
        if (strongMomentum && !warmVolume) {
            return "等待放量";
        }
        if (aboveMA7 && buyRatio > 0.5) {
            return "回踩支撑入场";
        }
        if (!aboveMA7 && compression) {
            return "底部吸筹等待";
        }
        return "轻仓试单";
    }

    // ==================== 数据获取 ====================

    /** 获取成交量前N的USDT交易对 */
    private List<Map<String, String>> fetchTopVolumeCoins() {
        List<Map<String, String>> list = new ArrayList<>();
        try {
            String body = callBinanceApi("/api/v3/ticker/24hr");
            if (body == null) return list;
            JsonNode root = objectMapper.readTree(body);

            for (JsonNode item : root) {
                String symbol = item.get("symbol").asText();
                if (!symbol.endsWith("USDT")) continue;

                Map<String, String> coin = new HashMap<>();
                coin.put("symbol", symbol);
                coin.put("baseAsset", symbol.replace("USDT", ""));
                coin.put("lastPrice", item.get("lastPrice").asText());
                coin.put("volume", item.get("quoteVolume").asText()); // USDT成交量
                coin.put("priceChangePercent", item.get("priceChangePercent").asText());
                list.add(coin);
            }

            // 按成交量降序排列
            list.sort((a, b) -> {
                double va = parseDoubleSafe(a.getOrDefault("volume", "0"));
                double vb = parseDoubleSafe(b.getOrDefault("volume", "0"));
                return Double.compare(vb, va);
            });

            if (list.size() > TOP_N) {
                list = list.subList(0, TOP_N);
            }
        } catch (Exception e) {
            log.error("获取24hr行情失败: {}", e.getMessage(), e);
        }
        return list;
    }

    /** 获取单个交易对的24hr行情 */
    private Map<String, String> fetchSingleTicker(String symbol) {
        Map<String, String> coin = new HashMap<>();
        try {
            String body = callBinanceApi("/api/v3/ticker/24hr?symbol=" + symbol.toUpperCase());
            if (body == null) return coin;
            JsonNode item = objectMapper.readTree(body);
            coin.put("symbol", item.get("symbol").asText());
            coin.put("baseAsset", item.get("symbol").asText().replace("USDT", ""));
            coin.put("lastPrice", item.get("lastPrice").asText());
            coin.put("volume", item.get("quoteVolume").asText());
            coin.put("priceChangePercent", item.get("priceChangePercent").asText());
        } catch (Exception e) {
            log.debug("获取 {} 24hr行情失败: {}", symbol, e.getMessage());
        }
        return coin;
    }

    /** 获取K线数据 */
    private List<KlineData> fetchKlines(String symbol, String interval, int limit) {
        List<KlineData> result = new ArrayList<>();
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
        } catch (Exception e) {
            log.debug("获取 {} K线失败: {}", symbol, e.getMessage());
        }
        return result;
    }

    // ==================== 工具方法 ====================

    private String callBinanceApi(String path) {
        List<String> urls = new ArrayList<>();
        if (activeBaseUrl != null && !activeBaseUrl.equals(binanceProperties.getBaseUrl())) {
            urls.add(activeBaseUrl);
        }
        urls.add(binanceProperties.getBaseUrl());
        for (String fb : FALLBACK_URLS) {
            if (!urls.contains(fb)) urls.add(fb);
        }

        for (String baseUrl : urls) {
            try {
                String fullUrl = baseUrl + path;
                var resp = restTemplate.getForEntity(fullUrl, String.class);
                if (resp.getStatusCode() == HttpStatus.OK) {
                    if (!baseUrl.equals(activeBaseUrl)) {
                        activeBaseUrl = baseUrl;
                    }
                    return resp.getBody();
                }
            } catch (HttpClientErrorException | ResourceAccessException e) {
                // try next
            } catch (Exception e) {
                // try next
            }
        }
        return null;
    }

    private double calcMA(List<KlineData> klines, int from, int to, String field) {
        from = Math.max(0, from);
        double sum = 0;
        int count = 0;
        for (int i = from; i < to && i < klines.size(); i++) {
            sum += getFieldValue(klines.get(i), field);
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private double calcStd(List<KlineData> klines, int from, int to, String field, double mean) {
        from = Math.max(0, from);
        double sumSq = 0;
        int count = 0;
        for (int i = from; i < to && i < klines.size(); i++) {
            double diff = getFieldValue(klines.get(i), field) - mean;
            sumSq += diff * diff;
            count++;
        }
        return count > 1 ? Math.sqrt(sumSq / (count - 1)) : 0;
    }

    private double getFieldValue(KlineData k, String field) {
        return switch (field) {
            case "close" -> parseDoubleSafe(k.getClose());
            case "open" -> parseDoubleSafe(k.getOpen());
            case "high" -> parseDoubleSafe(k.getHigh());
            case "low" -> parseDoubleSafe(k.getLow());
            case "volume" -> parseDoubleSafe(k.getVolume());
            default -> 0;
        };
    }

    private double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    private String formatVolume(String vol) {
        try {
            double v = Double.parseDouble(vol);
            if (v >= 1_000_000_000) return String.format("%.2fB", v / 1_000_000_000);
            if (v >= 1_000_000) return String.format("%.2fM", v / 1_000_000);
            if (v >= 1_000) return String.format("%.2fK", v / 1_000);
            return String.format("%.0f", v);
        } catch (Exception e) {
            return vol;
        }
    }
}
