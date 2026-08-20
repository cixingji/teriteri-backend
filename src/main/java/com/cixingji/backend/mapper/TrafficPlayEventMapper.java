package com.cixingji.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cixingji.backend.pojo.entity.TrafficPlayEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TrafficPlayEventMapper extends BaseMapper<TrafficPlayEvent> {
    @Update("UPDATE video_stats SET play = play + #{count} WHERE vid = #{vid}")
    int incrementPlay(@Param("vid") Integer vid, @Param("count") int count);
}
