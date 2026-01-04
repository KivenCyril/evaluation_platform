package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisIdWorker redisIdWorker;

    private static final ExecutorService ES = Executors.newFixedThreadPool(500);

    @AfterAll
    static void shutdownPool() {
        ES.shutdown();
    }

    /**
     * 并发生成ID：校验唯一性 + 统计耗时
     */
    @Test
    void testIdWorker() throws InterruptedException {
        final int taskCount = 300;
        final int idsPerTask = 100;
        final int expected = taskCount * idsPerTask;

        CountDownLatch latch = new CountDownLatch(taskCount);
        Set<Long> ids = ConcurrentHashMap.newKeySet(expected);

        Runnable task = () -> {
            try {
                for (int i = 0; i < idsPerTask; i++) {
                    long id = redisIdWorker.nextId("order");
                    System.out.println("id = "+ id);
                    ids.add(id);
                }
            } finally {
                latch.countDown();
            }
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < taskCount; i++) {
            ES.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();

        System.out.println("generated=" + ids.size()
                + ", expected=" + expected
                + ", cost(ms)=" + (end - begin));

        // 如果 size 小于 expected，说明有重复（Set 会去重）
        Assertions.assertEquals(expected, ids.size(), "ID出现重复，请检查生成逻辑或 Redis 配置");
    }

}
