package com.binance.web.scheduler;

import com.binance.web.service.CoinService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KlineScheduler {

    @Autowired
    private CoinService coinService;

    /**
     * 每5分钟执行一次K线数据刷新
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void refreshKlineData() {
        log.info("开始定时刷新K线数据...");
        coinService.refreshAllKlines();
        log.info("K线数据刷新完成，已缓存币种数: {}", coinService.getCachedSymbols().size());
    }
}
