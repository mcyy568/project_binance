package com.binance.web.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 策略持仓实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StrategyPosition {
    private Long id;

    /** 交易对 */
    private String symbol;

    /** 币种名称 */
    private String baseAsset;

    /** 策略名称 (早段动态/高位保护) */
    private String strategyName;

    /** 保证金 */
    private Double margin;

    /** 杠杆倍数 */
    private Integer leverage;

    /** 开仓价 */
    private String openPrice;

    /** 当前估值价 */
    private String currentPrice;

    /** 方向 LONG/SHORT */
    private String direction;

    /** 止损价 */
    private String stopLossPrice;

    /** 利润保护触发价 */
    private String profitProtectPrice;

    /** 浮动盈亏 */
    private Double unrealizedPnl;

    /** 收益率(%) */
    private Double pnlPct;

    /** 最高浮盈 */
    private Double highestPnl;

    /** 最大逆向 */
    private Double maxAdverse;

    /** Binance 开仓订单ID */
    private Long orderId;

    /** Binance 平仓订单ID */
    private Long closeOrderId;

    /** 实际成交数量（币本位） */
    private String executedQty;

    /** 状态: OPEN/CLOSED */
    private String status;

    /** 开仓时间 */
    private java.time.LocalDateTime openTime;

    /** 更新时间 */
    private java.time.LocalDateTime updateTime;
}
