package com.cixingji.backend.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("search_index_job")
public class SearchIndexJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobType;
    private String status;
    private Integer total;
    private Integer processed;
    private Integer missingCount;
    private Integer staleCount;
    private Integer extraCount;
    private Integer repairedCount;
    private String errorMessage;
    private Date createdAt;
    private Date startedAt;
    private Date finishedAt;
}
