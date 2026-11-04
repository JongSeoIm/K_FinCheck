package com.hk.stock.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfigCors implements WebMvcConfigurer{

	@Value("${app.allowed-origins}")
	private String[] allowedOrigins;

	@Autowired
	private LoginInterceptor loginInterceptor;

	//CORS 정책 제한 허용 (프론트 Next.js 주소만 허용)
	//로그인 세션 쿠키를 주고받아야 하므로 allowCredentials(true)
	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
			    .allowedOrigins(allowedOrigins)
			    .allowedMethods("GET", "POST", "DELETE")
			    .allowedHeaders("*")
			    .allowCredentials(true)
			    .maxAge(3600);
	}

	//로그인 필요 API: 관심 종목
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(loginInterceptor)
				.addPathPatterns("/api/watchlist/**");
	}
}
