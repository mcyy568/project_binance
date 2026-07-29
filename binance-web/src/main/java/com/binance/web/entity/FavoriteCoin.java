package com.binance.web.entity;

import java.time.LocalDateTime;

public class FavoriteCoin {
    private Long id;
    private String symbol;
    private String baseAsset;
    private String direction;    // LONG / SHORT
    private Integer score;
    private String price;
    private String reason;
    private String volume;
    private String change24h;
    private LocalDateTime createTime;

    public FavoriteCoin() {}

    /** 从推荐结果构造 */
    public FavoriteCoin(Recommendation rec) {
        this.symbol = rec.getSymbol();
        this.baseAsset = rec.getBaseAsset();
        this.direction = rec.getDirection();
        this.score = rec.getScore();
        this.price = rec.getPrice();
        this.reason = rec.getReason();
        this.volume = rec.getVolume();
        this.change24h = rec.getChange24h();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getBaseAsset() { return baseAsset; }
    public void setBaseAsset(String baseAsset) { this.baseAsset = baseAsset; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public String getPrice() { return price; }
    public void setPrice(String price) { this.price = price; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getVolume() { return volume; }
    public void setVolume(String volume) { this.volume = volume; }

    public String getChange24h() { return change24h; }
    public void setChange24h(String change24h) { this.change24h = change24h; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
