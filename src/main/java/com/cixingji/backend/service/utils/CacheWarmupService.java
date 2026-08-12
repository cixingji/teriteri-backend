//package com.cixingji.backend.service.utils;
//
//import com.cixingji.backend.mapper.CategoryMapper;
//import com.cixingji.backend.mapper.VideoMapper;
//import com.cixingji.backend.pojo.entity.Category;
//import com.cixingji.backend.pojo.entity.Video;
//import com.cixingji.backend.utils.RedisUtil;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.scheduling.annotation.Async;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Service;
//
//import java.util.List;
//
///**
// * 缓存预热服务
// * 定时任务实现缓存预热，避免缓存雪崩和缓存击穿
// */
//@Service
//@Slf4j
//public class CacheWarmupService {
//
//    @Autowired
//    private RedisUtil redisUtil;
//
//    @Autowired
//    private BloomFilterUtil bloomFilterUtil;
//
//    @Autowired
//    private CategoryMapper categoryMapper;
//
//    @Autowired
//    private VideoMapper videoMapper;
//
//    // 缓存预热相关常量
//    private static final String CATEGORY_CACHE_KEY = "category:all";
//    private static final String HOT_VIDEO_CACHE_KEY = "video:hot";
//    private static final String RECOMMEND_VIDEO_CACHE_KEY = "video:recommend";
//
//    /**
//     * 每天凌晨2点执行缓存预热
//     * 避免在高峰期进行缓存预热，减少对系统的影响
//     */
//    @Scheduled(cron = "0 0 2 * * ?")
//    public void dailyCacheWarmup() {
//        log.info("开始执行每日缓存预热任务");
//
//        try {
//            // 预热分类缓存
//            warmupCategoryCache();
//
//            // 预热热门视频缓存
//            warmupHotVideoCache();
//
//            // 预热推荐视频缓存
//            warmupRecommendVideoCache();
//
//            // 更新布隆过滤器
//            updateBloomFilter();
//
//            log.info("每日缓存预热任务执行完成");
//        } catch (Exception e) {
//            log.error("缓存预热任务执行失败", e);
//        }
//    }
//
//    /**
//     * 每小时执行一次缓存检查
//     * 确保关键缓存不会过期
//     */
//    @Scheduled(cron = "0 0 * * * ?")
//    public void hourlyCacheCheck() {
//        log.info("开始执行每小时缓存检查任务");
//
//        try {
//            // 检查分类缓存
//            if (!redisUtil.isExist(CATEGORY_CACHE_KEY)) {
//                warmupCategoryCache();
//            }
//
//            // 检查热门视频缓存
//            if (!redisUtil.isExist(HOT_VIDEO_CACHE_KEY)) {
//                warmupHotVideoCache();
//            }
//
//            log.info("每小时缓存检查任务执行完成");
//        } catch (Exception e) {
//            log.error("缓存检查任务执行失败", e);
//        }
//    }
//
//    /**
//     * 预热分类缓存
//     */
//    @Async
//    public void warmupCategoryCache() {
//        try {
//            log.info("开始预热分类缓存");
//
//            // 从数据库获取所有分类
//            List<Category> categories = categoryMapper.selectList(null);
//
//            // 存储到Redis缓存，设置24小时过期
//            redisUtil.setExObjectValue(CATEGORY_CACHE_KEY, categories, 24 * 60 * 60);
//
//            log.info("分类缓存预热完成，共缓存{}个分类", categories.size());
//        } catch (Exception e) {
//            log.error("分类缓存预热失败", e);
//        }
//    }
//
//    /**
//     * 预热热门视频缓存
//     */
//    @Async
//    public void warmupHotVideoCache() {
//        try {
//            log.info("开始预热热门视频缓存");
//
//            // 这里可以根据实际业务逻辑获取热门视频
//            // 例如：按播放量、点赞数等排序获取前100个视频
//            List<Video> hotVideos = videoMapper.selectHotVideos(100);
//
//            // 存储到Redis缓存，设置6小时过期
//            redisUtil.setExObjectValue(HOT_VIDEO_CACHE_KEY, hotVideos, 6 * 60 * 60);
//
//            log.info("热门视频缓存预热完成，共缓存{}个视频", hotVideos.size());
//        } catch (Exception e) {
//            log.error("热门视频缓存预热失败", e);
//        }
//    }
//
//    /**
//     * 预热推荐视频缓存
//     */
//    @Async
//    public void warmupRecommendVideoCache() {
//        try {
//            log.info("开始预热推荐视频缓存");
//
//            // 这里可以根据实际业务逻辑获取推荐视频
//            // 例如：基于用户行为、内容相似度等算法
//            List<Video> recommendVideos = videoMapper.selectRecommendVideos(50);
//
//            // 存储到Redis缓存，设置4小时过期
//            redisUtil.setExObjectValue(RECOMMEND_VIDEO_CACHE_KEY, recommendVideos, 4 * 60 * 60);
//
//            log.info("推荐视频缓存预热完成，共缓存{}个视频", recommendVideos.size());
//        } catch (Exception e) {
//            log.error("推荐视频缓存预热失败", e);
//        }
//    }
//
//    /**
//     * 更新布隆过滤器
//     */
//    @Async
//    public void updateBloomFilter() {
//        try {
//            log.info("开始更新布隆过滤器");
//
//            // 清空现有布隆过滤器
//            bloomFilterUtil.clear();
//
//            // 获取所有视频ID并添加到布隆过滤器
//            List<Video> allVideos = videoMapper.selectList(null);
//            for (Video video : allVideos) {
//                bloomFilterUtil.add("video:" + video.getVid());
//            }
//
//            // 获取所有分类ID并添加到布隆过滤器
//            List<Category> allCategories = categoryMapper.selectList(null);
//            for (Category category : allCategories) {
//                bloomFilterUtil.add("category:" + category.getCid());
//            }
//
//            log.info("布隆过滤器更新完成，共添加{}个视频和{}个分类",
//                    allVideos.size(), allCategories.size());
//        } catch (Exception e) {
//            log.error("布隆过滤器更新失败", e);
//        }
//    }
//
//    /**
//     * 手动触发缓存预热
//     */
//    public void manualWarmup() {
//        log.info("手动触发缓存预热");
//        dailyCacheWarmup();
//    }
//}
