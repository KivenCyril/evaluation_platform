package com.hmdp.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.hmdp.dto.ShopDoc;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopSearchService;
import com.hmdp.service.IShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class ShopSearchServiceImpl implements IShopSearchService {

    private final ElasticsearchClient esClient;
    private final IShopService shopService;

    @Value("${es.index.shop:merchant}")
    private String indexName;

    private ShopDoc toDoc(Shop shop) {
        ShopDoc doc = new ShopDoc();
        doc.setId(shop.getId());
        doc.setName(shop.getName());
        doc.setAddress(shop.getAddress());
        doc.setArea(shop.getArea());
        doc.setTypeId(shop.getTypeId());

        if (shop.getScore() != null) {
            doc.setScore(shop.getScore() / 10.0);
        }
        doc.setSold(shop.getSold());
        doc.setComments(shop.getComments());

        int hot = 0;
        if (shop.getComments() != null) hot += shop.getComments();
        if (shop.getSold() != null) hot += shop.getSold();
        doc.setHot(hot);

        if (shop.getY() != null && shop.getX() != null) {
            doc.setLocation(shop.getY() + "," + shop.getX()); // "lat,lon"
        }
        doc.setUpdateTime(shop.getUpdateTime());
        return doc;
    }

    @Override
    public List<Map<String, Object>> search(String keyword, int page, int size) {
        int from = Math.max(0, (page - 1) * size);

        try {
            SearchRequest req = SearchRequest.of(s -> s
                    .index(indexName)
                    .from(from)
                    .size(size)
                    .query(q -> q.multiMatch(mm -> mm
                            .query(keyword)
                            .fields("name^3", "address", "area")
                    ))
                    // 如果不想依赖 hot 排序，可以删掉这一行
                    .sort(so -> so.field(f -> f.field("hot").order(SortOrder.Desc)))
                    .sort(so -> so.field(f -> f.field("score").order(SortOrder.Desc)))
                    .highlight(h -> h
                            .fields("name", hf -> hf)
                            .fields("address", hf -> hf)
                    )
            );

            SearchResponse<ShopDoc> resp = esClient.search(req, ShopDoc.class);

            // 1) 从 ES 命中里拿 id，并收集高亮
            List<Long> ids = new ArrayList<>();
            Map<Long, String> hlNameMap = new HashMap<>();
            Map<Long, String> hlAddressMap = new HashMap<>();

            for (Hit<ShopDoc> hit : resp.hits().hits()) {
                Long id;
                try {
                    id = Long.valueOf(hit.id());
                } catch (Exception e) {
                    ShopDoc doc = hit.source();
                    if (doc == null || doc.getId() == null) continue;
                    id = doc.getId();
                }
                ids.add(id);

                Map<String, List<String>> hl = hit.highlight();
                if (hl != null) {
                    List<String> hn = hl.get("name");
                    if (hn != null && !hn.isEmpty()) hlNameMap.put(id, hn.get(0));
                    List<String> ha = hl.get("address");
                    if (ha != null && !ha.isEmpty()) hlAddressMap.put(id, ha.get(0));
                }
            }

            if (ids.isEmpty()) return Collections.emptyList();

            // 2) MySQL 批量查 Shop，并按 ES 顺序返回（保序很重要）
            String order = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
            List<Shop> shops = shopService.query()
                    .in("id", ids)
                    .last("ORDER BY FIELD(id," + order + ")")
                    .list();

            // 3) 只返回 MySQL 里存在的字段 + highlight
            List<Map<String, Object>> result = new ArrayList<>(shops.size());
            for (Shop shop : shops) {
                Map<String, Object> item = new HashMap<>();
                Long id = shop.getId();

                item.put("id", id);
                item.put("name", shop.getName());
                item.put("address", shop.getAddress());
                item.put("area", shop.getArea());
                item.put("typeId", shop.getTypeId());
                item.put("updateTime", shop.getUpdateTime());

                // 高亮可选：有则加，没有就不加
                String hn = hlNameMap.get(id);
                if (hn != null) item.put("highlightName", hn);
                String ha = hlAddressMap.get(id);
                if (ha != null) item.put("highlightAddress", ha);

                result.add(item);
            }

            return result;
        } catch (Exception e) {
            throw new RuntimeException("ES search failed", e);
        }
    }


    @Override
    public void upsertShop(Long shopId) {
        Shop shop = shopService.getById(shopId);
        if (shop == null) return;

        ShopDoc doc = toDoc(shop);

        // score：黑马是 1~5 * 10 存储，转成 4.8 这种更适合 ES 排序/展示
        if (shop.getScore() != null) {
            doc.setScore(shop.getScore() / 10.0);
        }

        doc.setSold(shop.getSold());
        doc.setComments(shop.getComments());

        // 热度 hot：你可以用 comments 或 sold 做近似
        int hot = 0;
        if (shop.getComments() != null) hot += shop.getComments();
        if (shop.getSold() != null) hot += shop.getSold();
        doc.setHot(hot);

        // 经纬度：ES geo_point 这里用 "lat,lon"（纬度在前）
        if (shop.getY() != null && shop.getX() != null) {
            doc.setLocation(shop.getY() + "," + shop.getX());
        }

        doc.setUpdateTime(shop.getUpdateTime());

        try {
            IndexRequest<ShopDoc> req = IndexRequest.of(i -> i
                    .index(indexName)
                    .id(String.valueOf(shopId))
                    .document(doc)
            );
            esClient.index(req);
        } catch (Exception e) {
            throw new RuntimeException("ES upsert failed", e);
        }
    }

    @Override
    public void deleteShop(Long shopId) {
        try {
            DeleteRequest req = DeleteRequest.of(d -> d.index(indexName).id(String.valueOf(shopId)));
            esClient.delete(req);
        } catch (Exception e) {
            throw new RuntimeException("ES delete failed", e);
        }
    }

    @Override
    public void rebuild() {
        int pageNo = 1;
        int pageSize = 500;

        try {
            while (true) {
                Page<Shop> page = shopService.page(new Page<>(pageNo, pageSize));
                List<Shop> records = page.getRecords();
                if (records == null || records.isEmpty()) break;

                BulkRequest.Builder br = new BulkRequest.Builder();
                for (Shop shop : records) {
                    ShopDoc doc = toDoc(shop);
                    br.operations(op -> op.index(idx -> idx
                            .index(indexName)
                            .id(shop.getId().toString())
                            .document(doc)
                    ));
                }

                BulkResponse resp = esClient.bulk(br.build());
                if (resp.errors()) {
                    throw new RuntimeException("ES bulk rebuild has errors: " + resp.items());
                }

                if (records.size() < pageSize) break;
                pageNo++;
            }
        } catch (Exception e) {
            throw new RuntimeException("ES rebuild failed", e);
        }
    }

}
