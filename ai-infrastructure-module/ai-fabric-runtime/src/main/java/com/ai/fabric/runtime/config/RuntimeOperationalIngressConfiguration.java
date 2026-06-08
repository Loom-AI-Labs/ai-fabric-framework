package com.ai.fabric.runtime.config;

import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class RuntimeOperationalIngressConfiguration implements WebMvcConfigurer {

    private final RuntimeRequestAuthResolver runtimeRequestAuthResolver;

    public RuntimeOperationalIngressConfiguration(RuntimeRequestAuthResolver runtimeRequestAuthResolver) {
        this.runtimeRequestAuthResolver = runtimeRequestAuthResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TrustedBackendDataSyncInterceptor(runtimeRequestAuthResolver))
            .addPathPatterns("/api/ai/data-sync/**");
    }

    private static final class TrustedBackendDataSyncInterceptor implements HandlerInterceptor {

        private final RuntimeRequestAuthResolver runtimeRequestAuthResolver;

        private TrustedBackendDataSyncInterceptor(RuntimeRequestAuthResolver runtimeRequestAuthResolver) {
            this.runtimeRequestAuthResolver = runtimeRequestAuthResolver;
        }

        @Override
        public boolean preHandle(@NonNull HttpServletRequest request,
                                 @NonNull HttpServletResponse response,
                                 @NonNull Object handler) {
            runtimeRequestAuthResolver.requireTrustedBackendIngress(request, "data-sync");
            return true;
        }
    }
}
