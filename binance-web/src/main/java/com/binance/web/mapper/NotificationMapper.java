package com.binance.web.mapper;

import com.binance.web.entity.NotificationLog;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NotificationMapper {

    /**
     * 查询某币种某种通知类型最近一次发送时间（4小时内视为已发送）
     */
    @Select("SELECT * FROM notification_log WHERE symbol = #{symbol} AND type = #{type} " +
            "AND sent_time > DATE_SUB(NOW(), INTERVAL 4 HOUR) ORDER BY sent_time DESC LIMIT 1")
    @Results(id = "notificationResult", value = {
            @Result(property = "sentTime", column = "sent_time")
    })
    NotificationLog findRecent(@Param("symbol") String symbol, @Param("type") String type);

    @Insert("INSERT INTO notification_log (symbol, type, score) VALUES (#{symbol}, #{type}, #{score})")
    int insert(NotificationLog log);

    /**
     * 清理7天前的日志
     */
    @Delete("DELETE FROM notification_log WHERE sent_time < DATE_SUB(NOW(), INTERVAL 7 DAY)")
    int cleanOld();
}
