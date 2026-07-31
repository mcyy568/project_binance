package com.binance.web.service;

import com.binance.web.entity.KlineData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 多维评分引擎 —— 参考"早期阶段动态入场"模型
 *
 * 四维评分体系：
 * 1. fundScore  (资金方向评分 0-100)  : 成交量质量、主动买入占比、量价配合
 * 2. fastScore  (快速动量评分 0-100)  : 多周期动量(ret1/ret3/ret5)、方向一致性
 * 3. qualityScore(结构质量评分 0-100) : 均线排列、回撤质量、压缩状态、上影线
 * 4. combinedScore(综合评分 0-100)   : 加权融合，入场核心依据
 *
 * 入场确认门槛（参考模型）：
 * - combinedScore >= 75   → 可入场
 * - fastScore >= 91       → 动量确认
 * - fundScore >= 52       → 资金确认
 */
@Slf4j
@Service
public class ScoringService {

    // ============ 评分权重 ============
    private static final double W_FUND    = 0.30;
    private static final double W_FAST    = 0.40;
    private static final double W_QUALITY = 0.30;

    // ============ 阈值常量 ============
    private static final double RET1_MIN  = 0.10;   // 1min 最低涨幅
    private static final double RET3_MIN  = 0.15;   // 3min 最低涨幅
    private static final double RET15_MAX = 3.5;    // 15min 过热上限
    private static final double PULLBACK_MAX = 3.0; // 最大回撤
    private static final double WICK_RATIO_MAX_LONG = 0.60; // LONG 方向最大上影线占比

    // ============ 对外入口 ============

    /**
     * 基于1分钟K线进行多维评分（用于策略实时交易）
     */
    public ScoreResult score1m(List<KlineData> klines) {
        if (klines == null || klines.size() < 20) return emptyResult();
        return compute(klines, 1);
    }

    /**
     * 基于15分钟K线进行多维评分（用于推荐扫描）
     */
    public ScoreResult score15m(List<KlineData> klines) {
        if (klines == null || klines.size() < 30) return emptyResult();
        return compute(klines, 15);
    }

    // ============ 核心计算 ============

    private ScoreResult compute(List<KlineData> klines, int periodMin) {
        int n = klines.size();

        // ---- 提取数组 ----
        double[] closes = new double[n];
        double[] highs   = new double[n];
        double[] lows    = new double[n];
        double[] volumes = new double[n];
        double[] quoteVols = new double[n];
        double[] takerBuyQuoteVols = new double[n];
        double[] takerBuyBaseVols  = new double[n];

        for (int i = 0; i < n; i++) {
            KlineData k = klines.get(i);
            closes[i]   = parseDouble(k.getClose());
            highs[i]    = parseDouble(k.getHigh());
            lows[i]     = parseDouble(k.getLow());
            volumes[i]  = parseDouble(k.getVolume());
            quoteVols[i] = parseDouble(k.getQuoteAssetVolume());
            takerBuyQuoteVols[i] = parseDouble(k.getTakerBuyQuoteAssetVolume());
            takerBuyBaseVols[i]  = parseDouble(k.getTakerBuyBaseAssetVolume());
        }

        ScoreResult r = new ScoreResult();

        // ========== 多周期动量 ==========
        // ret1: 最近1根 vs 前1根，ret3: 最近3根均值 vs 前3根均值，ret5/ret15 同理
        int p1 = periodToBars(1, periodMin);
        int p3 = periodToBars(3, periodMin);
        int p5 = periodToBars(5, periodMin);
        int p15 = periodToBars(15, periodMin);

        r.ret1  = pctChange(closes, n - p1, n, closes, n - 2 * p1, n - p1);
        r.ret3  = pctChange(closes, n - p3, n, closes, n - 2 * p3, n - p3);
        r.ret5  = pctChange(closes, n - p5, n, closes, n - 2 * p5, n - p5);
        r.ret15 = pctChange(closes, n - p15, n, closes, n - 2 * p15, n - p15);

        // 确定方向
        r.direction = determineDirection(r);
        if ("NEUTRAL".equals(r.direction)) {
            r.signal = "方向不明确";
            return r;
        }

        // ========== 成交量 ==========
        double avgVol20 = avg(volumes, Math.max(0, n - 20), n);
        r.relvol = avgVol20 > 0 ? avg(volumes, n - p3, n) / avgVol20 : 1.0;
        r.quoteVol = avg(quoteVols, n - p5, n);

        // ========== 1. fundScore（资金方向评分） ==========
        r.fundScore = computeFundScore(closes, volumes, quoteVols, takerBuyQuoteVols, takerBuyBaseVols, n, p5);

        // ========== 2. fastScore（快速动量评分） ==========
        r.fastScore = computeFastScore(r, closes, volumes, n, p3, p5);

        // ========== 均线 ==========
        r.ma7  = sma(closes, n - 7, n);
        r.ma25 = sma(closes, Math.max(0, n - 25), n);

        // ========== 回撤分析 ==========
        r.pullback = computePullback(closes, highs, lows, n, r.direction, p15);

        // ========== 上影线分析 ==========
        r.wickRatio = computeWickRatio(highs, lows, closes, n, r.direction);

        // ========== 布林压缩 ==========
        r.compression = computeCompression(closes, n, 20);

        // ========== 3. qualityScore（结构质量评分） ==========
        r.qualityScore = computeQualityScore(r, closes, highs, lows, n);

        // ========== 4. combinedScore（综合评分） ==========
        r.combinedScore = W_FUND * r.fundScore + W_FAST * r.fastScore + W_QUALITY * r.qualityScore;
        r.combinedScore = Math.min(100, Math.max(0, r.combinedScore));

        // ========== 信号描述 ==========
        r.signal = describeSignal(r);

        return r;
    }

