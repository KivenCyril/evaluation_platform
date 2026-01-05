package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ShopDoc {
    private Long id;
    private String name;
    private String address;
    private String area;       // 商圈
    private Long typeId;       // 类型
    private Double score;      // 4.8 这种
    private Integer sold;
    private Integer comments;
    private Integer hot;       // 你定义的热度
    private String location;   // "lat,lon"
    private LocalDateTime updateTime;
    private List<String> tags;

}
