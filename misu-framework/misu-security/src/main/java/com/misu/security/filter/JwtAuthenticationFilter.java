package com.misu.security.filter;

import com.misu.security.dto.LoginUser;
import com.misu.security.properties.PermitAllUrlProperties;
import com.misu.security.service.TokenService;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * JWT身份验证过滤器
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * token工具
     */
    @Resource
    private TokenService tokenService;

    /**
     * 令牌前缀
     */
    public static final String TOKEN_PREFIX = "Bearer ";

    /**
     * 检查令牌是否有效，如果用户和有效，更新安全上下文中的身份验证令牌
     * 最后一步执行过滤器chain，别忘记放行
     * @param request
     * @param response
     * @param filterChain
     * @throws ServletException
     * @throws IOException
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // 从请求头中获取认证信息
        final String authHeader = request.getHeader("Authorization");
        final String token;

        if (authHeader != null && authHeader.startsWith(TOKEN_PREFIX)) {
            // 提取 token
            token = authHeader.substring(TOKEN_PREFIX.length());

            // 如果当前上下文中没有认证信息，则进行 token 验证
            if (SecurityContextHolder.getContext().getAuthentication() == null && tokenService.verifyToken(token)) {
                // 如果令牌有效，获取用户信息并创建认证对象
                LoginUser loginUser = tokenService.getLoginUser(token);

                //设置认证后的信息
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        loginUser,
                        null,
                        loginUser.getAuthorities().stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList()));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // 更新安全上下文
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        // 继续过滤链
        filterChain.doFilter(request, response);
    }
}
