package com.binance.web.controller;

import com.binance.web.entity.*;
import com.binance.web.service.StrategyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/strategy")
public class StrategyController {

    @Autowired
    private StrategyService strategyService;

    /** 获取策略概览数据 */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        Map<String, Object> data = strategyService.getOverview();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 0);
        resp.put("data", data);
        return ResponseEntity.ok(resp);
    }

    /** 获取当前持仓 */
    @GetMapping("/positions")
    public ResponseEntity<Map<String, Object>> positions() {
        List<StrategyPosition> list = strategyService.getOpenPositions();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 0);
        resp.put("data", list);
        return ResponseEntity.ok(resp);
    }

    /** 获取交易记录 */
    @GetMapping("/trades")
    public ResponseEntity<Map<String, Object>> trades(
            @RequestParam(value = "filter", required = false) String filter) {
        List<StrategyTrade> list = strategyService.getRecentTrades(filter);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 0);
        resp.put("data", list);
        return ResponseEntity.ok(resp);
    }

    /** 获取信号记录 */
    @GetMapping("/signals")
    public ResponseEntity<Map<String, Object>> signals() {
        List<StrategySignal> list = strategyService.getRecentSignals();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 0);
        resp.put("data", list);
        return ResponseEntity.ok(resp);
    }

    /** 获取每日统计 */
    @GetMapping("/daily-stats")
    public ResponseEntity<Map<String, Object>> dailyStats() {
        List<Map<String, Object>> list = strategyService.getDailyStats();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 0);
        resp.put("data", list);
        return ResponseEntity.ok(resp);
    }

    /** 获取策略配置 */
    @GetMapping("/configs")
    public ResponseEntity<Map<String, Object>> configs() {
        List<StrategyConfig> list = strategyService.getConfigs();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 0);
        resp.put("data", list);
        return ResponseEntity.ok(resp);
    }

    /** 更新策略配置 */
    @PutMapping("/configs")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody StrategyConfig config) {
        StrategyConfig updated = strategyService.updateConfig(config);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 0);
        resp.put("data", updated);
        resp.put("message", "配置更新成功");
        return ResponseEntity.ok(resp);
    }

    /** 手动平仓 */
    @PostMapping("/positions/{id}/close")
    public ResponseEntity<Map<String, Object>> closePosition(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "手动平仓");
        strategyService.manualClose(id, reason);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 0);
        resp.put("message", "平仓成功");
        return ResponseEntity.ok(resp);
    }

    /** 手动触发一次扫描 */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> manualScan() {
        strategyService.scanForSignals();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 0);
        resp.put("message", "扫描完成");
        return ResponseEntity.ok(resp);
    }

    /** 获取监控列表 */
    @GetMapping("/watchlist")
    public ResponseEntity<Map<String, Object>> watchlist() {
        List<String> list = strategyService.getWatchlist();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 0);
        resp.put("data", list);
        return ResponseEntity.ok(resp);
    }

    /** 更新监控列表 */
    @PutMapping("/watchlist")
    public ResponseEntity<Map<String, Object>> updateWatchlist(@RequestBody List<String> list) {
        strategyService.setWatchlist(list);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 0);
        resp.put("message", "监控列表更新成功");
        return ResponseEntity.ok(resp);
    }
}
