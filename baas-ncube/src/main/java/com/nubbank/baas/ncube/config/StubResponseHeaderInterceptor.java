package com.nubbank.baas.ncube.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Adds X-NubBank-Stubbed: true to every response when baas.nps.live=false.
 *
 * <p>Companion to {@link StubModeGuard}. The guard refuses to start when stub mode meets
 * production; this interceptor makes every stubbed response unmistakable to consumers,
 * log aggregators, and audit pipelines so mock data can never silently pass for live data.
 */
@Component
public class StubResponseHeaderInterceptor implements HandlerInterceptor {

    private final boolean live;

    public StubResponseHeaderInterceptor(@Value("${baas.nps.live:false}") boolean live) {
        this.live = live;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        if (!live) {
            resp.setHeader("X-NubBank-Stubbed", "true");
        }
        return true;
    }
}
