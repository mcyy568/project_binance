package com.binance.web.service;

import com.binance.web.config.BinanceProperties;
import com.binance.web.entity.FavoriteCoin;
import com.binance.web.entity.NotificationLog;
import com.binance.web.entity.Recommendation;
import com.binance.web.mapper.FavoriteMapper;
import com.binance.web.mapper.NotificationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NotificationService {

    private static final String TYPE_HIGH = "HIGH_SCORE";
    private static final String TYPE_FAV_DROP = "FAV_DROP";

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private BinanceProperties binanceProperties;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private FavoriteMapper favoriteMapper;

    @Value("${spring.mail.username}")
    private String mailFrom;

    /**
     * 检查高分推荐（>= 70）并发送通知
     */
    public void notifyHighScore(List<Recommendation> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) return;

        List<Recommendation> highScore = recommendations.stream()
                .filter(r -> r.getScore() >= 70)
                .toList();

        List<Recommendation> toNotify = new ArrayList<>();
        for (Recommendation r : highScore) {
            NotificationLog recent = notificationMapper.findRecent(r.getSymbol(), TYPE_HIGH);
            if (recent == null) {
                toNotify.add(r);
                notificationMapper.insert(new NotificationLog(r.getSymbol(), TYPE_HIGH, r.getScore()));
            }
        }

        if (!toNotify.isEmpty()) {
            sendHighScoreEmail(toNotify);
        }
    }

    /**
     * 检查收藏币种评分 <= 70 并发送通知
     */
    public void notifyFavDrop(List<Recommendation> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) return;

        List<FavoriteCoin> favs = favoriteMapper.findAll();
        Set<String> favSymbols = favs.stream().map(FavoriteCoin::getSymbol).collect(Collectors.toSet());

        // 从扫描结果中找收藏币种评分 <= 70
        List<Recommendation> dropped = recommendations.stream()
                .filter(r -> favSymbols.contains(r.getSymbol()))
                .filter(r -> r.getScore() <= 70)
                .toList();

        List<Recommendation> toNotify = new ArrayList<>();
        for (Recommendation r : dropped) {
            NotificationLog recent = notificationMapper.findRecent(r.getSymbol(), TYPE_FAV_DROP);
            if (recent == null) {
                toNotify.add(r);
                notificationMapper.insert(new NotificationLog(r.getSymbol(), TYPE_FAV_DROP, r.getScore()));
            }
        }

        if (!toNotify.isEmpty()) {
            sendFavDropEmail(toNotify);
        }
    }

    private void sendHighScoreEmail(List<Recommendation> list) {
        String to = binanceProperties.getNotification().getEmail();
        if (to == null || to.isBlank() || to.contains("example")) {
            log.info("未配置通知邮箱，跳过发送（HIGH_SCORE: {} 个）", list.size());
            return;
        }

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(mailFrom);
        msg.setTo(to);
        msg.setSubject(String.format("[Binance] %s 高分推荐通知（%d条）",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), list.size()));

        StringBuilder sb = new StringBuilder();
        sb.append("以下币种评分 >= 70，建议关注：\n\n");
        sb.append(String.format("%-10s %-6s %5s %12s %s\n", "币种", "方向", "评分", "价格", "理由"));
        sb.append("────────────────────────────────────────────\n");
        for (Recommendation r : list) {
            sb.append(String.format("%-10s %-6s %5d %12s %s\n",
                    r.getBaseAsset(),
                    "LONG".equals(r.getDirection()) ? "做多" : "做空",
                    r.getScore(),
                    "$" + formatPrice(r.getPrice()),
                    r.getReason() != null ? r.getReason() : ""));
        }
        sb.append("\n—— Binance 自动推荐系统");

        msg.setText(sb.toString());
        mailSender.send(msg);
        log.info("高分通知邮件已发送：{} 个币种 -> {}", list.size(), to);
    }

    private void sendFavDropEmail(List<Recommendation> list) {
        String to = binanceProperties.getNotification().getEmail();
        if (to == null || to.isBlank() || to.contains("example")) {
            log.info("未配置通知邮箱，跳过发送（FAV_DROP: {} 个）", list.size());
            return;
        }

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(mailFrom);
        msg.setTo(to);
        msg.setSubject(String.format("[Binance] %s 收藏币种评分下降提醒（%d条）",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), list.size()));

        StringBuilder sb = new StringBuilder();
        sb.append("以下收藏币种评分已降至 <= 70，请注意风险：\n\n");
        sb.append(String.format("%-10s %-6s %5s %12s %s\n", "币种", "方向", "评分", "价格", "理由"));
        sb.append("────────────────────────────────────────────\n");
        for (Recommendation r : list) {
            sb.append(String.format("%-10s %-6s %5d %12s %s\n",
                    r.getBaseAsset(),
                    "LONG".equals(r.getDirection()) ? "做多" : "做空",
                    r.getScore(),
                    "$" + formatPrice(r.getPrice()),
                    r.getReason() != null ? r.getReason() : ""));
        }
        sb.append("\n—— Binance 自动推荐系统");

        msg.setText(sb.toString());
        mailSender.send(msg);
        log.info("收藏下跌通知邮件已发送：{} 个币种 -> {}", list.size(), to);
    }

    private String formatPrice(String price) {
        try {
            double p = Double.parseDouble(price);
            if (p >= 1000) return String.format("%.0f", p);
            if (p >= 1) return String.format("%.2f", p);
            if (p >= 0.01) return String.format("%.4f", p);
            return String.format("%.6f", p);
        } catch (NumberFormatException e) {
            return price;
        }
    }
}
