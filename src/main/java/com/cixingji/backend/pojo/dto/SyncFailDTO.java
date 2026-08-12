package com.cixingji.backend.pojo.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncFailDTO {

        private String tableName;        // 哪个表失败了
        private String operationType;    // INSERT, UPDATE, DELETE
        private String key;              // Redis key (用于删除操作)
        private Object data;             // 失败时要同步的完整数据对象（例如 User 对象）
//        private Long timestamp = System.currentTimeMillis();
    }

