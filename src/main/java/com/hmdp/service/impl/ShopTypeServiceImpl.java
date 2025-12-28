package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 店铺类型缓存
     * @return
     */
    public Result typelist() {

        String key = RedisConstants.CACHE_SHOP_TYPE_LIST_KEY;

        // 1) 查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            List<ShopType> list = JSONUtil.toList(json, ShopType.class);
            return Result.ok(list);
        }

        // 2) 查数据库
        List<ShopType> typeList = this.query().orderByAsc("sort").list();

        // 3) 空值缓存（防穿透：极端情况下 typeList 为空）
        if (typeList == null || typeList.isEmpty()) {
            stringRedisTemplate.opsForValue().set(
                    key,
                    "[]",
                    RedisConstants.CACHE_NULL_TTL,
                    TimeUnit.MINUTES
            );
            return Result.ok(typeList);
        }

        // 4) 写缓存（加随机值防雪崩）
        long ttl = RedisConstants.CACHE_SHOP_TYPE_LIST_TTL;
        long randomSeconds = (long) (Math.random() * 300); // 0~300 秒随机
        stringRedisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(typeList),
                ttl * 60 + randomSeconds,
                TimeUnit.SECONDS
        );

        return Result.ok(typeList);
    }
}
