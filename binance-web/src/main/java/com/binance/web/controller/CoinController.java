package com.binance.web.controller;

import com.binance.web.entity.CoinInfo;
import com.binance.web.entity.KlineData;
import com.binance.web.service.CoinService;
import com.binance.web.service.RecommendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CoinController {

    @Autowired
    private CoinService coinService;

    @Autowired
    private RecommendService recommendService;

    /**
     * 获取所有USDT交易对币种列表
     */
    @GetMapping("/coins")
    public ResponseEntity<Map<String, Object>> getCoins() {
        List<CoinInfo> coins = coinService.getCoinList();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", coins);
        result.put("total", coins.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 手动计算单个币种评分
     */
    @PostMapping("/coins/{symbol}/score")
    public ResponseEntity<Map<String, Object>> scoreCoin(@PathVariable String symbol) {
        Map<String, Object> result = recommendService.scoreCoin(symbol);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 0);
        resp.put("data", result);
        return ResponseEntity.ok(resp);
    }

    /**
     * 获取指定币种的15分钟K线数据
     */
    @GetMapping("/klines/{symbol}")
    public ResponseEntity<Map<String, Object>> getKlines(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "15m") String interval,
            @RequestParam(defaultValue = "100") int limit) {
        List<KlineData> klines = coinService.getKlines(symbol, interval, limit);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", klines);
        result.put("total", klines.size());
        return ResponseEntity.ok(result);
    }
}
