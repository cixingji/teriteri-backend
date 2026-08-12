//package com.cixingji.backend.config;
//
//import lombok.Data;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Configuration;
//
//@Data
//@Configuration
//public class HlsConfig {
//
//    @Value("${ffmpeg.path:ffmpeg}")
//    private String ffmpegPath;
//
//    @Value("${hls.segment.duration:6}")
//    private int segmentDurationSeconds;
//
//    @Value("${hls.segment.format_name:segment}")
//    private String segmentFormatName;
//
//    @Value("${hls.aac_profile:aac_he}")
//    private String aacProfile;
//
//    @Value("${hls.allow_low_latency:false}")
//    private boolean allowLowLatency;
//
//    @Value("${directory.video}")
//    private String videoWorkDir;
//
//    // 码率配置（逗号分隔，单位kbps），如：128,512,1200,2500
//    @Value("${hls.vbitrates:400,800,1500}")
//    private String videoBitratesKbps;
//
//    // 分辨率配置（与码率一一对应），如：426x240,640x360,1280x720
//    @Value("${hls.vresolutions:426x240,640x360,1280x720}")
//    private String videoResolutions;
//
//    // 音频码率（kbps）
//    @Value("${hls.abitrate:96}")
//    private int audioBitrateKbps;
//}
//
//
//
