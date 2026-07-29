package com.binance.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.binance.web.mapper")
public class BinanceWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(BinanceWebApplication.class, args);
    }
}
