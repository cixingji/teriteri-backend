package com.cixingji.backend.pojo.dto.search;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VideoSearchCriteria {
    private String keyword;
    private int page;
    private int size;
    private String sort;
    private String mcId;
    private String scId;
    private boolean onlyPass;

    public int safePage() {
        return Math.max(1, page);
    }

    public int safeSize() {
        return Math.max(1, Math.min(size, 100));
    }
}
