package com.binance.web.entity;

import lombok.Data;

/**
 * 策略配置实体
 */
@Data
public class StrategyConfig {
    private Long id;

    /** 策略名称 */
    private String name;

    /** 24H波动率阈值(%) 低于此值用早段动态，>=此值用高位保护 */
    private Double volatilityThreshold;

    /** 止损比例 (%) */
    private Double stopLossPct;

    /** 利润保护启动比例 (%) */
    private Double profitProtectPct;

    /** 回撤退出比例 (%) */
    private Double drawdownExitPct;

    /** 初始账户资金 */
    private Double initialCapital;

    /** 每笔保证金 */
    private Double positionSize;

    /** 最大同时持仓数 */
    private Integer maxPositions;

    /** 杠杆倍数 */
    private Integer leverage;

    /** 是否启用 */
    private Boolean enabled;

    /** 策略模式: EARLY(早段动态) / HIGH(高位保护) */
    private String mode;

    private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime updateTime;
}
