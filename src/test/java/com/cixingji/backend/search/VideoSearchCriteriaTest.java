package com.cixingji.backend.search;

import com.cixingji.backend.pojo.dto.search.VideoSearchCriteria;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoSearchCriteriaTest {

    @Test
    void clampsPageAndPageSize() {
        VideoSearchCriteria criteria = new VideoSearchCriteria(
                "java", -3, 1000, "relevance", null, null, true);

        assertEquals(1, criteria.safePage());
        assertEquals(100, criteria.safeSize());
    }

    @Test
    void keepsValidPagination() {
        VideoSearchCriteria criteria = new VideoSearchCriteria(
                "java", 4, 30, "latest", "tech", "computer_tech", true);

        assertEquals(4, criteria.safePage());
        assertEquals(30, criteria.safeSize());
    }
}
