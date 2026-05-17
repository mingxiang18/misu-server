package com.misu.fileServer.webdav;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import java.util.List;

/**
 * WebDAV 接入配置：把 {@link WebDavServlet} 注册到 {@code /dav/*}，
 * 并新增一个独立的 Tomcat 连接器，让 WebDAV 在自己的端口上对外提供。
 */
@Configuration
public class WebDavServerConfiguration {

    @Bean
    public ServletRegistrationBean<WebDavServlet> webDavServletRegistration(
            WebDavAuthenticator authenticator, WebDavService webDavService, WebDavLockManager lockManager) {
        WebDavServlet servlet = new WebDavServlet(authenticator, webDavService, lockManager);
        ServletRegistrationBean<WebDavServlet> registration = new ServletRegistrationBean<>(servlet, "/dav/*");
        registration.setName("webDavServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * 端口隔离过滤器，先于 Spring Security 过滤链执行。
     */
    @Bean
    public FilterRegistrationBean<WebDavPortFilter> webDavPortFilter(
            @org.springframework.beans.factory.annotation.Value("${webdav.server.port:30263}") int webdavPort) {
        FilterRegistrationBean<WebDavPortFilter> registration =
                new FilterRegistrationBean<>(new WebDavPortFilter(webdavPort));
        registration.addUrlPatterns("/*");
        registration.setName("webDavPortFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * 放行 WebDAV 扩展 HTTP 方法。Spring Security 的 {@code StrictHttpFirewall} 默认仅允许
     * 标准方法，PROPFIND / MKCOL / MOVE 等会在抵达 servlet 前被拒为 400。
     */
    @Bean
    public WebSecurityCustomizer webDavFirewallCustomizer() {
        return web -> {
            StrictHttpFirewall firewall = new StrictHttpFirewall();
            firewall.setAllowedHttpMethods(List.of(
                    "GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH",
                    "PROPFIND", "PROPPATCH", "MKCOL", "MOVE", "COPY", "LOCK", "UNLOCK"));
            web.httpFirewall(firewall);
        };
    }

    /**
     * 额外的 Tomcat 连接器：WebDAV 专用端口。
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> webDavConnectorCustomizer(
            @org.springframework.beans.factory.annotation.Value("${webdav.server.port:30263}") int webdavPort) {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(webdavPort);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
