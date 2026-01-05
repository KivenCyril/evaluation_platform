package com.hmdp.controller;

import com.hmdp.service.IShopSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/shop/es")
@RequiredArgsConstructor
public class ShopSearchController {

    private final IShopSearchService shopSearchService;

    @GetMapping("/search")
    public List<Map<String, Object>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return shopSearchService.search(keyword, page, size);
    }
    // 1) 全量重建 ES 索引数据（从 MySQL 导入）
    @PostMapping("/rebuild")
    public String rebuild() {
        shopSearchService.rebuild();
        return "OK";
    }

    // 2) 单个 shop 同步到 ES（MySQL -> ES）
    @PostMapping("/upsert/{id}")
    public String upsert(@PathVariable("id") Long id) {
        shopSearchService.upsertShop(id);
        return "OK";
    }

    // 3) 单个 shop 从 ES 删除
    @DeleteMapping("/delete/{id}")
    public String delete(@PathVariable("id") Long id) {
        shopSearchService.deleteShop(id);
        return "OK";
    }
}
