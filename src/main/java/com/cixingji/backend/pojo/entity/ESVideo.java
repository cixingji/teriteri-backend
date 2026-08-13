package com.cixingji.backend.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ESVideo {
    private Integer vid;
    private Integer uid;
    private String title;
    private String descr;
    private String tags;
    private String mcId;
    private String scId;
    private String mcName;
    private String scName;
    private String uploaderName;
    private Integer status;
    private Long uploadTime;
    private Integer play;
    private Integer good;

    /** Compatibility constructor used by legacy index maintenance tests. */
    public ESVideo(Integer vid, Integer uid, String title, String mcId,
                   String scId, String tags, Integer status) {
        this.vid = vid;
        this.uid = uid;
        this.title = title;
        this.mcId = mcId;
        this.scId = scId;
        this.tags = tags;
        this.status = status;
    }
}
