package com.cixingji.backend.config.videosummary;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class VideoSummaryExecutorConfig {

    @Bean("videoSummaryMediaExecutor")
    public Executor videoSummaryMediaExecutor(VideoSummaryProperties properties) {
        return createExecutor(
                "video-summary-media-",
                properties.getMediaCorePoolSize(),
                properties.getMediaMaxPoolSize(),
                properties.getMediaQueueCapacity()
        );
    }

    @Bean("videoSummaryRemoteExecutor")
    public Executor videoSummaryRemoteExecutor(VideoSummaryProperties properties) {
        return createExecutor(
                "video-summary-remote-",
                properties.getRemoteCorePoolSize(),
                properties.getRemoteMaxPoolSize(),
                properties.getRemoteQueueCapacity()
        );
    }

    private Executor createExecutor(String prefix, int coreSize, int maxSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
