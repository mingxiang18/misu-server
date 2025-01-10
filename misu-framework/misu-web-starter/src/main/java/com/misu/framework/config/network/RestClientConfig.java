package com.misu.framework.config.network;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 *
 * 网络请求相关配置
 *@author misu
 */
@Configuration
public class RestClientConfig {

    @Value("${rest.readTimeout:50000}")
    private int readTimeout;

    @Value("${rest.connectTimeout:50000}")
    private int connectTimeout;

    @Value("${rest.proxyIp:}")
    private String proxyIp;

    @Value("${rest.proxyPort:}")
    private Integer proxyPort;

    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .requestFactory(getClientHttpRequestFactory())
                .build();
    }

    private ClientHttpRequestFactory getClientHttpRequestFactory() {
        HttpClientBuilder httpClientBuilder = HttpClients.custom();

        //如果代理不为空，设置代理
        if (StringUtils.isNoneBlank(proxyIp) && proxyPort != null) {
            HttpHost proxy = new HttpHost(proxyIp, proxyPort);
            DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
            httpClientBuilder.setRoutePlanner(routePlanner);
        }
        CloseableHttpClient httpClient = httpClientBuilder.build();
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setHttpClient(httpClient);
        requestFactory.setConnectionRequestTimeout(readTimeout);
        requestFactory.setConnectTimeout(connectTimeout);
        return requestFactory;
    }


}
