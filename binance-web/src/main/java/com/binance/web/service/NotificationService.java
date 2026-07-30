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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

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
     * 检查高分推荐（>= 80）并发送通知
     */
    public void notifyHighScore(List<Recommendation> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) return;

        List<Recommendation> highScore = recommendations.stream()
                .filter(r -> r.getScore() >= 80)
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
     * 检查收藏币种评分 <= 80 并发送通知
     */
    public void notifyFavDrop(List<Recommendation> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) return;

        List<FavoriteCoin> favs = favoriteMapper.findAll();
        Set<String> favSymbols = favs.stream().map(FavoriteCoin::getSymbol).collect(Collectors.toSet());

        // 从扫描结果中找收藏币种评分 <= 80
        List<Recommendation> dropped = recommendations.stream()
                .filter(r -> favSymbols.contains(r.getSymbol()))
                .filter(r -> r.getScore() <= 80)
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

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(to);
            helper.setSubject(String.format("[Binance] %s 高分推荐通知（%d条）",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), list.size()));

            String html = buildTableHtml("以下币种评分 >= 80，建议关注：", "#fff3cd", list);
            helper.setText(html, true);
            mailSender.send(msg);
            log.info("高分通知邮件已发送：{} 个币种 -> {}", list.size(), to);
        } catch (MessagingException e) {
            log.error("发送高分邮件失败: {}", e.getMessage());
        }
    }

    private void sendFavDropEmail(List<Recommendation> list) {
        String to = binanceProperties.getNotification().getEmail();
        if (to == null || to.isBlank() || to.contains("example")) {
            log.info("未配置通知邮箱，跳过发送（FAV_DROP: {} 个）", list.size());
            return;
        }

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(to);
            helper.setSubject(String.format("[Binance] %s 收藏币种评分下降提醒（%d条）",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), list.size()));

            String html = buildTableHtml("以下收藏币种评分已降至 <= 80，请注意风险：", "#f8d7da", list);
            helper.setText(html, true);
            mailSender.send(msg);
            log.info("收藏下跌通知邮件已发送：{} 个币种 -> {}", list.size(), to);
        } catch (MessagingException e) {
            log.error("发送收藏下跌邮件失败: {}", e.getMessage());
        }
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

    private String formatPercent(String val) {
        if (val == null) return "-";
        try {
            double v = Double.parseDouble(val);
            return String.format("%.2f%%", v);
        } catch (NumberFormatException e) {
            return val;
        }
    }

    private String formatVolume(String val) {
        if (val == null) return "-";
        try {
            double v = Double.parseDouble(val);
            if (v >= 1_000_000_000) return String.format("%.2fB", v / 1_000_000_000);
            if (v >= 1_000_000) return String.format("%.2fM", v / 1_000_000);
            if (v >= 1_000) return String.format("%.2fK", v / 1_000);
            return String.format("%.2f", v);
        } catch (NumberFormatException e) {
            return val;
        }
    }

    private double tryParseDouble(String val) {
        if (val == null) return 0;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 构建带边框和背景色的 HTML 表格
     * @param desc 描述文字
     * @param headerBg 表头背景色（如 #fff3cd 黄色 / #f8d7da 红色）
     * @param list 推荐列表
     */
    private String buildTableHtml(String desc, String headerBg, List<Recommendation> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f5f5f5; padding: 20px;">
                """);

        sb.append("<p style='color:#333; font-size:14px;'>").append(desc).append("</p>");

        // 表格：边框、圆角、阴影
        sb.append("""
                <table style="width:100%; border-collapse: collapse; border: 1px solid #ddd;
                              border-radius: 6px; overflow: hidden; box-shadow: 0 2px 4px rgba(0,0,0,0.1);">
                """);

        // 表头
        sb.append("<thead>");
        sb.append("<tr style='background-color: ").append(headerBg).append("; border: 1px solid #ddd;'>");
        sb.append("<th style='padding: 8px 10px; text-align: left; border: 1px solid #ddd; font-size: 12px;'>币种</th>");
        sb.append("<th style='padding: 8px 10px; text-align: left; border: 1px solid #ddd; font-size: 12px;'>方向</th>");
        sb.append("<th style='padding: 8px 10px; text-align: left; border: 1px solid #ddd; font-size: 12px;'>评分</th>");
        sb.append("<th style='padding: 8px 10px; text-align: left; border: 1px solid #ddd; font-size: 12px;'>价格</th>");
        sb.append("<th style='padding: 8px 10px; text-align: left; border: 1px solid #ddd; font-size: 12px;'>24h涨跌</th>");
        sb.append("<th style='padding: 8px 10px; text-align: left; border: 1px solid #ddd; font-size: 12px;'>24h成交量</th>");
        sb.append("<th style='padding: 8px 10px; text-align: left; border: 1px solid #ddd; font-size: 12px;'>信号</th>");
        sb.append("<th style='padding: 8px 10px; text-align: left; border: 1px solid #ddd; font-size: 12px;'>理由</th>");
        sb.append("</tr>");
        sb.append("</thead>");

        // 表体 - 奇偶行不同背景色
        sb.append("<tbody>");
        for (int i = 0; i < list.size(); i++) {
            Recommendation r = list.get(i);
            String rowBg = (i % 2 == 0) ? "#ffffff" : "#fafafa";
            String direction = "LONG".equals(r.getDirection()) ? "做多" : "做空";
            String dirColor = "LONG".equals(r.getDirection()) ? "#28a745" : "#dc3545";
            String scoreColor = r.getScore() >= 80 ? "#28a745" : r.getScore() >= 70 ? "#ffc107" : "#dc3545";
            // 24h涨跌幅颜色
            double change24h = tryParseDouble(r.getChange24h());
            String changeColor = change24h > 0 ? "#28a745" : change24h < 0 ? "#dc3545" : "#666";
            String changeSign = change24h > 0 ? "+" : "";

            sb.append("<tr style='background-color: ").append(rowBg).append("; border: 1px solid #ddd;'>");
            sb.append("<td style='padding: 6px 10px; border: 1px solid #ddd; font-weight: bold;'>")
              .append(r.getBaseAsset()).append("</td>");
            sb.append("<td style='padding: 6px 10px; border: 1px solid #ddd; color: ").append(dirColor)
              .append("; font-weight: bold;'>").append(direction).append("</td>");
            sb.append("<td style='padding: 6px 10px; border: 1px solid #ddd; color: ").append(scoreColor)
              .append("; font-weight: bold;'>").append(r.getScore()).append("</td>");
            sb.append("<td style='padding: 6px 10px; border: 1px solid #ddd;'>$")
              .append(formatPrice(r.getPrice())).append("</td>");
            sb.append("<td style='padding: 6px 10px; border: 1px solid #ddd; color: ").append(changeColor)
              .append(";'>").append(changeSign).append(formatPercent(r.getChange24h())).append("</td>");
            sb.append("<td style='padding: 6px 10px; border: 1px solid #ddd;'>")
              .append(formatVolume(r.getVolume())).append("</td>");
            sb.append("<td style='padding: 6px 10px; border: 1px solid #ddd;'>")
              .append(r.getSignal() != null ? r.getSignal() : "").append("</td>");
            sb.append("<td style='padding: 6px 10px; border: 1px solid #ddd;'>")
              .append(r.getReason() != null ? r.getReason() : "").append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody>");
        sb.append("</table>");

        sb.append("<p style='color:#888; font-size:12px; margin-top: 16px;'>—— Binance 自动推荐系统</p>");
        sb.append("</body></html>");

        return sb.toString();
    }
}