    // ============ 子评分计算 ============

    private double computeFundScore(double[] closes, double[] volumes,
                                    double[] quoteVols, double[] takerBuyQuoteVols,
                                    double[] takerBuyBaseVols, int n, int p5) {
        double score = 50; // 基线

        // 1) 成交量趋势：最近5周期成交量 > 前20周期均量 → 放量
        double volRecent = sum(volumes, n - p5, n);
        double volRef = avg(volumes, Math.max(0, n - 25), n - p5);
        if (volRef > 0) {
            double volTrend = volRecent / (p5 * volRef);
            if (volTrend >= 2.0) score += 20;
            else if (volTrend >= 1.5) score += 12;
            else if (volTrend >= 1.2) score += 5;
            else if (volTrend >= 0.8) score += 0;
            else score -= 10;
        }

        // 2) 主动买入占比
        double takerBuyQ = sum(takerBuyQuoteVols, n - p5, n);
        double totalQ = sum(quoteVols, n - p5, n);
        if (totalQ > 0) {
            double buyRatio = takerBuyQ / totalQ;
            if (buyRatio >= 0.60) score += 15;
            else if (buyRatio >= 0.53) score += 8;
            else if (buyRatio >= 0.48) score += 2;
            else if (buyRatio < 0.42) score -= 10;
        }

        // 3) 量价配合：价涨量增 / 价跌量缩
        double priceDir = closes[n - 1] - closes[n - p5];
        double volDir = volRecent / p5 - avg(volumes, n - p5 - 5, n - p5);
        if (priceDir > 0 && volDir > 0) score += 10;    // 价涨量增
        else if (priceDir < 0 && volDir < 0) score += 3; // 价跌量缩
        else if (priceDir > 0 && volDir < 0) score -= 8; // 价涨量缩(弱)
        else score -= 3;

        return clamp(score, 0, 100);
    }

    private double computeFastScore(ScoreResult r, double[] closes, double[] volumes,
                                    int n, int p3, int p5) {
        double score = 50;

        boolean isLong = "LONG".equals(r.direction);
        double ret1 = r.ret1, ret3 = r.ret3, ret5 = r.ret5;

        // 1) 多周期动量一致性
        if (isLong) {
            if (ret1 > 0 && ret3 > 0 && ret5 > 0) score += 15;  // 三重确认
            else if (ret1 > 0 && ret3 > 0) score += 8;          // 双重确认
            else if (ret5 > 0 && ret5 > r.ret15) score += 5;    // 加速中
            else score -= 10;

            // 2) 动量强度
            double absRet1 = Math.abs(ret1);
            if (absRet1 >= 0.5) score += 15;
            else if (absRet1 >= 0.25) score += 10;
            else if (absRet1 >= 0.10) score += 4;
            else score -= 8;

            // 3) 动量加速度：ret3 > ret15（短周期快于长周期）
            if (ret3 > r.ret15 && r.ret15 > -0.5) score += 10;
            else if (r.ret15 > ret3) score -= 5;
        } else {
            if (ret1 < 0 && ret3 < 0 && ret5 < 0) score += 15;
            else if (ret1 < 0 && ret3 < 0) score += 8;
            else if (ret5 < 0 && ret5 < r.ret15) score += 5;
            else score -= 10;

            double absRet1 = Math.abs(ret1);
            if (absRet1 >= 0.5) score += 15;
            else if (absRet1 >= 0.25) score += 10;
            else if (absRet1 >= 0.10) score += 4;
            else score -= 8;

            if (ret3 < r.ret15 && r.ret15 < 0.5) score += 10;
            else if (r.ret15 < ret3) score -= 5;
        }

        // 4) 成交量配合
        if (r.relvol >= 2.0) score += 10;
        else if (r.relvol >= 1.5) score += 6;
        else if (r.relvol >= 1.0) score += 0;
        else score -= 8;

        return clamp(score, 0, 100);
    }

