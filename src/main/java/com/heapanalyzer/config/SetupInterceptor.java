package com.heapanalyzer.config;

import com.heapanalyzer.service.ConfigService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Redirects all page requests to /setup when the AI API key is not configured.
 * API endpoints, static resources, and the setup page itself are excluded.
 */
@Component
@org.springframework.context.annotation.Configuration
public class SetupInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final ConfigService configService;

    public SetupInterceptor(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String path = request.getRequestURI();

        // Allow these paths through even when unconfigured
        if (path.equals("/setup")
                || path.startsWith("/api/settings")
                || path.startsWith("/css")
                || path.startsWith("/js")
                || path.startsWith("/img")
                || path.startsWith("/favicon")
                || path.startsWith("/error")) {
            return true;
        }

        // If not configured, redirect to setup
        if (!configService.isConfigured()) {
            response.sendRedirect("/setup");
            return false;
        }

        return true;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns("/**")
                .excludePathPatterns("/setup", "/api/settings/**", "/error");
    }
}
