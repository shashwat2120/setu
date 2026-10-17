package com.setu.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers {@link PageSizeLimitFilter} explicitly, for every service that serves lists. */
@Configuration(proxyBeanMethods = false)
public class PageSizeLimitFilterConfig {

    @Bean
    public FilterRegistrationBean<PageSizeLimitFilter> pageSizeLimitFilterRegistration(@Value("${app.pagination.max-size:100}") int maxSize) {
        FilterRegistrationBean<PageSizeLimitFilter> registration = new FilterRegistrationBean<>(new PageSizeLimitFilter(maxSize));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);   // after the correlation id filter
        registration.addUrlPatterns("/*");
        return registration;
    }
}
