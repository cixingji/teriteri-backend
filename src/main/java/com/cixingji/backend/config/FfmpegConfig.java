//package com.cixingji.backend.config;
//
//import lombok.Data;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.PostConstruct;
//
//@Data
//@Component
//public class FfmpegConfig {
//    public static String PROFILES_FOLDER_PATH;
//    public static String INFO_PROFILES_PATH;// 视频信息配置文件路径
//    public static String KEY_PROFILES_PATH;// 视频密钥配置文件路径
//    public static String VIDEOS_FOLDER;// 视频总文件夹
//
//    // 临时变量
//    @Value("${video.profiles.folder}")
//    private String tmpProfilesFolderPath;
//    @Value("${video.profiles.infoPath}")
//    private String tmpInfoProfilesPath;
//    @Value("${video.profiles.keyPath}")
//    private String tmpKeyProfilesPath;
//    @Value("${video.folder.path}")
//    private String tmpVideoFolder;
//
//    // 将配置文件中的值赋值给静态变量
//    @PostConstruct
//    public void init(){
//        PROFILES_FOLDER_PATH = tmpProfilesFolderPath;
//        INFO_PROFILES_PATH = tmpInfoProfilesPath;
//        KEY_PROFILES_PATH = tmpKeyProfilesPath;
//        VIDEOS_FOLDER = tmpVideoFolder;
//    }
//}