    private double computeQualityScore(ScoreResult r, double[] closes,
                                       double[] highs, double[] lows, int n) {
        double score = 50;

        // 1) 均线排列
        if (r.ma7 > 0 && r.ma25 > 0) {
            if ("LONG".equals(r.direction) && r.ma7 > r.ma25) score += 12;     // 多头排列
            else if ("SHORT".equals(r.direction) && r.ma7 < r.ma25) score += 12; // 空头排列
            else score += 0; // 中性
        }

        // 2) 回撤控制
        if (r.pullback >= 0) {
            if (r.pullback <= 1.0) score += 15;
            else if (r.pullback <= 2.0) score += 10;
            else if (r.pullback <= 3.0) score += 4;
            else score -= 10;
        }

        // 3) 上影线质量（LONG要求小上影线）
        if ("LONG".equals(r.direction)) {
            if (r.wickRatio <= 0.30) score += 12;
            else if (r.wickRatio <= 0.50) score += 5;
            else if (r.wickRatio >= 0.70) score -= 10;
        } else {
            if (r.wickRatio >= 0.60) score += 5;
        }

        // 4) 布林压缩（压缩后突破更有力）
        if (r.compression > 0) {
            if (r.compression < 0.012) score += 15;
            else if (r.compression < 0.025) score += 8;
            else score += 0;
        }

        return clamp(score, 0, 100);
    }

    // ============ 工具算法 ============

    /** 多周期涨跌幅 % */
    private double pctChange(double[] close, int startRecent, int endRecent,
                             double[] closeRef, int startRef, int endRef) {
        double avgRecent = avg(close, Math.max(0, startRecent), Math.min(endRecent, close.length));
        double avgRef = avg(closeRef, Math.max(0, startRef), Math.min(endRef, closeRef.length));
        if (avgRef <= 0) return 0;
        return (avgRecent - avgRef) / avgRef * 100;
    }

    /** 确定方向 */
    private String determineDirection(ScoreResult r) {
        double ret1Abs = Math.abs(r.ret1);
        // 1min 无明显方向时不判断
        if (ret1Abs < 0.03 && Math.abs(r.ret3) < 0.05) return "NEUTRAL";

        // 综合 ret1/ret3/ret5 多数表决
        int longVotes = 0, shortVotes = 0;
        if (r.ret1 > 0) longVotes++; else shortVotes++;
        if (r.ret3 > 0) longVotes++; else shortVotes++;
        if (r.ret5 > 0) longVotes++; else shortVotes++;

        if (longVotes >= 2) return "LONG";
        if (shortVotes >= 2) return "SHORT";
        return r.ret1 > 0 ? "LONG" : "SHORT";
    }

    /** 最近高点回撤 % */
    private double computePullback(double[] closes, double[] highs, double[] lows, int n,
                                   String direction, int lookback) {
        if ("LONG".equals(direction)) {
            double peak = max(highs, Math.max(0, n - lookback), n);
            if (peak <= 0) return 0;
            return (peak - closes[n - 1]) / peak * 100;
        } else {
            double trough = min(lows, Math.max(0, n - lookback), n);
            if (trough <= 0) return 0;
            return (closes[n - 1] - trough) / trough * 100;
        }
    }

