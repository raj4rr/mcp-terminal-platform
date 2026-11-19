package dev.raj.gateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry r) {
        r.addMapping("/api/**")
         .allowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]", "http://localhost", "http://127.0.0.1")
         .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD")
         .allowedHeaders("*")
         .allowCredentials(true)
         .maxAge(3600);
    }
}
