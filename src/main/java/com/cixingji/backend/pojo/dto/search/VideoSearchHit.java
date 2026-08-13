package com.cixingji.backend.pojo.dto.search;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
public class VideoSearchHit {
    private Integer vid;
    private Double score;
    private Map<String, List<String>> highlights;
}
