package com.binance.web.entity;

import lombok.Data;

/**
 * 策略信号实体
 */
@Data
public class StrategySignal {
    private Long id;

    /** 交易对 */
    private String symbol;

    /** 币种名称 */
    private String baseAsset;

    /** 信号方向 LONG/SHORT */
    private String direction;

    /** 24H波动率 */
    private Double volatility24h;

    /** 信号价格 */
    private String price;

    /** 策略模式 EARLY/HIGH */
    private String mode;

    /** 信号评分 */
    private Integer score;

    /** 状态: PENDING/ACCEPTED/SKIPPED */
    private String status;

    /** 跳过原因 */
    private String skipReason;

    /** 信号检测时间 */
    private java.time.LocalDateTime detectTime;

    /** 处理时间 */
    private java.time.LocalDateTime processTime;
}
