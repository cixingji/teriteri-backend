package com.cixingji.backend.pojo.dto.search;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class VideoSearchPage {
    private List<VideoSearchHit> records;
    private long total;
    private int page;
    private int size;
}
