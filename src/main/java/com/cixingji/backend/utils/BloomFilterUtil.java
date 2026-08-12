//package com.cixingji.backend.utils;
//
//import com.google.common.hash.BloomFilter;
//import com.google.common.hash.Funnels;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.PostConstruct;
//import java.nio.charset.Charset;
//import java.util.List;
//
///**
// * 布隆过滤器工具类
// * 用于防止缓存穿透，判断数据是否可能存在
// */
//@Component
//@Slf4j
//public class BloomFilterUtil {
//
//    @Autowired
//    private RedisUtil redisUtil;
//
//    // 布隆过滤器实例
//    private BloomFilter<String> bloomFilter;
//
//    // 预期插入的数据量
//    private static final int EXPECTED_INSERTIONS = 1000000;
//
//    // 误判率
//    private static final double FALSE_POSITIVE_RATE = 0.01;
//
//    /**
//     * 初始化布隆过滤器
//     */
//    @PostConstruct
//    public void initBloomFilter() {
//        try {
//            // 创建布隆过滤器
//            bloomFilter = BloomFilter.create(
//                Funnels.stringFunnel(Charset.defaultCharset()),
//                EXPECTED_INSERTIONS,
//                FALSE_POSITIVE_RATE
//            );
//
//            // 从Redis中恢复布隆过滤器数据
//            loadBloomFilterFromRedis();
//
//            log.info("布隆过滤器初始化完成，预期插入量：{}，误判率：{}", EXPECTED_INSERTIONS, FALSE_POSITIVE_RATE);
//        } catch (Exception e) {
//            log.error("布隆过滤器初始化失败", e);
//        }
//    }
//
//    /**
//     * 添加元素到布隆过滤器
//     * @param value 要添加的值
//     */
//    public void add(String value) {
//        if (value != null && !value.trim().isEmpty()) {
//            bloomFilter.put(value);
//            // 同时存储到Redis中
//            redisUtil.setValue("bloom_filter:" + value.hashCode(), "1");
//        }
//    }
//
//    /**
//     * 批量添加元素到布隆过滤器
//     * @param values 要添加的值列表
//     */
//    public void addAll(List<String> values) {
//        if (values != null && !values.isEmpty()) {
//            for (String value : values) {
//                add(value);
//            }
//        }
//    }
//
//    /**
//     * 判断元素是否可能存在
//     * @param value 要检查的值
//     * @return true表示可能存在，false表示一定不存在
//     */
//    public boolean mightContain(String value) {
//        if (value == null || value.trim().isEmpty()) {
//            return false;
//        }
//        return bloomFilter.mightContain(value);
//    }
//
//    /**
//     * 获取布隆过滤器的预期插入量
//     */
//    public long getExpectedInsertions() {
//        return bloomFilter.approximateElementCount();
//    }
//
//    /**
//     * 从Redis中加载布隆过滤器数据
//     */
//    private void loadBloomFilterFromRedis() {
//        try {
//            // 这里可以根据实际需求从Redis中恢复布隆过滤器数据
//            // 由于布隆过滤器的内部状态比较复杂，这里简化处理
//            log.info("从Redis加载布隆过滤器数据");
//        } catch (Exception e) {
//            log.error("从Redis加载布隆过滤器数据失败", e);
//        }
//    }
//
//    /**
//     * 清空布隆过滤器
//     */
//    public void clear() {
//        bloomFilter = BloomFilter.create(
//            Funnels.stringFunnel(Charset.defaultCharset()),
//            EXPECTED_INSERTIONS,
//            FALSE_POSITIVE_RATE
//        );
//        log.info("布隆过滤器已清空");
//    }
//}
