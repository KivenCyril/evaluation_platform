package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.Voucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IUserService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    @Resource
    private IUserService userService;

    private static final ExecutorService ES = Executors.newFixedThreadPool(500);

    @AfterAll
    static void shutdownPool() {
        ES.shutdown();
    }
    
    /**
     * 生成 5000 个用户并保存 Token 到 tokens.txt，用于 JMeter 压测
     */
    @Test
    void createTokensForJMeter() throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter("tokens.txt"));
        
        for (int i = 0; i < 5000; i++) {
            // 1.创建用户
            User user = new User();
            user.setPhone("1380000" + String.format("%04d", i));
            user.setNickName("user_" + i);
            if(userService.getPhone(user.getPhone()) == null){
                userService.save(user);
            } else {
                user = userService.getPhone(user.getPhone());
            }

            // 2.保存用户到Redis
            String token = UUID.randomUUID().toString(true);
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
            
            String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
            
            // 3.写入文件
            writer.write(token);
            writer.newLine();
        }
        writer.close();
        System.out.println("Token 生成完成，已写入 tokens.txt");
    }

    /**
     * 重置压测数据（清理订单、重置库存）
     */
    @Test
    void resetBenchmarkData() {
        Long voucherId = 2L;
        int stock = 100;

        // 1. 重置数据库库存
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher != null) {
            seckillVoucher.setStock(stock);
            seckillVoucherService.updateById(seckillVoucher);
        }

        // 2. 清理数据库订单
        voucherOrderService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.hmdp.entity.VoucherOrder>().eq("voucher_id", voucherId));

        // 3. 重置 Redis 数据
        stringRedisTemplate.opsForValue().set("seckill:stock:" + voucherId, String.valueOf(stock));
        stringRedisTemplate.delete("seckill:order:" + voucherId);
        
        System.out.println("数据已重置：库存=" + stock + ", 历史订单已清理");
    }

    /**
     * 压测秒杀业务
     */
    @Test
    void testSeckillBenchmark() throws InterruptedException {
        // 1. 准备数据
        Long voucherId = 2L; // 使用 ID=2 的券进行测试
        int stock = 100;

        // 1.1 准备数据库数据 (如果不存在则插入)
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null) {
            voucher = new Voucher();
            voucher.setId(voucherId);
            voucher.setShopId(1L);
            voucher.setTitle("测试秒杀券");
            voucher.setPayValue(100L);
            voucher.setActualValue(1L);
            voucher.setType(1); // 秒杀券
            voucher.setStatus(1);
            voucherService.save(voucher);
        }

        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            seckillVoucher = new SeckillVoucher();
            seckillVoucher.setVoucherId(voucherId);
            seckillVoucher.setStock(stock);
            seckillVoucher.setBeginTime(LocalDateTime.now().minusHours(1));
            seckillVoucher.setEndTime(LocalDateTime.now().plusHours(1));
            seckillVoucherService.save(seckillVoucher);
        } else {
            // 重置库存
            seckillVoucher.setStock(stock);
            seckillVoucherService.updateById(seckillVoucher);
        }

        // 1.2 准备 Redis 数据
        stringRedisTemplate.opsForValue().set("seckill:stock:" + voucherId, String.valueOf(stock));
        stringRedisTemplate.delete("seckill:order:" + voucherId); // 清理之前的订单记录
        voucherOrderService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.hmdp.entity.VoucherOrder>().eq("voucher_id", voucherId)); // 清理数据库订单

        System.out.println("数据准备完成，开始压测...");

        // 2. 多线程并发抢购
        int userCount = 200; // 模拟 200 个用户
        CountDownLatch latch = new CountDownLatch(userCount);
        AtomicInteger successCount = new AtomicInteger(0);

        Runnable task = () -> {
            try {
                // 模拟不同用户
                long userId = redisIdWorker.nextId("user");
                UserDTO user = new UserDTO();
                user.setId(userId);
                user.setNickName("User-" + userId);
                UserHolder.saveUser(user);

                // 执行秒杀
                long start = System.currentTimeMillis();
                com.hmdp.dto.Result result = voucherOrderService.seckillVoucher(voucherId);
                long end = System.currentTimeMillis();

                if (result.getSuccess()) {
                    successCount.incrementAndGet();
                    // System.out.println("用户 " + userId + " 抢购成功, 耗时: " + (end - start) + "ms");
                } else {
                    // System.out.println("用户 " + userId + " 抢购失败: " + result.getErrorMsg());
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                UserHolder.removeUser();
                latch.countDown();
            }
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < userCount; i++) {
            ES.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();

        System.out.println("压测结束，总耗时: " + (end - begin) + "ms");
        System.out.println("实际成功请求数(Redis层): " + successCount.get());

        // 3. 等待 MQ 消费 (异步入库)
        System.out.println("等待 MQ 消费 (5秒)...");
        Thread.sleep(5000);

        // 4. 验证结果
        SeckillVoucher finalVoucher = seckillVoucherService.getById(voucherId);
        System.out.println("最终数据库库存: " + finalVoucher.getStock());

        int orderCount = voucherOrderService.query().eq("voucher_id", voucherId).count();
        System.out.println("最终数据库订单数: " + orderCount);

        // 验证: 订单数应该等于初始库存 (因为请求数 200 > 库存 100)
        // 注意：这里假设没有其他干扰，且 MQ 消费完全成功
        Assertions.assertTrue(orderCount <= stock, "订单数超过库存，存在超卖！");
        Assertions.assertEquals(stock, orderCount, "订单数不等于库存数，可能存在少卖或 MQ 积压");
    }

    /**
     * 压测秒杀业务 (同步下单 - 不走MQ)
     */
    @Test
    void testSeckillBenchmarkSync() throws InterruptedException {
        // 1. 准备数据
        Long voucherId = 2L;
        int stock = 100;

        // 1.1 准备数据库数据
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            seckillVoucher = new SeckillVoucher();
            seckillVoucher.setVoucherId(voucherId);
            seckillVoucher.setStock(stock);
            seckillVoucher.setBeginTime(LocalDateTime.now().minusHours(1));
            seckillVoucher.setEndTime(LocalDateTime.now().plusHours(1));
            seckillVoucherService.save(seckillVoucher);
        } else {
            seckillVoucher.setStock(stock);
            seckillVoucherService.updateById(seckillVoucher);
        }

        // 1.2 准备 Redis 数据
        stringRedisTemplate.opsForValue().set("seckill:stock:" + voucherId, String.valueOf(stock));
        stringRedisTemplate.delete("seckill:order:" + voucherId); // 清理之前的订单记录
        voucherOrderService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.hmdp.entity.VoucherOrder>().eq("voucher_id", voucherId)); // 清理数据库订单

        System.out.println("数据准备完成，开始同步压测...");

        // 2. 多线程并发抢购
        int userCount = 200; // 模拟 200 个用户
        CountDownLatch latch = new CountDownLatch(userCount);
        AtomicInteger successCount = new AtomicInteger(0);

        Runnable task = () -> {
            try {
                // 模拟不同用户
                long userId = redisIdWorker.nextId("user");
                UserDTO user = new UserDTO();
                user.setId(userId);
                user.setNickName("User-" + userId);
                UserHolder.saveUser(user);

                // 执行秒杀 (同步)
                long start = System.currentTimeMillis();
                com.hmdp.dto.Result result = voucherOrderService.seckillVoucherSync(voucherId);
                long end = System.currentTimeMillis();

                if (result.getSuccess()) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                UserHolder.removeUser();
                latch.countDown();
            }
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < userCount; i++) {
            ES.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();

        System.out.println("同步压测结束，总耗时: " + (end - begin) + "ms");
        System.out.println("实际成功请求数: " + successCount.get());

        // 4. 验证结果
        SeckillVoucher finalVoucher = seckillVoucherService.getById(voucherId);
        System.out.println("最终数据库库存: " + finalVoucher.getStock());

        int orderCount = voucherOrderService.query().eq("voucher_id", voucherId).count();
        System.out.println("最终数据库订单数: " + orderCount);
    }

}
