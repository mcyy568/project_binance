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
import java.time.LocalDate;
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

    @Autowired
    private ScoringService scoringService;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    /** 测试网账户资金（初始 5000 USDT） */
    private double accountCapital = 5000.0;

    /** 默认监控币种
     *  分两层：主流币（稳定）+ 高波动币（需额外风控）
     */
    private static final List<String> DEFAULT_WATCHLIST = List.of(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT", "BNBUSDT",
            "XRPUSDT", "ADAUSDT", "AVAXUSDT", "DOTUSDT", "LINKUSDT",
            "SUIUSDT", "PEPEUSDT", "SHIBUSDT", "APTUSDT", "ARBUSDT",
            "OPUSDT", "FILUSDT", "ATOMUSDT", "NEARUSDT", "WIFUSDT"
    );

    /** 高波动币种（meme/小市值）：需要更严格的过滤条件 */
    private static final Set<String> HIGH_VOLATILITY_SYMBOLS = Set.of(
            "PEPEUSDT", "SHIBUSDT", "WIFUSDT", "BONKUSDT", "FLOKIUSDT", "DOGEUSDT"
    );

    /** 大盘BTC锚定：不随山寨币一起跌 */
    private static final String BTC_SYMBOL = "BTCUSDT";

    /** 风控参数 */
    private static final double DAILY_LOSS_LIMIT_PCT = -5.0;   // 日亏损超5%停止交易
    private static final double BTC_BEARISH_THRESHOLD = -2.0;  // BTC 15分钟跌超2%视为大盘走弱
    private static final double MIN_24H_VOLUME_USDT = 10_000_000; // 最小24h交易量(10M USDT)
    private static final int COOLDOWN_MINUTES = 5;              // 同方向开仓冷却时间
    private static final int MAX_DAILY_TRADES = 8;               // 每日最大交易笔数
    private static final int MAX_HOLD_HOURS = 4;                 // 最大持仓时间（从8h降到4h）

    private List<String> watchlist = new ArrayList<>(DEFAULT_WATCHLIST);
    private List<String> fallbackUrls = List.of(
            "https://api.binance.com",
            "https://api1.binance.com",
            "https://api2.binance.com",
            "https://api3.binance.com"
    );
    private String activeBaseUrl;

    // 风控状态
    private Boolean btcMarketBearish = null;       // BTC大盘是否走弱（缓存1分钟）
    private long lastBtcCheckTime = 0;
    private LocalDateTime lastTradeCloseTime = null; // 上次平仓时间（冷却用）
    private double todayTotalPnl = 0;               // 今日累计盈亏

    // ==================== 核心调度入口 ====================

    /**
     * 每1分钟执行一次：信号检测 + 持仓管理（复盘优化版）
     */
    public void tick() {
        try {
            // 0. 重置每日统计
            resetDailyIfNeeded();

            // 1. 同步测试网账户余额
            syncAccountBalance();

            // 2. 检查大盘风控：BTC是否走弱
            if (!checkBtcMarketHealthy()) {
                log.info("大盘风控：BTC走弱或波动异常，本轮跳过开仓扫描");
                // 但仍然检查持仓退出
                updateAllPositions();
                checkExitConditions();
                return;
            }

            // 3. 日亏损限制检查
            if (todayTotalPnl < accountCapital * DAILY_LOSS_LIMIT_PCT / 100) {
                log.info("风控触发：今日已亏损 {:.2f} USDT ({:.1f}%)，停止新开仓",
                        todayTotalPnl, todayTotalPnl / accountCapital * 100);
                updateAllPositions();
                checkExitConditions();
                return;
            }

            // 4. 更新所有持仓的浮动盈亏
            updateAllPositions();

            // 5. 检查所有持仓的退出条件
            checkExitConditions();

            // 6. 如果超过每日最大交易数，不再开仓
            int todayTrades = countTodayTrades();
            if (todayTrades >= MAX_DAILY_TRADES) {
                log.info("今日已交易 {} 笔，已达上限 {}，停止新开仓", todayTrades, MAX_DAILY_TRADES);
                return;
            }

            // 7. 检查冷却时间
            if (lastTradeCloseTime != null &&
                    Duration.between(lastTradeCloseTime, LocalDateTime.now()).toMinutes() < COOLDOWN_MINUTES) {
                log.debug("冷却中，距离上次平仓不足 {} 分钟", COOLDOWN_MINUTES);
                return;
            }

            // 8. 如果没有持仓或未满，扫描新信号
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

    /** 每日重置 */
    private void resetDailyIfNeeded() {
        LocalDate today = LocalDate.now();
        // 用静态变量记录上次重置日期
        // 简化实现：每次tick检查是否新的一天
        LocalDate lastReset = LocalDate.from(
                lastTradeCloseTime != null && lastTradeCloseTime.toLocalDate().equals(today)
                        ? lastTradeCloseTime.toLocalDate() : today.minusDays(1));
        if (!today.equals(lastReset)) {
            todayTotalPnl = 0;
        }
    }

    /** 统计今日已平仓交易数 */
    private int countTodayTrades() {
        try {
            return strategyMapper.countTodayTrades();
        } catch (Exception e) {
            return 0;
        }
    }

    /** BTC大盘趋势检查：返回 true 表示大盘健康，可以开仓 */
    private boolean checkBtcMarketHealthy() {
        long now = System.currentTimeMillis();
        // 缓存1分钟
        if (now - lastBtcCheckTime < 60_000 && btcMarketBearish != null) {
            return !btcMarketBearish;
        }
        lastBtcCheckTime = now;

        try {
            // 获取BTC 15分钟K线和1小时K线
            List<KlineData> btc15m = fetchKlines(BTC_SYMBOL, "15m", 1);
            List<KlineData> btc1h = fetchKlines(BTC_SYMBOL, "1h", 4);

            if (btc15m.isEmpty()) {
                btcMarketBearish = false; // 获取失败不阻挡
                return true;
            }

            // 检查BTC 15分钟涨跌
            KlineData latest15m = btc15m.get(btc15m.size() - 1);
            double btcRet15 = (Double.parseDouble(latest15m.getClose()) -
                    Double.parseDouble(latest15m.getOpen())) / Double.parseDouble(latest15m.getOpen()) * 100;

            // BTC 15分钟跌超阈值 → 大盘走弱
            if (btcRet15 < BTC_BEARISH_THRESHOLD) {
                log.info("BTC_ALERT: BTC 15分钟跌 {:.2f}% > {:.1f}%，大盘走弱，禁止开仓",
                        btcRet15, -BTC_BEARISH_THRESHOLD);
                btcMarketBearish = true;
                return false;
            }

            // 检查BTC 1小时趋势
            if (btc1h.size() >= 4) {
                double btcRet1h = (Double.parseDouble(btc1h.get(btc1h.size()-1).getClose()) -
                        Double.parseDouble(btc1h.get(0).getOpen())) / Double.parseDouble(btc1h.get(0).getOpen()) * 100;
                if (btcRet1h < -1.5) {
                    log.info("BTC_ALERT: BTC 1小时走弱 {:.2f}%，需等大盘企稳", btcRet1h);
                    btcMarketBearish = true;
                    return false;
                }
            }

            btcMarketBearish = false;
            return true;
        } catch (Exception e) {
            log.debug("BTC大盘检查异常: {}", e.getMessage());
            btcMarketBearish = false;
            return true;
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

                // 获取24h行情和1m K线
                Map<String, String> ticker = fetchTicker(symbol);
                if (ticker.isEmpty()) continue;

                // ----- 新增：24h交易量过滤 -----
                double quoteVolume = parseDoubleSafe(ticker.getOrDefault("volume", "0"));
                if (quoteVolume < MIN_24H_VOLUME_USDT) {
                    log.debug("{} 24h成交量={:.0f} < 10M，量能不足跳过", symbol, quoteVolume);
                    continue;
                }

                double volatility24h = parseDoubleSafe(ticker.getOrDefault("priceChangePercent", "0"));
                volatility24h = Math.abs(volatility24h);

                // ----- 新增：高波动币种额外风控 -----
                boolean isHighVol = HIGH_VOLATILITY_SYMBOLS.contains(symbol);
                if (isHighVol && volatility24h > 15) {
                    // meme币24h波动超15%时不进场（复盘：PEPE大亏是在极端波动下开的仓）
                    log.info("{} 高波动币种 24h波动={:.1f}% > 15%，风险过高跳过", symbol, volatility24h);
                    continue;
                }

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

                // ----- 高波动币种额外评分要求 -----
                if (isHighVol && signal.score < 85) {
                    log.info("{} 高波动币种信号评分={} < 85，风险过高跳过", symbol, signal.score);
                    saveSkippedSignal(symbol, baseAsset, signal, volatility24h, config.getMode(),
                            "high_vol_symbol_low_score");
                    continue;
                }

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
                    log.info("策略信号接受: {} {} 价格={} 模式={} score={} subtype={} ret1={:.2f}% ret5={:.2f}% relvol={:.2f}",
                            symbol, signal.direction, signal.price, config.getMode(), signal.score,
                            signal.subtype, signal.ret1, signal.ret5, signal.relvol);
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
     * 检查退出条件：止损 / 利润保护回撤 / 动态退出（复盘优化版）
     * 关键改进：
     * 1. 止损阈值从50%降低到合理范围（通过DB配置控制，默认5-8%）
     * 2. 利润保护触发从120%降低到3-5%（通过DB配置控制）
     * 3. 最大持仓时间从8h降到4h
     * 4. 增加浮盈回撤保护：浮盈达2%即激活保本止损
     */
    private void checkExitConditions() {
        List<StrategyPosition> positions = strategyMapper.findOpenPositions();
        for (StrategyPosition pos : positions) {
            try {
                StrategyConfig config = strategyMapper.findByMode(
                        pos.getStrategyName().contains("早段") ? "EARLY" : "HIGH");
                if (config == null) continue;

                String reason = null;

                // ---- 第1层：浮盈回撤保护（新增！复盘核心改进） ----
                // 当浮盈达到margin的2.5%时，启动保本止损
                // 如果回撤到仅剩0.5%利润 → 保本退出
                double breakevenPct = 2.5;  // 浮盈达2.5%启动保本保护
                double breakevenProtectPct = 0.5; // 回撤到0.5%以下退出
                if (pos.getUnrealizedPnl() < pos.getMargin() * breakevenProtectPct / 100
                        && pos.getHighestPnl() >= pos.getMargin() * breakevenPct / 100) {
                    reason = String.format("跌破保本线，最高浮盈%.1fU回撤到%.1fU (保本%)",
                            pos.getHighestPnl(), pos.getUnrealizedPnl(), breakevenPct);
                }

                // ---- 第2层：止损检查（硬止损） ----
                if (reason == null) {
                    double lossPct = -(config.getStopLossPct());
                    if (pos.getPnlPct() <= lossPct) {
                        reason = "原定止损触发 (" + String.format("%.1f", pos.getPnlPct()) + "%)";
                    }
                }

                // ---- 第3层：利润保护回撤 ----
                if (reason == null && pos.getHighestPnl() >= pos.getMargin() * config.getProfitProtectPct() / 100) {
                    double drawdownFromPeak = pos.getHighestPnl() - pos.getUnrealizedPnl();
                    double drawdownPct = pos.getHighestPnl() > 0 ? drawdownFromPeak / pos.getHighestPnl() * 100 : 0;
                    if (drawdownFromPeak >= pos.getMargin() * config.getDrawdownExitPct() / 100) {
                        reason = String.format("盈利高点回撤%.0f%%保护 (峰值%.1fU 当前%.1fU)",
                                drawdownPct, pos.getHighestPnl(), pos.getUnrealizedPnl());
                    }
                }

                // ---- 第4层：动态退出 ----
                if (reason == null) {
                    double minutesOpen = Duration.between(pos.getOpenTime(), LocalDateTime.now()).toMinutes();
                    double highestPnlPct = pos.getHighestPnl() / pos.getMargin() * 100;
                    String subtype = extractSubtype(pos.getStrategyName());
                    String dynamicReason = scoringService.dynamicExitCheck(
                            null, pos.getPnlPct(), highestPnlPct, minutesOpen, subtype);
                    if (dynamicReason != null) {
                        reason = dynamicReason;
                    }
                }

                // ---- 第5层：最大持仓时间（从8h缩短到4h） ----
                if (reason == null) {
                    long hoursOpen = Duration.between(pos.getOpenTime(), LocalDateTime.now()).toHours();
                    if (hoursOpen >= MAX_HOLD_HOURS && pos.getHighestPnl() < pos.getMargin() * config.getProfitProtectPct() / 100) {
                        reason = String.format("%d小时未启动利润保护退出", MAX_HOLD_HOURS);
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

    /** 从策略名中提取子类型 */
    private String extractSubtype(String strategyName) {
        if (strategyName == null) return "standard";
        if (strategyName.contains("peak")) return "high_peak";
        if (strategyName.contains("vol_break")) return "volume_break";
        if (strategyName.contains("compression")) return "compression_break";
        if (strategyName.contains("extension")) return "extension";
        if (strategyName.contains("early")) return "early_stage";
        return "standard";
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
            // 策略名加入子类型标签（如 "早段动态_early_stage" / "高位保护_volume_break"）
            String subtypeTag = signal.subtype != null ? "_" + signal.subtype : "";
            pos.setStrategyName(config.getName() + subtypeTag);
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

            // 更新当日累计PnL和冷却时间
            todayTotalPnl += finalPnl;
            lastTradeCloseTime = LocalDateTime.now();

            log.info("平仓: {} {} {} 盈亏={:.2f}U ({:.1f}%) 今日累计={:.2f}U 原因={}",
                    pos.getSymbol(), pos.getDirection(), pos.getStrategyName(), finalPnl,
                    finalPnl / pos.getMargin() * 100, todayTotalPnl, reason);

        } catch (Exception e) {
            log.error("平仓失败 {}: {}", pos.getSymbol(), e.getMessage());
        }
    }

    // ==================== 信号检测（多维评分引擎） ====================

    /**
     * 基于1分钟K线 + 多维评分引擎检测入场信号
     * 替代旧版简单动量检测，使用 fund_score / fast_score / quality_score / combined_score
     */
    private SignalResult detectSignal(List<KlineData> klines, Map<String, String> ticker) {
        // 调用多维评分引擎
        ScoringService.ScoreResult score = scoringService.score1m(klines);
        if (score == null || "NEUTRAL".equals(score.direction)) return null;

        // 入场确认
        ScoringService.EntryConfirm confirm = scoringService.confirmEntry(score, "");

        if (confirm == null || !confirm.passed) return null;

        SignalResult result = new SignalResult();
        result.price = ticker.getOrDefault("lastPrice", String.valueOf(score.ma7));
        result.direction = score.direction;
        result.momentum = score.ret3; // 用3min动量作为主动量
        result.score = (int) score.combinedScore;
        result.subtype = confirm.subtype;          // 子类型
        result.ret1 = score.ret1;
        result.ret5 = score.ret5;
        result.relvol = score.relvol;
        result.pullback = score.pullback;

        return result;
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
        String subtype;       // high_peak / volume_break / compression_break / extension / early_stage / standard
        double ret1;
        double ret5;
        double relvol;
        double pullback;
    }
}
