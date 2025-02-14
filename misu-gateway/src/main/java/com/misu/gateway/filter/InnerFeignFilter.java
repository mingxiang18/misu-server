package com.misu.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 内部远程调用过滤器
 * 只提供内部feign调用的接口无法被gateway访问
 *
 * @author misu
 */
@Component
public class InnerFeignFilter implements GlobalFilter, Ordered {

    private static final String RESTRICTED_PATH_PATTERN = "/[^/]+/inner.*"; // 匹配任意服务名/inner 开头的路径

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 使用正则表达式匹配/任意服务名/inner路径
        if (path.matches(RESTRICTED_PATH_PATTERN)) {
            // 如果是 /xxx/inner 开头的路径，返回一个 403 错误
            return Mono.defer(() -> {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            });
        }

        // 否则正常转发请求
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
