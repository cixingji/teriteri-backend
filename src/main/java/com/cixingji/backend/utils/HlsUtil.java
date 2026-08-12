//package com.cixingji.backend.utils;
//
//import com.cixingji.backend.config.HlsConfig;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.io.FileUtils;
//import org.springframework.stereotype.Component;
//
//import java.io.File;
//import java.io.IOException;
//import java.nio.charset.StandardCharsets;
//import java.text.SimpleDateFormat;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Date;
//import java.util.List;
//
//@Slf4j
//@Component
//@RequiredArgsConstructor
//public class HlsUtil {
//
//    private final HlsConfig hlsConfig;
//
//    public static class VariantInfo {
//        public final String name;      // 如 240p、360p、720p
//        public final int vBitrateKbps; // 视频码率
//        public final String vResolution; // 分辨率 1280x720
//        public VariantInfo(String name, int vBitrateKbps, String vResolution) {
//            this.name = name;
//            this.vBitrateKbps = vBitrateKbps;
//            this.vResolution = vResolution;
//        }
//    }
//
//    public static class HlsResult {
//        public final File outputDir;
//        public final File masterPlaylist;
//        public final List<File> variantPlaylists;
//        public HlsResult(File outputDir, File masterPlaylist, List<File> variantPlaylists) {
//            this.outputDir = outputDir;
//            this.masterPlaylist = masterPlaylist;
//            this.variantPlaylists = variantPlaylists;
//        }
//    }
//
//    public HlsResult transcodeToHlsMultiBitrate(File inputMp4, String baseName) throws IOException, InterruptedException {
//        String[] bitrateStrs = hlsConfig.getVideoBitratesKbps().split(",");
//        String[] resolutions = hlsConfig.getVideoResolutions().split(",");
//        if (bitrateStrs.length != resolutions.length) {
//            throw new IllegalArgumentException("hls.vbitrates 与 hls.vresolutions 数量不一致");
//        }
//
//        List<VariantInfo> variants = new ArrayList<>();
//        for (int i = 0; i < bitrateStrs.length; i++) {
//            int kbps = Integer.parseInt(bitrateStrs[i].trim());
//            String res = resolutions[i].trim();
//            String variantName = res.contains("x") ? res.split("x")[1] + "p" : (kbps + "k");
//            variants.add(new VariantInfo(variantName, kbps, res));
//        }
//
//        String date = new SimpleDateFormat("yyyyMMdd").format(new Date());
//        File outDir = new File(hlsConfig.getVideoWorkDir(), date + File.separator + baseName);
//        if (!outDir.exists() && !outDir.mkdirs()) {
//            throw new IOException("创建输出目录失败: " + outDir.getAbsolutePath());
//        }
//
//        List<File> variantM3u8List = new ArrayList<>();
//        for (VariantInfo v : variants) {
//            File subDir = new File(outDir, v.name);
//            if (!subDir.exists() && !subDir.mkdirs()) {
//                throw new IOException("创建清晰度目录失败: " + subDir.getAbsolutePath());
//            }
//            File playlist = new File(subDir, "index.m3u8");
//            File segmentPattern = new File(subDir, v.name + "_%03d.ts");
//
//            List<String> cmd = new ArrayList<>();
//            cmd.add(hlsConfig.getFfmpegPath());
//            cmd.add("-y");
//            cmd.add("-i");
//            cmd.add(inputMp4.getAbsolutePath());
//            cmd.add("-vf");
//            cmd.add("scale=" + v.vResolution);
//            cmd.add("-c:v");
//            cmd.add("h264");
//            cmd.add("-b:v");
//            cmd.add(v.vBitrateKbps + "k");
//            cmd.add("-c:a");
//            cmd.add("aac");
//            cmd.add("-b:a");
//            cmd.add(hlsConfig.getAudioBitrateKbps() + "k");
//            cmd.add("-ac");
//            cmd.add("2");
//            cmd.add("-f");
//            cmd.add("hls");
//            cmd.add("-hls_time");
//            cmd.add(String.valueOf(hlsConfig.getSegmentDurationSeconds()));
//            cmd.add("-hls_list_size");
//            cmd.add("0");
//            cmd.add("-hls_segment_filename");
//            cmd.add(segmentPattern.getAbsolutePath());
//            cmd.add(playlist.getAbsolutePath());
//
//            ProcessBuilder pb = new ProcessBuilder(cmd);
//            pb.redirectErrorStream(true);
//            Process p = pb.start();
//            int exit = p.waitFor();
//            if (exit != 0) {
//                throw new IOException("ffmpeg 转码失败: " + Arrays.toString(cmd.toArray()));
//            }
//            variantM3u8List.add(playlist);
//        }
//
//        File master = new File(outDir, "master.m3u8");
//        StringBuilder sb = new StringBuilder();
//        sb.append("#EXTM3U\n");
//        sb.append("#EXT-X-VERSION:3\n");
//        for (int i = 0; i < variants.size(); i++) {
//            VariantInfo v = variants.get(i);
//            // 近似带宽（比特/秒），乘以1000
//            int bandwidth = v.vBitrateKbps * 1000 + hlsConfig.getAudioBitrateKbps() * 1000;
//            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth)
//              .append(",RESOLUTION=").append(v.vResolution)
//              .append("\n");
//            sb.append(v.name).append("/index.m3u8\n");
//        }
//        FileUtils.write(master, sb.toString(), StandardCharsets.UTF_8);
//
//        return new HlsResult(outDir, master, variantM3u8List);
//    }
//}
//
//
//