    /** 上影线占比 */
    private double computeWickRatio(double[] highs, double[] lows, double[] closes,
                                    int n, String direction) {
        double sumWick = 0;
        int count = Math.min(5, n);
        double sumRange = 0;
        for (int i = n - count; i < n; i++) {
            double range = highs[i] - lows[i];
            if (range <= 0) continue;
            if ("LONG".equals(direction)) {
                sumWick += highs[i] - closes[i];
            } else {
                sumWick += closes[i] - lows[i];
            }
            sumRange += range;
        }
        return sumRange > 0 ? sumWick / sumRange : 0;
    }

    /** 布林带压缩率 (std / sma) */
    private double computeCompression(double[] closes, int n, int period) {
        if (n < period) return 0;
        int start = n - period;
        double mean = avg(closes, start, n);
        double sumSq = 0;
        for (int i = start; i < n; i++) {
            sumSq += (closes[i] - mean) * (closes[i] - mean);
        }
        double std = Math.sqrt(sumSq / period);
        return mean > 0 ? std / mean : 0;
    }

    /** 信号文字描述 */
    private String describeSignal(ScoreResult r) {
        if (r.combinedScore >= 85) return "极强信号";
        if (r.combinedScore >= 75) return "强信号";
        if (r.combinedScore >= 60) return "中等信号";
        if (r.combinedScore >= 40) return "弱信号";
        return "无信号";
    }

    // ============ 入场确认 ============

    /**
     * 入场确认策略：多层过滤
     * @return null=不通过, 非null=通过（包含子类型标签）
     */
    public EntryConfirm confirmEntry(ScoreResult r, String symbol) {
        if (r == null || "NEUTRAL".equals(r.direction)) return null;

        // ---- 第一层：综合评分门槛 ----
        if (r.combinedScore < 75) {
            log.debug("{} combinedScore={} < 75, 不合格", symbol, String.format("%.1f", r.combinedScore));
            return null;
        }

        // ---- 第二层：快速动量确认 ----
        if (r.fastScore < 91) {
            log.debug("{} fastScore={} < 91, 动量不够", symbol, String.format("%.1f", r.fastScore));
            return null;
        }

        // ---- 第三层：资金方向确认 ----
        if (r.fundScore < 52) {
            log.debug("{} fundScore={} < 52, 资金未确认", symbol, String.format("%.1f", r.fundScore));
            return null;
        }

        // ---- 第四层：回撤确认 ----
        if (r.pullback > PULLBACK_MAX) {
            log.debug("{} pullback={} > {}, 回撤过大", symbol,
                    String.format("%.2f", r.pullback), PULLBACK_MAX);
            return null;
        }

        // ---- 第五层：过热线检测 ----
        if (Math.abs(r.ret15) > RET15_MAX) {
            log.debug("{} ret15={} > {}, 短期过热", symbol,
                    String.format("%.2f", r.ret15), RET15_MAX);
            return null;
        }

        // ---- 第六层：上影线确认（LONG方向） ----
        if ("LONG".equals(r.direction) && r.wickRatio > WICK_RATIO_MAX_LONG) {
            log.debug("{} wickRatio={} > {}, 上影线过长", symbol,
                    String.format("%.2f", r.wickRatio), WICK_RATIO_MAX_LONG);
            return null;
        }

        EntryConfirm confirm = new EntryConfirm();
        confirm.passed = true;
        confirm.subtype = deriveSubtype(r);
        return confirm;
    }

    /** 子类型分类（影响退出策略） */
    private String deriveSubtype(ScoreResult r) {
        if (r.combinedScore >= 90 && r.fastScore >= 95) return "high_peak";      // 极强峰值
        if (r.combinedScore >= 80 && r.relvol >= 2.0) return "volume_break";    // 放量突破
        if (r.pullback <= 1.0 && r.compression < 0.015) return "compression_break"; // 压缩突破
        if (r.ret15 > r.ret5 && r.ret5 > r.ret3) return "extension";            // 延续型
        if (r.ret3 >= r.ret15) return "early_stage";                            // 早段启动
        return "standard";
    }

    // ============ 退出时机判断 ============

