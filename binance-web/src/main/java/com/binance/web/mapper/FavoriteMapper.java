package com.binance.web.mapper;

import com.binance.web.entity.FavoriteCoin;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface FavoriteMapper {

    @Select("SELECT * FROM favorite_coin ORDER BY create_time DESC")
    @Results(id = "favoriteResult", value = {
            @Result(property = "baseAsset", column = "base_asset"),
            @Result(property = "change24h", column = "change_24h"),
            @Result(property = "recommendTime", column = "recommend_time"),
            @Result(property = "createTime", column = "create_time")
    })
    List<FavoriteCoin> findAll();

    @Select("SELECT * FROM favorite_coin WHERE symbol = #{symbol}")
    @ResultMap("favoriteResult")
    FavoriteCoin findBySymbol(@Param("symbol") String symbol);

    @Select("SELECT COUNT(*) FROM favorite_coin WHERE symbol = #{symbol}")
    int existsBySymbol(@Param("symbol") String symbol);

    @Insert("INSERT INTO favorite_coin (symbol, base_asset, direction, score, price, reason, volume, change_24h, recommend_time) " +
            "VALUES (#{symbol}, #{baseAsset}, #{direction}, #{score}, #{price}, #{reason}, #{volume}, #{change24h}, #{recommendTime})")
    int insert(FavoriteCoin coin);

    @Update("UPDATE favorite_coin SET base_asset=#{baseAsset}, direction=#{direction}, score=#{score}, " +
            "price=#{price}, reason=#{reason}, volume=#{volume}, change_24h=#{change24h}, recommend_time=#{recommendTime} WHERE symbol=#{symbol}")
    int update(FavoriteCoin coin);

    @Delete("DELETE FROM favorite_coin WHERE symbol = #{symbol}")
    int deleteBySymbol(@Param("symbol") String symbol);
}
