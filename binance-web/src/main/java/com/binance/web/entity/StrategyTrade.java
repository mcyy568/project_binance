package com.binance.web.entity;

import lombok.Data;

/**
 * 策略交易记录（已平仓）
 */
@Data
public class StrategyTrade {
    private Long id;

    /** 交易对 */
    private String symbol;

    /** 币种名称 */
    private String baseAsset;

    /** 策略名称 */
    private String strategyName;

    /** 方向 LONG/SHORT */
    private String direction;

    /** 保证金 */
    private Double margin;

    /** 杠杆倍数 */
    private Integer leverage;

    /** 开仓价 */
    private String openPrice;

    /** 平仓价 */
    private String closePrice;

    /** 净盈亏 */
    private Double pnl;

    /** 盈亏率(%) */
    private Double pnlPct;

    /** 最高浮盈 */
    private Double highestPnl;

    /** 最大逆向 */
    private Double maxAdverse;

    /** 持仓时长(分钟) */
    private Integer holdDuration;

    /** 平仓原因 */
    private String closeReason;

    /** 开仓时间 */
    private java.time.LocalDateTime openTime;

    /** 平仓时间 */
    private java.time.LocalDateTime closeTime;
}
