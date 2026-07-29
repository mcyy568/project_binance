package com.binance.web.controller;

import com.binance.web.entity.FavoriteCoin;
import com.binance.web.mapper.FavoriteMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/favorites")
@CrossOrigin(origins = "*")
public class FavoriteController {

    @Autowired
    private FavoriteMapper favoriteMapper;

    /** 获取所有收藏 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        List<FavoriteCoin> list = favoriteMapper.findAll();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", list);
        result.put("total", list.size());
        return ResponseEntity.ok(result);
    }

    /** 添加收藏 */
    @PostMapping("/{symbol}")
    public ResponseEntity<Map<String, Object>> add(@PathVariable String symbol, @RequestBody FavoriteCoin coin) {
        Map<String, Object> result = new HashMap<>();
        coin.setSymbol(symbol);

        if (favoriteMapper.existsBySymbol(symbol) > 0) {
            favoriteMapper.update(coin);
            result.put("code", 0);
            result.put("message", "已更新收藏");
        } else {
            favoriteMapper.insert(coin);
            result.put("code", 0);
            result.put("message", "收藏成功");
        }
        return ResponseEntity.ok(result);
    }

    /** 取消收藏 */
    @DeleteMapping("/{symbol}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable String symbol) {
        favoriteMapper.deleteBySymbol(symbol);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "已取消收藏");
        return ResponseEntity.ok(result);
    }

    /** 检查是否已收藏 */
    @GetMapping("/{symbol}/exists")
    public ResponseEntity<Map<String, Object>> exists(@PathVariable String symbol) {
        boolean exists = favoriteMapper.existsBySymbol(symbol) > 0;
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", exists);
        return ResponseEntity.ok(result);
    }
}
