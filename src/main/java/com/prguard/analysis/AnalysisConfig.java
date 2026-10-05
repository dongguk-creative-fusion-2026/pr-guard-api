package com.prguard.analysis;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AnalysisConfig {

    /** base/head 인덱스, 검사기 그룹, LLM 리뷰를 동시에 돌리는 스레드 풀. */
    @Bean
    Executor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(6);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("analysis-");
        executor.initialize();
        return executor;
    }
}