    /**
     * 动态退出判定（在每tick的checkExitConditions中调用）
     * @return null=不退出, 非null=退出原因文本
     */
    public String dynamicExitCheck(ScoreResult currentScore, double pnlPct, double highestPnlPct,
                                   double minutesOpen, String subtype) {
        // ---- 1) high_peak 利润桥接退出：获利后回吐利润的60%即退出 ----
        if ("high_peak".equals(subtype) || "volume_break".equals(subtype)) {
            if (highestPnlPct >= 3.0 && pnlPct < highestPnlPct * 0.40) {
                return String.format("峰值利润桥接退出(峰值%.1f%% 当前%.1f%%)", highestPnlPct, pnlPct);
            }
        }

        if ("compression_break".equals(subtype)) {
            if (highestPnlPct >= 2.5 && pnlPct < highestPnlPct * 0.45) {
                return String.format("压缩突破利润回撤退出(峰值%.1f%% 当前%.1f%%)", highestPnlPct, pnlPct);
            }
        }

        // ---- 2) 双时间尺度快速失败 ----
        if (minutesOpen >= 12 && highestPnlPct < 0.5 && pnlPct <= 0) {
            return "双时间尺度快速失败(12分钟未启动)";
        }

        // ---- 3) 双时间尺度慢速失败 ----
        if (minutesOpen >= 25 && highestPnlPct < 1.0 && pnlPct < 0.2) {
            return "双时间尺度慢速失败(25分钟利润不足)";
        }

        // ---- 4) 过热失败 ----
        if (minutesOpen >= 20 && highestPnlPct < 0.8 && pnlPct < -1.0) {
            return "过热信号失败(20分钟浮亏>1%)";
        }

        // ---- 5) extension 延续型跌破启动价 ----
        if ("extension".equals(subtype) && minutesOpen >= 15 && pnlPct < -0.5 && highestPnlPct < 0.5) {
            return "延续型15分钟未起势退出";
        }

        return null; // 不退出
    }

    // ============ 辅助方法 ============

    private int periodToBars(int minutes, int barMinutes) {
        return Math.max(1, minutes / barMinutes);
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s); }
        catch (Exception e) { return 0; }
    }

    private double avg(double[] arr, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(to, arr.length);
        if (from >= to) return 0;
        double s = 0;
        for (int i = from; i < to; i++) s += arr[i];
        return s / (to - from);
    }

    private double sma(double[] arr, int from, int to) {
        return avg(arr, from, to);
    }

    private double sum(double[] arr, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(to, arr.length);
        double s = 0;
        for (int i = from; i < to; i++) s += arr[i];
        return s;
    }

    private double max(double[] arr, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(to, arr.length);
        if (from >= to) return 0;
        double m = arr[from];
        for (int i = from + 1; i < to; i++) {
            if (arr[i] > m) m = arr[i];
        }
        return m;
    }

    private double min(double[] arr, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(to, arr.length);
        if (from >= to) return 0;
        double m = arr[from];
        for (int i = from + 1; i < to; i++) {
            if (arr[i] < m) m = arr[i];
        }
        return m;
    }

    private ScoreResult emptyResult() {
        ScoreResult r = new ScoreResult();
        r.direction = "NEUTRAL";
        r.signal = "数据不足";
        return r;
    }

    // ============ 内部类 ============

    public static class ScoreResult {
        public double fundScore;       // 资金方向评分 0-100
        public double fastScore;       // 快速动量评分 0-100
        public double qualityScore;    // 结构质量评分 0-100
        public double combinedScore;   // 综合评分 0-100
        public double ret1;            // 1min收益率 %
        public double ret3;            // 3min收益率 %
        public double ret5;            // 5min收益率 %
        public double ret15;           // 15min收益率 %
        public double relvol;          // 相对成交量
        public double quoteVol;        // 报价成交量(USDT)
        public double ma7;             // 7周期均线
        public double ma25;            // 25周期均线
        public double pullback;        // 从高点回撤 %
        public double wickRatio;       // 上影线占比 0-1
        public double compression;     // 布林压缩率
        public String direction;       // LONG / SHORT / NEUTRAL
        public String signal;          // 信号描述

        /** 供 RecommendService 使用的摘要 */
        public String toReason() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("综合%.0f", combinedScore));
            sb.append(String.format(" 资金%.0f", fundScore));
            sb.append(String.format(" 动量%.0f", fastScore));
            sb.append(String.format(" 质量%.0f", qualityScore));
            sb.append(String.format(" |%s", signal));
            return sb.toString();
        }
    }

    public static class EntryConfirm {
        public boolean passed;
        public String subtype;   // high_peak / volume_break / compression_break / extension / early_stage / standard
    }
}
