package com.blog.article.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行配置
 *
 * 2026-06-18 性能：PageView 记录从「请求线程同步写库」改为「异步线程池写库」，
 * 公开接口（首页 /articles、/articles/categories、/articles/tags、/public/settings/*）
 * 不再为统计 PV 同步等待 SELECT+INSERT，响应直接返回。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * PageView 专用线程池。
     * - core/max 故意小：统计写库不抢占业务资源（SQLite 仍是单写者，过多写线程也无益）。
     * - 有界队列：突发流量下排队而非无限堆积内存。
     * - 拒绝策略 DiscardPolicy：队列满时直接丢弃这次 PV（统计是 best-effort，绝不能反压业务）。
     */
    @Bean("pageViewExecutor")
    public Executor pageViewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("pageview-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
