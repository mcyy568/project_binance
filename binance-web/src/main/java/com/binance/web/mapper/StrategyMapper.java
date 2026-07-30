package com.binance.web.mapper;

import com.binance.web.entity.*;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface StrategyMapper {

    // ==================== 策略配置 ====================

    @Select("SELECT * FROM strategy_config WHERE enabled = true")
    List<StrategyConfig> findEnabledConfigs();

    @Select("SELECT * FROM strategy_config WHERE mode = #{mode} AND enabled = true")
    StrategyConfig findByMode(@Param("mode") String mode);

    @Update("UPDATE strategy_config SET name=#{name}, volatility_threshold=#{volatilityThreshold}, " +
            "stop_loss_pct=#{stopLossPct}, profit_protect_pct=#{profitProtectPct}, " +
            "drawdown_exit_pct=#{drawdownExitPct}, initial_capital=#{initialCapital}, " +
            "position_size=#{positionSize}, max_positions=#{maxPositions}, leverage=#{leverage}, " +
            "enabled=#{enabled}, update_time=NOW() WHERE id=#{id}")
    int updateConfig(StrategyConfig config);

    @Select("SELECT * FROM strategy_config")
    List<StrategyConfig> findAllConfigs();

    // ==================== 信号 ====================

    @Insert("INSERT INTO strategy_signal (symbol, base_asset, direction, volatility_24h, price, mode, score, status, skip_reason, detect_time, process_time) " +
            "VALUES (#{symbol}, #{baseAsset}, #{direction}, #{volatility24h}, #{price}, #{mode}, #{score}, #{status}, #{skipReason}, #{detectTime}, #{processTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSignal(StrategySignal signal);

    @Select("SELECT COUNT(*) FROM strategy_signal WHERE symbol=#{symbol} AND status='ACCEPTED' AND DATE(detect_time) = CURDATE()")
    int countTodayAcceptedBySymbol(@Param("symbol") String symbol);

    @Select("SELECT * FROM strategy_signal ORDER BY detect_time DESC LIMIT 200")
    List<StrategySignal> findRecentSignals();

    @Select("SELECT COUNT(*) FROM strategy_signal")
    int countTotalSignals();

    // ==================== 持仓 ====================

    @Insert("INSERT INTO strategy_position (symbol, base_asset, strategy_name, margin, leverage, " +
            "open_price, current_price, direction, stop_loss_price, profit_protect_price, " +
            "unrealized_pnl, pnl_pct, highest_pnl, max_adverse, order_id, executed_qty, " +
            "status, open_time, update_time) " +
            "VALUES (#{symbol}, #{baseAsset}, #{strategyName}, #{margin}, #{leverage}, " +
            "#{openPrice}, #{currentPrice}, #{direction}, #{stopLossPrice}, #{profitProtectPrice}, " +
            "#{unrealizedPnl}, #{pnlPct}, #{highestPnl}, #{maxAdverse}, #{orderId}, #{executedQty}, " +
            "#{status}, #{openTime}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPosition(StrategyPosition position);

    @Select("SELECT * FROM strategy_position WHERE status = 'OPEN'")
    List<StrategyPosition> findOpenPositions();

    @Select("SELECT COUNT(*) FROM strategy_position WHERE status = 'OPEN'")
    int countOpenPositions();

    @Select("SELECT COUNT(*) FROM strategy_position WHERE status = 'OPEN' AND symbol = #{symbol}")
    int countOpenBySymbol(@Param("symbol") String symbol);

    @Update("UPDATE strategy_position SET current_price=#{currentPrice}, unrealized_pnl=#{unrealizedPnl}, " +
            "pnl_pct=#{pnlPct}, highest_pnl=#{highestPnl}, max_adverse=#{maxAdverse}, update_time=NOW() " +
            "WHERE id=#{id}")
    int updatePositionPnL(StrategyPosition position);

    @Update("UPDATE strategy_position SET status='CLOSED', close_order_id=#{closeOrderId}, update_time=NOW() WHERE id=#{id}")
    int closePosition(@Param("id") Long id, @Param("closeOrderId") Long closeOrderId);

    // ==================== 交易记录 ====================

    @Insert("INSERT INTO strategy_trade (symbol, base_asset, strategy_name, direction, margin, executed_qty, leverage, " +
            "open_price, close_price, pnl, pnl_pct, highest_pnl, max_adverse, hold_duration, " +
            "close_reason, open_time, close_time) " +
            "VALUES (#{symbol}, #{baseAsset}, #{strategyName}, #{direction}, #{margin}, #{executedQty}, #{leverage}, " +
            "#{openPrice}, #{closePrice}, #{pnl}, #{pnlPct}, #{highestPnl}, #{maxAdverse}, #{holdDuration}, " +
            "#{closeReason}, #{openTime}, #{closeTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTrade(StrategyTrade trade);

    @Select("SELECT * FROM strategy_trade ORDER BY close_time DESC LIMIT 200")
    List<StrategyTrade> findRecentTrades();

    @Select("SELECT * FROM strategy_trade WHERE pnl > 0 ORDER BY close_time DESC")
    List<StrategyTrade> findWinTrades();

    @Select("SELECT * FROM strategy_trade WHERE pnl <= 0 ORDER BY close_time DESC")
    List<StrategyTrade> findLossTrades();

    @Select("SELECT COUNT(*) FROM strategy_trade")
    int countTotalTrades();

    @Select("SELECT COUNT(*) FROM strategy_trade WHERE pnl > 0")
    int countWinTrades();

    @Select("SELECT SUM(pnl) FROM strategy_trade")
    Double sumTotalPnl();

    @Select("SELECT MAX(pnl) FROM strategy_trade")
    Double maxSingleWin();

    @Select("SELECT MIN(pnl) FROM strategy_trade")
    Double minSingleLoss();

    @Select("SELECT AVG(pnl) FROM strategy_trade")
    Double avgSinglePnl();

    @Select("SELECT MAX(total_pnl) FROM (SELECT SUM(t.pnl) OVER (ORDER BY t.close_time) as total_pnl FROM strategy_trade t) sub")
    Double maxCumulativePnl();

    @Select("SELECT MIN(pnl) FROM (SELECT SUM(pnl) OVER (ORDER BY close_time) as pnl FROM strategy_trade) sub")
    Double maxDrawdown();

    @Select("SELECT MAX(margin * leverage) FROM strategy_trade")
    Double peakMargin();

    // ==================== 每日统计 ====================

    @Select("SELECT DATE(close_time) as date, COUNT(*) as count, SUM(pnl) as pnl, " +
            "ROUND(SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 1) as winRate, " +
            "ROUND(SUM(CASE WHEN close_reason LIKE '%止损%' THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 1) as stopLossRate " +
            "FROM strategy_trade GROUP BY DATE(close_time) ORDER BY date DESC LIMIT 30")
    @Results({
            @Result(column = "date", property = "date"),
            @Result(column = "count", property = "count"),
            @Result(column = "pnl", property = "pnl"),
            @Result(column = "win_rate", property = "winRate"),
            @Result(column = "stop_loss_rate", property = "stopLossRate")
    })
    List<DailyStat> findDailyStats();

    // 内部类用于每日统计
    class DailyStat {
        public String date;
        public int count;
        public Double pnl;
        public Double winRate;
        public Double stopLossRate;
    }
}
