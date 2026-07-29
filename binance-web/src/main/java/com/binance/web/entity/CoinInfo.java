package com.binance.web.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinInfo {
    /** 交易对符号，如 BTCUSDT */
    private String symbol;
    /** 基础资产，如 BTC */
    private String baseAsset;
    /** 计价资产，如 USDT */
    private String quoteAsset;
    /** 基础资产精度 */
    private Long baseAssetPrecision;
    /** 计价资产精度 */
    private Long quoteAssetPrecision;
    /** 最新价格 */
    private String price;
}
