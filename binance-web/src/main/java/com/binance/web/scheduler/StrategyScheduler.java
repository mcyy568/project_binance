package com.binance.web.scheduler;

import com.binance.web.service.StrategyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 策略交易调度器 - 每1分钟执行一次
 */
@Slf4j
@Component
public class StrategyScheduler {

    @Autowired
    private StrategyService strategyService;

    /**
     * 启动后延迟初始化
     */
    @PostConstruct
    public void init() {
        new Thread(() -> {
            try {
                Thread.sleep(8000);
            } catch (InterruptedException ignored) {
            }
            log.info("策略交易引擎启动...");
            strategyService.tick();
        }).start();
    }

    /**
     * 每1分钟执行策略引擎
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void scheduledTick() {
        log.debug("策略引擎 tick...");
        strategyService.tick();
    }
}
