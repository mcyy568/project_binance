package com.binance.web.controller;

import com.binance.web.entity.Recommendation;
import com.binance.web.service.RecommendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class RecommendController {

    @Autowired
    private RecommendService recommendService;

    /**
     * 获取当前推荐列表
     */
    @GetMapping("/recommendations")
    public ResponseEntity<Map<String, Object>> getRecommendations() {
        List<Recommendation> list = recommendService.getRecommendations();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", list);
        result.put("total", list.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 手动触发一次扫描
     */
    @PostMapping("/recommendations/scan")
    public ResponseEntity<Map<String, Object>> triggerScan() {
        recommendService.scanAndRecommend();
        return getRecommendations();
    }
}
