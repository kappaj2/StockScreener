package za.co.sfh.stocklistener.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("https://www.tradingview.com")
                        .allowedMethods("POST", "OPTIONS")
                        .maxAge(86400);
            }
        };
    }

    // Spring's CorsConfiguration does not model Access-Control-Allow-Private-Network,
    // so we set it in a filter when the browser's PNA preflight sends the corresponding request header.
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> privateNetworkAccessFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                if ("true".equalsIgnoreCase(request.getHeader("Access-Control-Request-Private-Network"))) {
                    response.setHeader("Access-Control-Allow-Private-Network", "true");
                }
                chain.doFilter(request, response);
            }
        });
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }
}