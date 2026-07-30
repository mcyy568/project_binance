CREATE TABLE IF NOT EXISTS binance_kline (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol      VARCHAR(32)  NOT NULL COMMENT '交易对',
    open_time   BIGINT       NOT NULL COMMENT '开盘时间(毫秒)',
    open_price  VARCHAR(32)  NOT NULL COMMENT '开盘价',
    high_price  VARCHAR(32)  NOT NULL COMMENT '最高价',
    low_price   VARCHAR(32)  NOT NULL COMMENT '最低价',
    close_price VARCHAR(32)  NOT NULL COMMENT '收盘价',
    volume      VARCHAR(32)  DEFAULT NULL COMMENT '成交量',
    close_time  BIGINT       DEFAULT NULL COMMENT '收盘时间(毫秒)',
    quote_asset_volume         VARCHAR(32) DEFAULT NULL COMMENT '成交额',
    number_of_trades           INT         DEFAULT NULL COMMENT '成交笔数',
    taker_buy_base_asset_volume  VARCHAR(32) DEFAULT NULL COMMENT '主动买入成交量',
    taker_buy_quote_asset_volume VARCHAR(32) DEFAULT NULL COMMENT '主动买入成交额',
    create_time DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_symbol_opentime (symbol, open_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线数据表';

CREATE TABLE IF NOT EXISTS favorite_coin (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol      VARCHAR(32)  NOT NULL COMMENT '交易对',
    base_asset  VARCHAR(20)  DEFAULT NULL COMMENT '币种',
    direction   VARCHAR(10)  DEFAULT NULL COMMENT '方向 LONG/SHORT',
    score       INT          DEFAULT 0  COMMENT '评分',
    price       VARCHAR(32)  DEFAULT NULL COMMENT '价格',
    reason      VARCHAR(200) DEFAULT NULL COMMENT '推荐理由',
    volume      VARCHAR(32)  DEFAULT NULL COMMENT '24h成交量',
    change_24h  VARCHAR(16)  DEFAULT NULL COMMENT '24h涨跌幅',
    recommend_time VARCHAR(64) DEFAULT NULL COMMENT '推荐购买时间',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    UNIQUE KEY uk_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收藏币种表';

CREATE TABLE IF NOT EXISTS notification_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol      VARCHAR(32)  NOT NULL COMMENT '交易对',
    type        VARCHAR(20)  NOT NULL COMMENT '通知类型 HIGH_SCORE/FAV_DROP',
    score       INT          DEFAULT NULL COMMENT '触发时评分',
    sent_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    INDEX idx_symbol_type_time (symbol, type, sent_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知日志表';

-- ==================== 策略交易系统 ====================

CREATE TABLE IF NOT EXISTS strategy_config (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                 VARCHAR(50)  NOT NULL COMMENT '策略名称',
    mode                 VARCHAR(20)  NOT NULL COMMENT '模式 EARLY/HIGH',
    volatility_threshold DOUBLE       DEFAULT 24 COMMENT '24H波动率阈值(%)',
    stop_loss_pct        DOUBLE       DEFAULT 80 COMMENT '止损比例(%)',
    profit_protect_pct   DOUBLE       DEFAULT 120 COMMENT '利润保护启动(%)',
    drawdown_exit_pct    DOUBLE       DEFAULT 10 COMMENT '回撤退出(%)',
    initial_capital      DOUBLE       DEFAULT 1000 COMMENT '初始资金',
    position_size        DOUBLE       DEFAULT 200 COMMENT '每笔保证金',
    max_positions        INT          DEFAULT 1 COMMENT '最大持仓数',
    leverage             INT          DEFAULT 20 COMMENT '杠杆倍数',
    enabled              BOOLEAN      DEFAULT TRUE COMMENT '是否启用',
    create_time          DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略配置表';

-- 插入默认配置
INSERT IGNORE INTO strategy_config (id, name, mode, volatility_threshold, stop_loss_pct, profit_protect_pct, drawdown_exit_pct, initial_capital, position_size, max_positions, leverage, enabled)
VALUES (1, '早段动态', 'EARLY', 24, 80, 120, 10, 1000, 200, 1, 20, TRUE);

INSERT IGNORE INTO strategy_config (id, name, mode, volatility_threshold, stop_loss_pct, profit_protect_pct, drawdown_exit_pct, initial_capital, position_size, max_positions, leverage, enabled)
VALUES (2, '高位保护', 'HIGH', 24, 50, 20, 10, 1000, 200, 1, 20, TRUE);

CREATE TABLE IF NOT EXISTS strategy_signal (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol          VARCHAR(32)  NOT NULL COMMENT '交易对',
    base_asset      VARCHAR(20)  DEFAULT NULL COMMENT '币种',
    direction       VARCHAR(10)  DEFAULT NULL COMMENT '方向 LONG/SHORT',
    volatility_24h  DOUBLE       DEFAULT 0 COMMENT '24H波动率',
    price           VARCHAR(32)  DEFAULT NULL COMMENT '触发价格',
    mode            VARCHAR(20)  DEFAULT NULL COMMENT '策略模式',
    score           INT          DEFAULT 0 COMMENT '评分',
    status          VARCHAR(20)  DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/SKIPPED',
    skip_reason     VARCHAR(200) DEFAULT NULL COMMENT '跳过原因',
    detect_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '检测时间',
    process_time    DATETIME     DEFAULT NULL COMMENT '处理时间',
    INDEX idx_symbol_time (symbol, detect_time),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略信号表';

CREATE TABLE IF NOT EXISTS strategy_position (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol              VARCHAR(32)  NOT NULL COMMENT '交易对',
    base_asset          VARCHAR(20)  DEFAULT NULL COMMENT '币种',
    strategy_name       VARCHAR(50)  DEFAULT NULL COMMENT '策略名称',
    margin              DOUBLE       DEFAULT 0 COMMENT '保证金',
    leverage            INT          DEFAULT 20 COMMENT '杠杆',
    open_price          VARCHAR(32)  DEFAULT NULL COMMENT '开仓价',
    current_price       VARCHAR(32)  DEFAULT NULL COMMENT '当前价格',
    direction           VARCHAR(10)  DEFAULT NULL COMMENT 'LONG/SHORT',
    stop_loss_price     VARCHAR(32)  DEFAULT NULL COMMENT '止损价',
    profit_protect_price VARCHAR(32) DEFAULT NULL COMMENT '利润保护价',
    unrealized_pnl      DOUBLE       DEFAULT 0 COMMENT '浮动盈亏',
    pnl_pct             DOUBLE       DEFAULT 0 COMMENT '盈亏率(%)',
    highest_pnl         DOUBLE       DEFAULT 0 COMMENT '最高浮盈',
    max_adverse         DOUBLE       DEFAULT 0 COMMENT '最大逆向',
    order_id            BIGINT       DEFAULT NULL COMMENT 'Binance开仓订单ID',
    close_order_id      BIGINT       DEFAULT NULL COMMENT 'Binance平仓订单ID',
    executed_qty        VARCHAR(32)  DEFAULT NULL COMMENT '实际成交数量',
    status              VARCHAR(10)  DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
    open_time           DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '开仓时间',
    update_time         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略持仓表';

CREATE TABLE IF NOT EXISTS strategy_trade (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol          VARCHAR(32)  NOT NULL COMMENT '交易对',
    base_asset      VARCHAR(20)  DEFAULT NULL COMMENT '币种',
    strategy_name   VARCHAR(50)  DEFAULT NULL COMMENT '策略名称',
    direction       VARCHAR(10)  DEFAULT NULL COMMENT '方向',
    margin          DOUBLE       DEFAULT 0 COMMENT '保证金',
    executed_qty    VARCHAR(32)  DEFAULT NULL COMMENT '实际成交数量',
    leverage        INT          DEFAULT 20 COMMENT '杠杆',
    open_price      VARCHAR(32)  DEFAULT NULL COMMENT '开仓价',
    close_price     VARCHAR(32)  DEFAULT NULL COMMENT '平仓价',
    pnl             DOUBLE       DEFAULT 0 COMMENT '净盈亏',
    pnl_pct         DOUBLE       DEFAULT 0 COMMENT '盈亏率(%)',
    highest_pnl     DOUBLE       DEFAULT 0 COMMENT '最高浮盈',
    max_adverse     DOUBLE       DEFAULT 0 COMMENT '最大逆向',
    hold_duration   INT          DEFAULT 0 COMMENT '持仓时长(分钟)',
    close_reason    VARCHAR(200) DEFAULT NULL COMMENT '平仓原因',
    open_time       DATETIME     DEFAULT NULL COMMENT '开仓时间',
    close_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '平仓时间',
    INDEX idx_close_time (close_time),
    INDEX idx_result (pnl)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略交易记录表';
