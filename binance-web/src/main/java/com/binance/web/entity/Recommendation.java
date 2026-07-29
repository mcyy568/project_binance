package com.binance.web.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Recommendation {
    /** 交易对符号 */
    private String symbol;
    /** 基础资产名 */
    private String baseAsset;
    /** 当前价格 */
    private String price;
    /** 方向：LONG 做多 / SHORT 做空 */
    private String direction;
    /** 综合评分 0-100 */
    private int score;
    /** 信号强度描述 */
    private String signal;
    /** 推荐理由 */
    private String reason;
    /** 24h成交量(USDT) */
    private String volume;
    /** 24h涨跌幅 */
    private String change24h;
    /** 推荐购买时间 */
    private String recommendTime;
    /** 更新时间 */
    private String updateTime;
}
