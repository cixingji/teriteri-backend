package com.cixingji.backend.controller;

import com.cixingji.backend.pojo.dto.search.VideoSearchCriteria;
import com.cixingji.backend.pojo.dto.search.VideoSearchHit;
import com.cixingji.backend.pojo.dto.search.VideoSearchPage;
import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.service.search.SearchService;
import com.cixingji.backend.service.user.UserService;
import com.cixingji.backend.service.video.VideoService;
import com.cixingji.backend.utils.ESUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class SearchController {

    private final SearchService searchService;
    private final ESUtil esUtil;
    private final VideoService videoService;
    private final UserService userService;

    public SearchController(SearchService searchService,
                            ESUtil esUtil,
                            VideoService videoService,
                            UserService userService) {
        this.searchService = searchService;
        this.esUtil = esUtil;
        this.videoService = videoService;
        this.userService = userService;
    }

    @GetMapping("/search/hot/get")
    public CustomResponse getHotSearch() {
        return new CustomResponse(200, "OK", searchService.getHotSearch());
    }

    @PostMapping("/search/word/add")
    public CustomResponse addSearchWord(@RequestParam("keyword") String keyword) {
        return new CustomResponse(200, "OK", searchService.addSearchWord(keyword));
    }

    @GetMapping("/search/word/get")
    public CustomResponse getSearchWord(@RequestParam("keyword") String keyword) {
        keyword = decode(keyword);
        return new CustomResponse(200, "OK", keyword.trim().isEmpty()
                ? Collections.emptyList()
                : searchService.getMatchingWord(keyword));
    }

    @GetMapping("/search/count")
    public CustomResponse getCount(@RequestParam("keyword") String keyword) {
        return new CustomResponse(200, "OK", searchService.getCount(decode(keyword)));
    }

    /**
     * Structured search API with weighted multi-field relevance, server-side highlights,
     * category filters, and deterministic sorting.
     */
    @GetMapping("/search/videos")
    public ResponseEntity<CustomResponse> searchVideos(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "30") Integer size,
            @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(required = false) String mcId,
            @RequestParam(required = false) String scId) {
        try {
            VideoSearchPage searchPage = esUtil.searchVideos(new VideoSearchCriteria(
                    keyword.trim(), page, size, sort, emptyToNull(mcId), emptyToNull(scId), true));
            List<Integer> ids = new ArrayList<>();
            Map<Integer, VideoSearchHit> hits = new HashMap<>();
            for (VideoSearchHit hit : searchPage.getRecords()) {
                ids.add(hit.getVid());
                hits.put(hit.getVid(), hit);
            }
            List<Map<String, Object>> details = videoService.getVideosWithDataByIdList(ids);
            for (Map<String, Object> detail : details) {
                Object videoObject = detail.get("video");
                if (videoObject instanceof com.cixingji.backend.pojo.entity.Video) {
                    Integer vid = ((com.cixingji.backend.pojo.entity.Video) videoObject).getVid();
                    VideoSearchHit hit = hits.get(vid);
                    detail.put("highlight", hit == null ? Collections.emptyMap() : hit.getHighlights());
                    detail.put("searchScore", hit == null ? null : hit.getScore());
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("records", details);
            data.put("total", searchPage.getTotal());
            data.put("page", searchPage.getPage());
            data.put("size", searchPage.getSize());
            data.put("sort", normalizeSort(sort));
            return ResponseEntity.ok(new CustomResponse(200, "OK", data));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503)
                    .body(new CustomResponse(503, "视频搜索服务暂时不可用，请检查 Elasticsearch 与 IK 分词插件", null));
        }
    }

    /** Keeps the original response shape for existing clients. */
    @GetMapping("/search/video/only-pass")
    public CustomResponse getMatchingVideo(@RequestParam("keyword") String keyword,
                                           @RequestParam("page") Integer page) {
        List<Integer> ids = esUtil.searchVideosByKeyword(decode(keyword), page, 30, true);
        return new CustomResponse(200, "OK", videoService.getVideosWithDataByIdList(ids));
    }

    @GetMapping("/search/user")
    public CustomResponse getMatchingUser(@RequestParam("keyword") String keyword,
                                          @RequestParam("page") Integer page) {
        List<Integer> ids = esUtil.searchUsersByKeyword(decode(keyword), page, 30);
        return new CustomResponse(200, "OK", userService.getUserByIdList(ids));
    }

    @PostMapping("/admin/search/index/rebuild")
    public ResponseEntity<CustomResponse> rebuildVideoIndex() {
        try {
            esUtil.rebuildVideoSearchIndex();
            return ResponseEntity.accepted()
                    .body(new CustomResponse(202, "视频搜索索引已重建，文档正在异步写入", null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503)
                    .body(new CustomResponse(503, "索引重建失败，请检查 Elasticsearch 与 IK 分词插件", null));
        }
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable", e);
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String normalizeSort(String value) {
        if (value == null) return "relevance";
        switch (value.toLowerCase()) {
            case "latest":
            case "play":
            case "likes":
                return value.toLowerCase();
            default:
                return "relevance";
        }
    }
}
