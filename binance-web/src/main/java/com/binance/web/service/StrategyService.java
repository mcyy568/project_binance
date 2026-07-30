package com.binance.web.service;

import com.binance.web.config.BinanceProperties;
import com.binance.web.entity.*;
import com.binance.web.mapper.StrategyMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 策略交易引擎 - 自动买入卖出合约的量化策略系统
 *
 * 两种策略模式：
 * 1. 早段动态 (EARLY): 24H波动率 < 阈值 → 止损80%，利润保护120%
 * 2. 高位保护 (HIGH):  24H波动率 >= 阈值 → 止损50%，利润保护20%
 *
 * 执行规则：
 * - 入场: 下一根完整1分钟K线确认
 * - 同币种每日仅取第一个信号
 * - 同币种未平仓时禁止重复开仓
 */
@Slf4j
@Service
public class StrategyService {

    @Autowired
    private StrategyMapper strategyMapper;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private BinanceProperties binanceProperties;

    @Autowired
    private BinanceTradeService binanceTradeService;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    /** 测试网账户资金（初始 5000 USDT） */
    private double accountCapital = 5000.0;

    /** 默认监控币种 */
    private static final List<String> DEFAULT_WATCHLIST = List.of(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT", "BNBUSDT",
            "XRPUSDT", "ADAUSDT", "AVAXUSDT", "DOTUSDT", "LINKUSDT",
            "SUIUSDT", "PEPEUSDT", "SHIBUSDT", "APTUSDT", "ARBUSDT",
            "OPUSDT", "FILUSDT", "ATOMUSDT", "NEARUSDT", "WIFUSDT"
    );

    private List<String> watchlist = new ArrayList<>(DEFAULT_WATCHLIST);
    private List<String> fallbackUrls = List.of(
            "https://api.binance.com",
            "https://api1.binance.com",
            "https://api2.binance.com",
            "https://api3.binance.com"
    );
    private String activeBaseUrl;

    // ==================== 核心调度入口 ====================

