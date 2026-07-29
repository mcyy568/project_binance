package com.binance.web.scheduler;

import com.binance.web.service.NotificationService;
import com.binance.web.service.RecommendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 推荐扫描调度器 - 每10分钟扫描全市场USDT永续合约
 */
@Slf4j
@Component
public class RecommendScheduler {

    @Autowired
    private RecommendService recommendService;

    @Autowired
    private NotificationService notificationService;

    /**
     * 启动后延迟初始化
     */
    @PostConstruct
    public void init() {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
            }
            log.info("推荐扫描初始化启动...");
            recommendService.scanAndRecommend();
        }).start();
    }

    /**
     * 每5分钟自动扫描
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void scheduledScan() {
        recommendService.scanAndRecommend();

        // 邮件通知调度器
        scheduledNotify();
    }

    /**
     * 邮件通知调度器
     */
    public void scheduledNotify() {
        log.info("--------- 邮件通知调度器 ---------");
        var recommendations = recommendService.getRecommendations();
        notificationService.notifyHighScore(recommendations);
        notificationService.notifyFavDrop(recommendations);
    }

}
