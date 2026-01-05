package com.hmdp.service;

import java.util.List;
import java.util.Map;

public interface IShopSearchService {
    List<Map<String, Object>> search(String keyword, int page, int size);
    void upsertShop(Long shopId);
    void deleteShop(Long shopId);
    // 新增：从 MySQL 全量导入到 ES
    void rebuild();
}