    /**
     * 每1分钟执行一次：信号检测 + 持仓管理
     */
    public void tick() {
        try {
            // 0. 同步测试网账户余额
            syncAccountBalance();

            // 1. 更新所有持仓的浮动盈亏
            updateAllPositions();

            // 2. 检查所有持仓的退出条件
            checkExitConditions();

            // 3. 如果没有持仓或未满，扫描新信号
            int openCount = strategyMapper.countOpenPositions();
            StrategyConfig config = strategyMapper.findByMode("EARLY");
            int maxPos = config != null && config.getMaxPositions() != null ? config.getMaxPositions() : 1;
            if (openCount < maxPos) {
                scanForSignals();
            }
        } catch (Exception e) {
            log.error("策略引擎 tick 异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 同步 Binance 测试网账户余额到 accountCapital
     */
    private void syncAccountBalance() {
        try {
            double balance = binanceTradeService.getUsdtBalance();
            if (balance > 0 && Math.abs(balance - accountCapital) > 0.01) {
                log.info("同步测试网余额: {} → {} USDT", String.format("%.2f", accountCapital), String.format("%.2f", balance));
                accountCapital = balance;
            }
        } catch (Exception e) {
            log.debug("同步余额失败: {}", e.getMessage());
        }
    }

    // ==================== 信号扫描 ====================

    /**
     * 扫描监控列表中的币种，检测入场信号
     */
    public void scanForSignals() {
        log.info("开始扫描策略信号，监控 {} 个币种", watchlist.size());
        int signalCount = 0;
        int acceptedCount = 0;

        for (String symbol : watchlist) {
            try {
                // 检查是否已有持仓
                if (strategyMapper.countOpenBySymbol(symbol) > 0) {
                    continue;
                }

                // 获取24h波动率和1m K线
                Map<String, String> ticker = fetchTicker(symbol);
                if (ticker.isEmpty()) continue;

                double volatility24h = parseDoubleSafe(ticker.getOrDefault("priceChangePercent", "0"));
                volatility24h = Math.abs(volatility24h);

                List<KlineData> klines = fetchKlines(symbol, "1m", 20);
                if (klines.size() < 20) continue;

                // 判断策略模式
                StrategyConfig config = getStrategyConfig(volatility24h);
                if (config == null || !config.getEnabled()) continue;

                // 检测信号
                SignalResult signal = detectSignal(klines, ticker);
                if (signal == null) continue;

                signalCount++;
                String baseAsset = symbol.replace("USDT", "");

                // 检查同币种今日是否已有信号
                if (strategyMapper.countTodayAcceptedBySymbol(symbol) > 0) {
                    saveSkippedSignal(symbol, baseAsset, signal, volatility24h, config.getMode(),
                            "same_symbol_cooldown: 同币种今日已开仓");
                    continue;
                }

                // 接受信号 → 开仓
                StrategyPosition pos = openPosition(symbol, baseAsset, signal, config);
                if (pos != null) {
                    acceptedCount++;
                    StrategySignal sig = buildSignal(symbol, baseAsset, signal, volatility24h, config.getMode(), "ACCEPTED", null);
                    sig.setProcessTime(LocalDateTime.now());
                    strategyMapper.insertSignal(sig);
                    log.info("策略信号接受: {} {} 价格={} 模式={} score={}",
                            symbol, signal.direction, signal.price, config.getMode(), signal.score);
                }

            } catch (Exception e) {
                log.debug("扫描 {} 异常: {}", symbol, e.getMessage());
            }
        }
        log.info("信号扫描完成: 检测到 {} 个信号, 接受 {} 个", signalCount, acceptedCount);
    }

    // ==================== 持仓管理 ====================

    /**
     * 更新所有持仓的当前价格和浮动盈亏
     */
    private void updateAllPositions() {
        List<StrategyPosition> positions = strategyMapper.findOpenPositions();
        // 一次性获取币安全部余额，避免每个持仓单独请求
        Map<String, Map<String, String>> binanceBalances = null;
        try {
            binanceBalances = binanceTradeService.getFullBalances();
        } catch (Exception e) {
            log.warn("获取币安账户余额失败: {}", e.getMessage());
        }

        for (StrategyPosition pos : positions) {
            try {
                Map<String, String> ticker = fetchTicker(pos.getSymbol());
                if (ticker.isEmpty()) continue;

                String currentPrice = ticker.getOrDefault("lastPrice", pos.getOpenPrice());
                pos.setCurrentPrice(currentPrice);

                double openP = Double.parseDouble(pos.getOpenPrice());
                double curP = Double.parseDouble(currentPrice);

                if ("SHORT".equals(pos.getDirection())) {
                    pos.setUnrealizedPnl((openP - curP) / openP * pos.getMargin());
                    pos.setPnlPct((openP - curP) / openP * 100);
                } else {
                    pos.setUnrealizedPnl((curP - openP) / openP * pos.getMargin());
                    pos.setPnlPct((curP - openP) / openP * 100);
                }

                // 更新最高浮盈
                if (pos.getUnrealizedPnl() > pos.getHighestPnl()) {
                    pos.setHighestPnl(pos.getUnrealizedPnl());
                }

                // 更新最大逆向
                if (pos.getUnrealizedPnl() < pos.getMaxAdverse()) {
                    pos.setMaxAdverse(pos.getUnrealizedPnl());
                }

                // 从币安实时同步实际持仓数量
                if (binanceBalances != null) {
                    Map<String, String> assetInfo = binanceBalances.get(pos.getBaseAsset());
                    if (assetInfo != null) {
                        pos.setExecutedQty(assetInfo.get("free"));
                    } else {
                        // 币安没有该资产，可能已被手动卖出
                        pos.setExecutedQty("0");
                    }
                }

                strategyMapper.updatePositionPnL(pos);
            } catch (Exception e) {
                log.debug("更新持仓 {} 异常: {}", pos.getSymbol(), e.getMessage());
            }
        }
    }

    /**
     * 检查退出条件：止损 / 利润保护回撤 / 动态退出
     */
    private void checkExitConditions() {
        List<StrategyPosition> positions = strategyMapper.findOpenPositions();
        for (StrategyPosition pos : positions) {
            try {
                StrategyConfig config = strategyMapper.findByMode(
                        pos.getStrategyName().contains("早段") ? "EARLY" : "HIGH");
                if (config == null) continue;

                String reason = null;

                // 1. 止损检查
                double lossPct = -(config.getStopLossPct());
                if (pos.getPnlPct() <= lossPct) {
                    reason = "原定止损触发 (" + String.format("%.1f", pos.getPnlPct()) + "%)";
                }

                // 2. 利润保护：达到profitProtectPct后，回撤drawdownExitPct退出
                if (reason == null && pos.getHighestPnl() >= pos.getMargin() * config.getProfitProtectPct() / 100) {
                    double drawdownFromPeak = pos.getHighestPnl() - pos.getUnrealizedPnl();
                    double drawdownPct = pos.getHighestPnl() > 0 ? drawdownFromPeak / pos.getHighestPnl() * 100 : 0;
                    if (drawdownFromPeak >= pos.getMargin() * config.getDrawdownExitPct() / 100) {
                        reason = String.format("盈利高点回撤%.0f%%保护 (峰值%.1fU 当前%.1fU)",
                                drawdownPct, pos.getHighestPnl(), pos.getUnrealizedPnl());
                    }
                }

                // 3. 8小时未启动利润保护退出
                if (reason == null) {
                    long hoursOpen = Duration.between(pos.getOpenTime(), LocalDateTime.now()).toHours();
                    if (hoursOpen >= 8 && pos.getHighestPnl() < pos.getMargin() * config.getProfitProtectPct() / 100) {
                        reason = "8小时未启动利润保护退出";
                    }
                }

                if (reason != null) {
                    closePosition(pos, reason, config);
                }

            } catch (Exception e) {
                log.debug("检查退出条件 {} 异常: {}", pos.getSymbol(), e.getMessage());
            }
        }
    }

    // ==================== 开仓 ====================

    private StrategyPosition openPosition(String symbol, String baseAsset, SignalResult signal, StrategyConfig config) {
        try {
            // 现货测试网仅支持做多
            if ("SHORT".equalsIgnoreCase(signal.direction)) {
                log.warn("现货测试网不支持做空，跳过 {} SHORT 开仓", symbol);
                return null;
            }

            double posSize = config.getPositionSize();
            double usdtBalance = binanceTradeService.getUsdtBalance();
            log.info("测试网余额: {} USDT, 计划开仓: {} USDT  {}", usdtBalance, posSize, symbol);
            if (usdtBalance < posSize) {
                log.warn("余额不足: {} < {} USDT, 跳过 {}", usdtBalance, posSize, symbol);
                return null;
            }

            // 执行真实市价买入
            Map<String, String> orderResult = binanceTradeService.marketBuy(symbol, posSize);
            if (orderResult == null) {
                log.error("Binance 下单失败: {} BUY", symbol);
                return null;
            }

            String orderIdStr = orderResult.get("orderId");
            String fillPrice = orderResult.get("avgPrice");
            String execQty = orderResult.get("executedQty");
            String orderStatus = orderResult.get("status");
            double openP = fillPrice != null ? Double.parseDouble(fillPrice) : Double.parseDouble(signal.price);

            log.info("Binance 开仓成功: {} @ {} 数量={} orderId={} status={}",
                    symbol, openP, execQty, orderIdStr, orderStatus);

            StrategyPosition pos = new StrategyPosition();
            pos.setSymbol(symbol);
            pos.setBaseAsset(baseAsset);
            pos.setStrategyName(config.getName());
            pos.setMargin(config.getPositionSize());
            pos.setLeverage(config.getLeverage());
            pos.setOpenPrice(String.format("%.6f", openP));
            pos.setCurrentPrice(String.format("%.6f", openP));
            pos.setDirection(signal.direction);
            pos.setStatus("OPEN");
            pos.setOpenTime(LocalDateTime.now());
            pos.setUnrealizedPnl(0.0);
            pos.setPnlPct(0.0);
            pos.setHighestPnl(0.0);
            pos.setMaxAdverse(0.0);
            pos.setOrderId(Long.parseLong(orderIdStr));
            pos.setExecutedQty(execQty);

            double stopLossPct = config.getStopLossPct() / 100.0;
            if ("LONG".equals(signal.direction)) {
                pos.setStopLossPrice(String.format("%.6f", openP * (1 - stopLossPct)));
                pos.setProfitProtectPrice(String.format("%.6f", openP * (1 + config.getProfitProtectPct() / 100.0)));
            } else {
                pos.setStopLossPrice(String.format("%.6f", openP * (1 + stopLossPct)));
                pos.setProfitProtectPrice(String.format("%.6f", openP * (1 - config.getProfitProtectPct() / 100.0)));
            }

            strategyMapper.insertPosition(pos);
            return pos;
        } catch (Exception e) {
            log.error("开仓失败 {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ==================== 平仓 ====================

    private void closePosition(StrategyPosition pos, String reason, StrategyConfig config) {
        try {
            // 获取最新价格
            Map<String, String> ticker = fetchTicker(pos.getSymbol());
            String closePrice = ticker.isEmpty() ? pos.getCurrentPrice() : ticker.getOrDefault("lastPrice", pos.getCurrentPrice());

            double openP = Double.parseDouble(pos.getOpenPrice());
            double closeP = Double.parseDouble(closePrice);
            double finalPnl;

            if ("SHORT".equals(pos.getDirection())) {
                finalPnl = (openP - closeP) / openP * pos.getMargin();
            } else {
                finalPnl = (closeP - openP) / openP * pos.getMargin();
            }

            // 执行真实市价卖出（LONG 仓位）
            Long closeOrderId = null;
            if ("LONG".equalsIgnoreCase(pos.getDirection())) {
                // 从币安实时获取该币种可用余额，不依赖 DB 缓存的 executedQty
                double actualBalance = binanceTradeService.getAssetBalance(pos.getBaseAsset());
                log.info("币安实时 {} 可用余额: {}，DB 记录: {}",
                        pos.getBaseAsset(), actualBalance, pos.getExecutedQty());

                if (actualBalance <= 0) {
                    log.warn("币安 {} 可用余额为 0，可能已被手动卖出，仅关闭本地持仓记录", pos.getSymbol());
                } else {
                    try {
                        Map<String, String> sellResult = binanceTradeService.marketSell(pos.getSymbol(), actualBalance);
                        if (sellResult != null) {
                            closeOrderId = Long.parseLong(sellResult.get("orderId"));
                            // 更新实际成交数量
                            pos.setExecutedQty(sellResult.get("executedQty"));
                            // 使用实际成交价
                            String sellPrice = sellResult.get("avgPrice");
                            if (sellPrice != null && !"0".equals(sellPrice)) {
                                closePrice = sellPrice;
                                closeP = Double.parseDouble(closePrice);
                                finalPnl = (closeP - openP) / openP * pos.getMargin();
                            }
                            log.info("Binance 平仓成功: {} SELL orderId={} 数量={} 价格={}",
                                    pos.getSymbol(), closeOrderId, sellResult.get("executedQty"), closePrice);
                        } else {
                            log.error("Binance 平仓被拒绝 {}: API 返回错误，持仓保持不动请手动处理", pos.getSymbol());
                            return;
                        }
                    } catch (Exception ex) {
                        log.error("Binance 卖出异常 {}: {}，持仓保持不动请手动处理", pos.getSymbol(), ex.getMessage());
                        return;
                    }
                }
            }

            long holdMinutes = Duration.between(pos.getOpenTime(), LocalDateTime.now()).toMinutes();

            StrategyTrade trade = new StrategyTrade();
            trade.setSymbol(pos.getSymbol());
            trade.setBaseAsset(pos.getBaseAsset());
            trade.setStrategyName(pos.getStrategyName());
            trade.setDirection(pos.getDirection());
            trade.setMargin(pos.getMargin());
            trade.setExecutedQty(pos.getExecutedQty());
            trade.setLeverage(pos.getLeverage());
            trade.setOpenPrice(pos.getOpenPrice());
            trade.setClosePrice(closePrice);
            trade.setPnl(finalPnl);
            trade.setPnlPct(finalPnl / pos.getMargin() * 100);
            trade.setHighestPnl(pos.getHighestPnl());
            trade.setMaxAdverse(pos.getMaxAdverse());
            trade.setHoldDuration((int) holdMinutes);
            trade.setCloseReason(reason);
            trade.setOpenTime(pos.getOpenTime());
            trade.setCloseTime(LocalDateTime.now());

            strategyMapper.insertTrade(trade);
            strategyMapper.closePosition(pos.getId(), closeOrderId);

            // 更新账户资金
            accountCapital += finalPnl;

            log.info("平仓: {} {} {} 盈亏={:.2f}U ({:.1f}%) 原因={}",
                    pos.getSymbol(), pos.getDirection(), pos.getStrategyName(), finalPnl,
                    finalPnl / pos.getMargin() * 100, reason);

        } catch (Exception e) {
            log.error("平仓失败 {}: {}", pos.getSymbol(), e.getMessage());
        }
    }

    // ==================== 信号检测 ====================

    /**
     * 基于1分钟K线检测入场信号
     * 逻辑: 基于价格动量 + 成交量异动
     */
    private SignalResult detectSignal(List<KlineData> klines, Map<String, String> ticker) {
        if (klines.size() < 10) return null;

        int n = klines.size();
        // 最近3根K线的表现
        List<KlineData> recent = klines.subList(n - 3, n);
        List<KlineData> earlier = klines.subList(n - 10, n - 3);

        // 计算早期均值和近期均值
        double earlyClose = calcAvgClose(earlier);
        double recentClose = calcAvgClose(recent);

        double momentum = (recentClose - earlyClose) / earlyClose * 100;

        // 计算成交量比值
        double recentVol = calcAvgVol(recent);
        double earlyVol = calcAvgVol(earlier);
        earlyVol = earlyVol > 0 ? earlyVol : 1;
        double volRatio = recentVol / earlyVol;

        // 信号判断阈值
        if (Math.abs(momentum) < 0.15 || volRatio < 1.2) {
            return null; // 无明显信号
        }

        SignalResult result = new SignalResult();
        result.price = ticker.getOrDefault("lastPrice", String.valueOf(recentClose));
        result.direction = momentum > 0 ? "LONG" : "SHORT";
        result.momentum = momentum;

        // 评分：动量绝对值 + 成交量加权
        double absMomentum = Math.abs(momentum);
        result.score = (int) Math.min(100, absMomentum * 100 + (volRatio - 1) * 30);

        return result;
    }

    private double calcAvgClose(List<KlineData> klines) {
        return klines.stream()
                .mapToDouble(k -> parseDoubleSafe(k.getClose()))
                .average().orElse(0);
    }

    private double calcAvgVol(List<KlineData> klines) {
        return klines.stream()
                .mapToDouble(k -> parseDoubleSafe(k.getQuoteAssetVolume()))
                .average().orElse(0);
    }

    // ==================== 辅助方法 ====================

    private StrategyConfig getStrategyConfig(double volatility24h) {
        StrategyConfig config = strategyMapper.findByMode("EARLY");
        if (config == null || volatility24h >= config.getVolatilityThreshold()) {
            config = strategyMapper.findByMode("HIGH");
        }
        return config;
    }

    private SignalResult createSignalFromKlines(List<KlineData> klines, Map<String, String> ticker) {
        return detectSignal(klines, ticker);
    }

    private void saveSkippedSignal(String symbol, String baseAsset, SignalResult signal,
                                    double volatility24h, String mode, String reason) {
        StrategySignal sig = buildSignal(symbol, baseAsset, signal, volatility24h, mode, "SKIPPED", reason);
        sig.setProcessTime(LocalDateTime.now());
        strategyMapper.insertSignal(sig);
    }

    private StrategySignal buildSignal(String symbol, String baseAsset, SignalResult signal,
                                        double volatility24h, String mode, String status, String skipReason) {
        StrategySignal sig = new StrategySignal();
        sig.setSymbol(symbol);
        sig.setBaseAsset(baseAsset);
        sig.setDirection(signal.direction);
        sig.setVolatility24h(volatility24h);
        sig.setPrice(signal.price);
        sig.setMode(mode);
        sig.setScore(signal.score);
        sig.setStatus(status);
        sig.setSkipReason(skipReason);
        sig.setDetectTime(LocalDateTime.now());
        return sig;
    }

    // ==================== API 查询 ====================

    public Map<String, Object> getOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();

        int totalSignals = strategyMapper.countTotalSignals();
        int totalTrades = strategyMapper.countTotalTrades();
        int openPositions = strategyMapper.countOpenPositions();
        int winTrades = strategyMapper.countWinTrades();
        Double totalPnl = strategyMapper.sumTotalPnl();
        Double maxWin = strategyMapper.maxSingleWin();
        Double maxLoss = strategyMapper.minSingleLoss();
        Double avgPnl = strategyMapper.avgSinglePnl();
        Double maxDrawdown = strategyMapper.maxDrawdown();

        overview.put("totalSignals", totalSignals);
        overview.put("openCount", openPositions);
        overview.put("totalTrades", totalTrades);
        overview.put("accountCapital", accountCapital);
        overview.put("totalPnl", totalPnl != null ? totalPnl : 0);
        overview.put("netProfit", totalPnl != null ? totalPnl : 0);
        overview.put("winRate", totalTrades > 0 ? Math.round(winTrades * 1000.0 / totalTrades) / 10.0 : 0);
        overview.put("stopLossRate", totalTrades > 0 ?
                Math.round((totalTrades - winTrades) * 1000.0 / totalTrades) / 10.0 : 0);
        overview.put("closedOpen", totalTrades + " / " + openPositions);
        overview.put("avgPnl", avgPnl != null ? avgPnl : 0);
        overview.put("maxWin", maxWin != null ? maxWin : 0);
        overview.put("maxLoss", maxLoss != null ? maxLoss : 0);
        overview.put("maxDrawdown", maxDrawdown != null ? maxDrawdown : 0);

        return overview;
    }

    public List<StrategyPosition> getOpenPositions() {
        List<StrategyPosition> positions = strategyMapper.findOpenPositions();
        // 用币安实时余额同步所有持仓数量
        try {
            Map<String, Map<String, String>> binanceBalances = binanceTradeService.getFullBalances();
            for (StrategyPosition pos : positions) {
                Map<String, String> assetInfo = binanceBalances.get(pos.getBaseAsset());
                if (assetInfo != null) {
                    pos.setExecutedQty(assetInfo.get("free"));
                } else {
                    pos.setExecutedQty("0");
                }
            }
        } catch (Exception e) {
            log.warn("获取币安实时余额失败: {}", e.getMessage());
        }
        return positions;
    }

    public List<StrategyTrade> getRecentTrades(String filter) {
        if ("win".equals(filter)) return strategyMapper.findWinTrades();
        if ("loss".equals(filter)) return strategyMapper.findLossTrades();
        return strategyMapper.findRecentTrades();
    }

    public List<StrategySignal> getRecentSignals() {
        return strategyMapper.findRecentSignals();
    }

    public List<StrategyConfig> getConfigs() {
        return strategyMapper.findAllConfigs();
    }

    public StrategyConfig updateConfig(StrategyConfig config) {
        strategyMapper.updateConfig(config);
        return strategyMapper.findByMode(config.getMode());
    }

    public List<Map<String, Object>> getDailyStats() {
        List<StrategyMapper.DailyStat> stats = strategyMapper.findDailyStats();
        List<Map<String, Object>> result = new ArrayList<>();
        for (StrategyMapper.DailyStat s : stats) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("date", s.date);
            map.put("count", s.count);
            map.put("pnl", s.pnl);
            map.put("winRate", s.winRate);
            map.put("stopLossRate", s.stopLossRate);
            result.add(map);
        }
        return result;
    }

    public List<String> getWatchlist() {
        return watchlist;
    }

    public void setWatchlist(List<String> list) {
        this.watchlist = list;
    }

    /**
     * 手动平仓
     */
    public void manualClose(Long positionId, String reason) {
        List<StrategyPosition> positions = strategyMapper.findOpenPositions();
        for (StrategyPosition pos : positions) {
            if (pos.getId().equals(positionId)) {
                StrategyConfig config = strategyMapper.findByMode(
                        pos.getStrategyName().contains("早段") ? "EARLY" : "HIGH");
                if (config == null) break;
                closePosition(pos, reason != null ? reason : "手动平仓", config);
                break;
            }
        }
    }

    // ==================== Binance API 调用 ====================

    private Map<String, String> fetchTicker(String symbol) {
        Map<String, String> coin = new HashMap<>();
        try {
            String body = callBinanceApi("/api/v3/ticker/24hr?symbol=" + symbol.toUpperCase());
            if (body == null) return coin;
            JsonNode item = objectMapper.readTree(body);
            coin.put("symbol", item.get("symbol").asText());
            coin.put("lastPrice", item.get("lastPrice").asText());
            coin.put("volume", item.get("quoteVolume").asText());
            coin.put("priceChangePercent", item.get("priceChangePercent").asText());
        } catch (Exception e) {
            log.debug("获取 {} 行情失败: {}", symbol, e.getMessage());
        }
        return coin;
    }

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

    private String callBinanceApi(String path) {
        List<String> urls = new ArrayList<>();
        if (activeBaseUrl != null && !activeBaseUrl.equals(binanceProperties.getBaseUrl())) {
            urls.add(activeBaseUrl);
        }
        urls.add(binanceProperties.getBaseUrl());
        for (String fb : fallbackUrls) {
            if (!urls.contains(fb)) urls.add(fb);
        }
        for (String baseUrl : urls) {
            try {
                String fullUrl = baseUrl + path;
                var resp = restTemplate.getForEntity(fullUrl, String.class);
                if (resp.getStatusCode() == HttpStatus.OK) {
                    if (!baseUrl.equals(activeBaseUrl)) activeBaseUrl = baseUrl;
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

    private double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    // ==================== 内部类 ====================

    static class SignalResult {
        String price;
        String direction;
        double momentum;
        int score;
    }
}
