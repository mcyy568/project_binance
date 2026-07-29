package com.binance.web.entity;

import java.time.LocalDateTime;

/**
 * 邮件通知日志 - 防止重复发送
 */
public class NotificationLog {
    private Long id;
    private String symbol;
    private String type;      // HIGH_SCORE / FAV_DROP
    private Integer score;
    private LocalDateTime sentTime;

    public NotificationLog() {}

    public NotificationLog(String symbol, String type, Integer score) {
        this.symbol = symbol;
        this.type = type;
        this.score = score;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public LocalDateTime getSentTime() { return sentTime; }
    public void setSentTime(LocalDateTime sentTime) { this.sentTime = sentTime; }
}
