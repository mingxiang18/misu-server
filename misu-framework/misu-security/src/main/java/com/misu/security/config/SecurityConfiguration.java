package com.misu.security.config;

import com.misu.security.filter.JwtAuthenticationFilter;
import com.misu.security.properties.PermitAllUrlProperties;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.ExpressionUrlAuthorizationConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.web.filter.CorsFilter;

/**
 * 安全配置
 */
@Configuration
// 开启网络安全注解
@EnableWebSecurity
public class SecurityConfiguration {

    // 将自定义JwtAuthenticationFilter注入
    @Resource
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * 允许匿名访问的地址
     */
    @Resource
    private PermitAllUrlProperties permitAllUrl;

//    @Resource
//    private CorsFilter corsFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(AbstractHttpConfigurer::disable)
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> {
                // 设置auth的url为放行白名单
                permitAllUrl.getUrls().forEach(url -> auth.requestMatchers(url).permitAll());
                auth.requestMatchers("/videoRoom/ws/**").permitAll();
                // Q5：放开 Spring Boot Actuator 健康检查 / Prometheus 指标抓取（生产由网络层做内网限制）
                auth.requestMatchers("/actuator/**").permitAll();
                // WebDAV servlet 自行做 HTTP Basic Auth，放行让请求抵达 servlet（仅 file-server 有 /dav/* servlet）
                auth.requestMatchers("/dav/**").permitAll();
                auth.anyRequest().authenticated();
            })
            // 未携带 / 已过期 / 无效 token 的请求统一返回 401。
            // Spring Security 6 在无 formLogin/httpBasic 时默认入口点是
            // Http403ForbiddenEntryPoint（返回 403 空 body），会让前端 axios
            // 的 token 刷新逻辑（仅在 401 触发）永不生效，refreshToken 形同虚设。
            .exceptionHandling(exception -> exception
                    .authenticationEntryPoint((request, response, authException) -> {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"code\":401,\"msg\":\"未登录或登录状态已过期\"}");
                    }))
            // 使用无状态session，即不使用session缓存数据
            .sessionManagement(sessionManagement -> sessionManagement
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                    .disable()
                    // 添加JWT校验
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class))
                    // 添加允许跨域，若微服务使用gateway网关，则只在网关配置跨域即可，其他服务不需要配置
//                    .addFilterBefore(corsFilter, JwtAuthenticationFilter.class)
//                    .addFilterBefore(corsFilter, LogoutFilter.class))
            // 登出操作
//            .logout(logoutConfigurer -> logoutConfigurer.logoutUrl("/auth/logout"))
//            .logoutSuccessHandler((request, response, authentication) -> SecurityContextHolder.clearContext())
        ;


        return http.build();
    }

    /**
     * 提供密码编码机制
     * @return
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
