package com.cixingji.backend.utils;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HighlighterEncoder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cixingji.backend.mapper.CategoryMapper;
import com.cixingji.backend.mapper.UserMapper;
import com.cixingji.backend.mapper.VideoMapper;
import com.cixingji.backend.mapper.VideoStatsMapper;
import com.cixingji.backend.pojo.dto.search.VideoSearchCriteria;
import com.cixingji.backend.pojo.dto.search.VideoSearchHit;
import com.cixingji.backend.pojo.dto.search.VideoSearchPage;
import com.cixingji.backend.pojo.entity.Category;
import com.cixingji.backend.pojo.entity.ESSearchWord;
import com.cixingji.backend.pojo.entity.ESUser;
import com.cixingji.backend.pojo.entity.ESVideo;
import com.cixingji.backend.pojo.entity.User;
import com.cixingji.backend.pojo.entity.Video;
import com.cixingji.backend.pojo.entity.VideoStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ESUtil {

    public static final String VIDEO_SEARCH_INDEX = "video_search_v2";

    private enum IndexOperation { UPSERT, DELETE }

    private final ElasticsearchClient client;
    private final VideoMapper videoMapper;
    private final UserMapper userMapper;
    private final CategoryMapper categoryMapper;
    private final VideoStatsMapper videoStatsMapper;
    private final ConcurrentHashMap<Integer, IndexOperation> pendingVideoOperations = new ConcurrentHashMap<>();
    private final Object indexInitializationMonitor = new Object();

    private volatile boolean videoIndexReady;

    @Value("${search.index.bootstrap-enabled:true}")
    private boolean bootstrapEnabled;

    public ESUtil(ElasticsearchClient client,
                  VideoMapper videoMapper,
                  UserMapper userMapper,
                  CategoryMapper categoryMapper,
                  VideoStatsMapper videoStatsMapper) {
        this.client = client;
        this.videoMapper = videoMapper;
        this.userMapper = userMapper;
        this.categoryMapper = categoryMapper;
        this.videoStatsMapper = videoStatsMapper;
    }

    /**
     * Video writes are coalesced and flushed asynchronously. Indexing by vid makes every write idempotent.
     */
    public void addVideo(Video video) {
        if (video != null && video.getVid() != null) {
            pendingVideoOperations.put(video.getVid(), IndexOperation.UPSERT);
        }
    }

    public void updateVideo(Video video) {
        addVideo(video);
    }

    public void updateVideoById(Integer vid) {
        if (vid != null) {
            pendingVideoOperations.put(vid, IndexOperation.UPSERT);
        }
    }

    public void deleteVideo(Integer vid) {
        if (vid != null) {
            pendingVideoOperations.put(vid, IndexOperation.DELETE);
        }
    }

    public void updateVideosByUser(Integer uid) {
        if (uid == null) return;
        List<Video> videos = videoMapper.selectList(new QueryWrapper<Video>()
                .select("vid").eq("uid", uid).ne("status", 3));
        for (Video video : videos) {
            updateVideoById(video.getVid());
        }
    }

    @Scheduled(fixedDelayString = "${search.index.sync-delay-ms:3000}")
    public void flushPendingVideoOperations() {
        if (pendingVideoOperations.isEmpty()) return;
        Map<Integer, IndexOperation> batch = new HashMap<>(pendingVideoOperations);
        for (Map.Entry<Integer, IndexOperation> entry : batch.entrySet()) {
            if (!pendingVideoOperations.remove(entry.getKey(), entry.getValue())) continue;
            try {
                if (entry.getValue() == IndexOperation.DELETE) {
                    deleteVideoNow(entry.getKey());
                } else {
                    upsertVideoNow(entry.getKey());
                }
            } catch (Exception e) {
                videoIndexReady = false;
                pendingVideoOperations.putIfAbsent(entry.getKey(), entry.getValue());
                log.warn("Video search index sync failed for vid {}; it will be retried", entry.getKey(), e);
            }
        }
    }

    @Async("taskExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapVideoSearchIndex() {
        if (!bootstrapEnabled) return;
        try {
            ensureVideoSearchIndex();
            List<Video> videos = videoMapper.selectList(new QueryWrapper<Video>()
                    .select("vid").ne("status", 3));
            for (Video video : videos) {
                pendingVideoOperations.put(video.getVid(), IndexOperation.UPSERT);
            }
            log.info("Queued {} videos for idempotent search index bootstrap", videos.size());
        } catch (Exception e) {
            log.warn("Video search bootstrap skipped. Verify Elasticsearch and the IK analyzer plugin", e);
        }
    }

    public void rebuildVideoSearchIndex() {
        try {
            synchronized (indexInitializationMonitor) {
                videoIndexReady = false;
                if (client.indices().exists(e -> e.index(VIDEO_SEARCH_INDEX)).value()) {
                    client.indices().delete(d -> d.index(VIDEO_SEARCH_INDEX));
                }
                ensureVideoSearchIndex();
            }
            List<Video> videos = videoMapper.selectList(new QueryWrapper<Video>()
                    .select("vid").ne("status", 3));
            for (Video video : videos) {
                pendingVideoOperations.put(video.getVid(), IndexOperation.UPSERT);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not rebuild the video search index", e);
        }
    }

    public VideoSearchPage searchVideos(VideoSearchCriteria criteria) {
        try {
            ensureVideoSearchIndex();
            BoolQuery.Builder bool = new BoolQuery.Builder();
            if (criteria.isOnlyPass()) {
                bool.filter(f -> f.term(t -> t.field("status").value(1)));
            }
            if (StringUtils.hasText(criteria.getMcId())) {
                bool.filter(f -> f.term(t -> t.field("mcId").value(criteria.getMcId())));
            }
            if (StringUtils.hasText(criteria.getScId())) {
                bool.filter(f -> f.term(t -> t.field("scId").value(criteria.getScId())));
            }
            if (StringUtils.hasText(criteria.getKeyword())) {
                bool.must(m -> m.multiMatch(mm -> mm
                        .query(criteria.getKeyword().trim())
                        .fields("title^6", "tags^4", "uploaderName^3", "scName^2.5", "mcName^2", "descr")
                        .operator(Operator.Or)
                        .minimumShouldMatch("1")));
            } else {
                bool.must(m -> m.matchAll(a -> a));
            }

            SearchRequest.Builder request = new SearchRequest.Builder()
                    .index(VIDEO_SEARCH_INDEX)
                    .query(Query.of(q -> q.bool(bool.build())))
                    .from((criteria.safePage() - 1) * criteria.safeSize())
                    .size(criteria.safeSize())
                    .trackTotalHits(t -> t.enabled(true))
                    .highlight(h -> h
                            .encoder(HighlighterEncoder.Html)
                            .preTags("<mark>")
                            .postTags("</mark>")
                            .fields("title", f -> f.numberOfFragments(0))
                            .fields("descr", f -> f.fragmentSize(140).numberOfFragments(1))
                            .fields("tags", f -> f.numberOfFragments(0))
                            .fields("uploaderName", f -> f.numberOfFragments(0))
                            .fields("mcName", f -> f.numberOfFragments(0))
                            .fields("scName", f -> f.numberOfFragments(0)));
            applySort(request, criteria.getSort());

            SearchResponse<ESVideo> response = client.search(request.build(), ESVideo.class);
            List<VideoSearchHit> records = new ArrayList<>();
            for (Hit<ESVideo> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    records.add(new VideoSearchHit(hit.source().getVid(), hit.score(), hit.highlight()));
                }
            }
            long total = response.hits().total() == null ? records.size() : response.hits().total().value();
            return new VideoSearchPage(records, total, criteria.safePage(), criteria.safeSize());
        } catch (IOException e) {
            videoIndexReady = false;
            throw new IllegalStateException("Video search is temporarily unavailable", e);
        }
    }

    public Long getVideoCount(String keyword, boolean onlyPass) {
        return searchVideos(new VideoSearchCriteria(keyword, 1, 1, "relevance", null, null, onlyPass)).getTotal();
    }

    public List<Integer> searchVideosByKeyword(String keyword, Integer page, Integer size, boolean onlyPass) {
        VideoSearchPage result = searchVideos(new VideoSearchCriteria(
                keyword, page, size, "relevance", null, null, onlyPass));
        List<Integer> ids = new ArrayList<>();
        for (VideoSearchHit hit : result.getRecords()) ids.add(hit.getVid());
        return ids;
    }

    private void applySort(SearchRequest.Builder request, String sort) {
        String normalized = StringUtils.hasText(sort) ? sort.toLowerCase() : "relevance";
        switch (normalized) {
            case "latest":
                request.sort(s -> s.field(f -> f.field("uploadTime").order(SortOrder.Desc)));
                break;
            case "play":
                request.sort(s -> s.field(f -> f.field("play").order(SortOrder.Desc)))
                        .sort(s -> s.field(f -> f.field("uploadTime").order(SortOrder.Desc)));
                break;
            case "likes":
                request.sort(s -> s.field(f -> f.field("good").order(SortOrder.Desc)))
                        .sort(s -> s.field(f -> f.field("uploadTime").order(SortOrder.Desc)));
                break;
            default:
                request.sort(s -> s.score(sc -> sc.order(SortOrder.Desc)))
                        .sort(s -> s.field(f -> f.field("uploadTime").order(SortOrder.Desc)));
        }
    }

    private void ensureVideoSearchIndex() throws IOException {
        if (videoIndexReady) return;
        synchronized (indexInitializationMonitor) {
            if (videoIndexReady) return;
            if (!client.indices().exists(e -> e.index(VIDEO_SEARCH_INDEX)).value()) {
                client.indices().create(c -> c
                        .index(VIDEO_SEARCH_INDEX)
                        .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                        .mappings(m -> m
                                .properties("vid", p -> p.integer(i -> i))
                                .properties("uid", p -> p.integer(i -> i))
                                .properties("title", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                                .properties("descr", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                                .properties("tags", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                                .properties("uploaderName", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                                .properties("mcName", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                                .properties("scName", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                                .properties("mcId", p -> p.keyword(k -> k))
                                .properties("scId", p -> p.keyword(k -> k))
                                .properties("status", p -> p.integer(i -> i))
                                .properties("uploadTime", p -> p.long_(l -> l))
                                .properties("play", p -> p.integer(i -> i))
                                .properties("good", p -> p.integer(i -> i))));
            }
            videoIndexReady = true;
        }
    }

    private void upsertVideoNow(Integer vid) throws IOException {
        ensureVideoSearchIndex();
        Video video = videoMapper.selectById(vid);
        if (video == null || Integer.valueOf(3).equals(video.getStatus())) {
            deleteVideoNow(vid);
            return;
        }
        User user = userMapper.selectById(video.getUid());
        Category category = categoryMapper.selectOne(new QueryWrapper<Category>()
                .eq("mc_id", video.getMcId()).eq("sc_id", video.getScId()));
        VideoStats stats = videoStatsMapper.selectById(vid);
        ESVideo document = new ESVideo(
                video.getVid(), video.getUid(), safe(video.getTitle()), safe(video.getDescr()), safe(video.getTags()),
                safe(video.getMcId()), safe(video.getScId()),
                category == null ? "" : safe(category.getMcName()),
                category == null ? "" : safe(category.getScName()),
                user == null ? "" : safe(user.getNickname()),
                video.getStatus(), video.getUploadDate() == null ? 0L : video.getUploadDate().getTime(),
                stats == null || stats.getPlay() == null ? 0 : stats.getPlay(),
                stats == null || stats.getGood() == null ? 0 : stats.getGood());
        client.index(i -> i.index(VIDEO_SEARCH_INDEX).id(String.valueOf(vid)).document(document));
    }

    private void deleteVideoNow(Integer vid) throws IOException {
        ensureVideoSearchIndex();
        if (client.exists(e -> e.index(VIDEO_SEARCH_INDEX).id(String.valueOf(vid))).value()) {
            client.delete(d -> d.index(VIDEO_SEARCH_INDEX).id(String.valueOf(vid)));
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public void addUser(User user) throws IOException {
        ESUser esUser = new ESUser(user.getUid(), user.getNickname());
        client.index(i -> i.index("user").id(esUser.getUid().toString()).document(esUser));
    }

    public void deleteUser(Integer uid) throws IOException {
        client.delete(d -> d.index("user").id(uid.toString()));
    }

    public void updateUser(User user) throws IOException {
        ESUser esUser = new ESUser(user.getUid(), user.getNickname());
        client.update(u -> u.index("user").id(user.getUid().toString()).doc(esUser), ESUser.class);
        updateVideosByUser(user.getUid());
    }

    public Long getUserCount(String keyword) {
        try {
            Query query = Query.of(q -> q.simpleQueryString(s -> s.fields("nickname").query(keyword)
                    .defaultOperator(Operator.And)));
            CountResponse response = client.count(new CountRequest.Builder().index("user").query(query).build());
            return response.count();
        } catch (IOException e) {
            log.error("Could not count matching users", e);
            return 0L;
        }
    }

    public List<Integer> searchUsersByKeyword(String keyword, Integer page, Integer size) {
        try {
            Query query = Query.of(q -> q.simpleQueryString(s -> s.fields("nickname").query(keyword)
                    .defaultOperator(Operator.And)));
            SearchRequest request = new SearchRequest.Builder().index("user").query(query)
                    .from((page - 1) * size).size(size).build();
            SearchResponse<ESUser> response = client.search(request, ESUser.class);
            List<Integer> result = new ArrayList<>();
            for (Hit<ESUser> hit : response.hits().hits()) {
                if (hit.source() != null) result.add(hit.source().getUid());
            }
            return result;
        } catch (IOException e) {
            log.error("Could not search users", e);
            return Collections.emptyList();
        }
    }

    public void addSearchWord(String text) {
        try {
            ESSearchWord document = new ESSearchWord(text);
            client.index(i -> i.index("search_word").document(document));
        } catch (IOException e) {
            log.error("Could not add a search suggestion", e);
        }
    }

    public List<String> getMatchingWord(String text) {
        try {
            Query exact = Query.of(q -> q.simpleQueryString(s -> s.fields("content").query(text)
                    .defaultOperator(Operator.And)));
            Query prefix = Query.of(q -> q.prefix(p -> p.field("content").value(text)));
            Query query = Query.of(q -> q.bool(b -> b.should(exact).should(prefix)));
            SearchResponse<ESSearchWord> response = client.search(new SearchRequest.Builder()
                    .index("search_word").query(query).from(0).size(10).build(), ESSearchWord.class);
            List<String> result = new ArrayList<>();
            for (Hit<ESSearchWord> hit : response.hits().hits()) {
                if (hit.source() != null) result.add(hit.source().getContent());
            }
            return result;
        } catch (IOException e) {
            log.error("Could not query search suggestions", e);
            return Collections.emptyList();
        }
    }
}
