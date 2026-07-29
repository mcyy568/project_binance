package com.binance.web.mapper;

import com.binance.web.entity.KlineData;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface KlineMapper {

    /**
     * 批量插入K线数据，重复的symbol+openTime则忽略
     */
    @Insert("<script>" +
            "INSERT IGNORE INTO binance_kline (symbol, open_time, open_price, high_price, low_price, close_price, " +
            "volume, close_time, quote_asset_volume, number_of_trades, " +
            "taker_buy_base_asset_volume, taker_buy_quote_asset_volume) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.symbol}, #{item.openTime}, #{item.open}, #{item.high}, #{item.low}, #{item.close}, " +
            "#{item.volume}, #{item.closeTime}, #{item.quoteAssetVolume}, #{item.numberOfTrades}, " +
            "#{item.takerBuyBaseAssetVolume}, #{item.takerBuyQuoteAssetVolume})" +
            "</foreach>" +
            "</script>")
    int batchInsertIgnore(@Param("list") List<KlineData> list);

    /**
     * 根据symbol查询K线数据，按openTime升序
     */
    @Select("SELECT symbol, open_time, open_price AS open, high_price AS high, low_price AS low, " +
            "close_price AS close, volume, close_time, quote_asset_volume, number_of_trades, " +
            "taker_buy_base_asset_volume, taker_buy_quote_asset_volume " +
            "FROM binance_kline WHERE symbol = #{symbol} ORDER BY open_time ASC")
    @Results(id = "klineResult", value = {
            @Result(property = "symbol", column = "symbol"),
            @Result(property = "openTime", column = "open_time"),
            @Result(property = "open", column = "open"),
            @Result(property = "high", column = "high"),
            @Result(property = "low", column = "low"),
            @Result(property = "close", column = "close"),
            @Result(property = "volume", column = "volume"),
            @Result(property = "closeTime", column = "close_time"),
            @Result(property = "quoteAssetVolume", column = "quote_asset_volume"),
            @Result(property = "numberOfTrades", column = "number_of_trades"),
            @Result(property = "takerBuyBaseAssetVolume", column = "taker_buy_base_asset_volume"),
            @Result(property = "takerBuyQuoteAssetVolume", column = "taker_buy_quote_asset_volume")
    })
    List<KlineData> selectBySymbol(@Param("symbol") String symbol);

    /**
     * 查询某个symbol最新一条K线的openTime
     */
    @Select("SELECT MAX(open_time) FROM binance_kline WHERE symbol = #{symbol}")
    Long selectMaxOpenTime(@Param("symbol") String symbol);

    /**
     * 删除过期K线数据（保留最近N条）
     */
    @Delete("DELETE FROM binance_kline WHERE symbol = #{symbol} AND open_time NOT IN " +
            "(SELECT t.open_time FROM (SELECT open_time FROM binance_kline WHERE symbol = #{symbol} " +
            "ORDER BY open_time DESC LIMIT #{keepCount}) t)")
    int deleteExpired(@Param("symbol") String symbol, @Param("keepCount") int keepCount);
}
