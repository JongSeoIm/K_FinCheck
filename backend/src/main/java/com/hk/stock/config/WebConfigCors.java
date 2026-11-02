package com.hk.stock.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfigCors implements WebMvcConfigurer{

	@Value("${app.allowed-origins}")
	private String[] allowedOrigins;

	//CORS 정책 제한 허용 (프론트 Next.js 주소만 허용)
	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
			    .allowedOrigins(allowedOrigins)
			    .allowedMethods("GET")
			    .allowedHeaders("*")
			    .allowCredentials(false)
			    .maxAge(3600);
	}
}
