package io.modivra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcAsyncConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(180_000L);
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("mvc-vt-");
        executor.setVirtualThreads(true);
        configurer.setTaskExecutor(executor);
    }
}
