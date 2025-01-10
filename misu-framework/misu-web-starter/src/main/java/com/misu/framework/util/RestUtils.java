package com.misu.framework.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.alibaba.fastjson2.annotation.JSONField;
import com.misu.common.exception.ServiceException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.beans.PropertyDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * @author misu
 */
@Component
public class RestUtils {

    @jakarta.annotation.Resource
    private RestClient restClient;

    private static final Logger log = LoggerFactory.getLogger("netRequestLogger");

    @SneakyThrows
    public <T> T get(String url, TypeReference<T> typeReference) {
        return get(url, new HttpHeaders(), typeReference, null);
    }

    @SneakyThrows
    public <T> T get(String url, HttpHeaders httpHeaders, TypeReference<T> typeReference) {
        return get(url, new HttpHeaders(), typeReference, null);
    }

    @SneakyThrows
    public <T> T get(String url, TypeReference<T> typeReference, Object requestParam) {
        return get(url, new HttpHeaders(), typeReference, requestParam);
    }

    @SneakyThrows
    public <T> T get(String url, HttpHeaders httpHeaders, TypeReference<T> typeReference, Object requestParam) {
        if (!httpHeaders.containsKey(HttpHeaders.USER_AGENT)) {
            httpHeaders.set("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.5; Windows NT)");
        }

        //如果请求参数实体不为空，将参数封装到url后缀
        if (requestParam != null) {
            url = url + packageParamString(requestParam);
        }

        log.info("向外部接口发起GET请求，url：{}", url);
        return restClient.get()
                .uri(url)
                .headers(h -> httpHeaders.forEach(h::addAll))
                .exchange((request, response) -> {
                    return packageResponse(response, typeReference);
                });
    }

    @SneakyThrows
    public <T> T postByJson(String url, Object params, TypeReference<T> typeReference) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.set("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.5; Windows NT)");
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);

        return postByJson(url, httpHeaders, params, typeReference);
    }

    public <T> T postByJson(String url, HttpHeaders httpHeaders, Object params, TypeReference<T> typeReference) {
        String jsonParams = JSON.toJSONString(params);

        log.info("向外部接口发起POST请求，url：{}，请求报文：{}", url, jsonParams);
        return restClient.post()
                .uri(url)
                .headers(h -> httpHeaders.forEach(h::addAll))
                .body(jsonParams)
                .exchange((request, response) -> {
                    return packageResponse(response, typeReference);
                });
    }

    public <T> T postByForm(String url, Object params, TypeReference<T> typeReference) {
        return postByForm(url, new HttpHeaders(), params, typeReference);
    }

    @SneakyThrows
    public <T> T postByForm(String url, HttpHeaders httpHeaders, Object params, TypeReference<T> typeReference) {
        //头部类型
        httpHeaders.set("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        httpHeaders.set("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.5; Windows NT)");

        MultiValueMap<String, Object> map = packageParamMultiValueMap(params);

        log.info("向外部接口发起form-data类型的post请求，url：{}", url);
        return restClient.post()
                .uri(url)
                .headers(h -> httpHeaders.forEach(h::addAll))
                .body(map)
                .exchange((request, response) -> {
                    return packageResponse(response, typeReference);
                });
    }

    /**
     * 封装网络请求响应体
     */
    @SneakyThrows
    private <T> T packageResponse(ClientHttpResponse response, TypeReference<T> typeReference) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new ServiceException(response.getStatusCode().value(),
                    "RestClientCallCodeException，" +
                    ", statusText: " + response.getStatusText() +
                    ", message: " + IOUtils.toString(response.getBody(), StandardCharsets.UTF_8));
        }

        if (typeReference.getRawType() == InputStream.class) {
            //如果需要返回响应流，则不处理额外处理日志或格式等，直接返回
            return (T) response.getBody();
        }else if (typeReference.getRawType() == ClientHttpResponse.class) {
            return (T) response;
        }else {
            String bodyString = null;
            // 如果header编码是gzip格式，解压gzip响应体
            if (response.getHeaders().containsKey(HttpHeaders.CONTENT_ENCODING)
                    && "gzip".equalsIgnoreCase(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING))) {
                bodyString = new String(unGZip(response.getBody()), StandardCharsets.UTF_8);
            }else {
                bodyString = IOUtils.toString(response.getBody(), StandardCharsets.UTF_8);
            }

            log.info("外部接口返回报文:{}", bodyString);
            if (typeReference.getRawType() == String.class) {
                return (T) bodyString;
            }else {
                return JSON.parseObject(bodyString, typeReference);
            }
        }
    }

    /**
     * Gzip解压缩
     * @param inputStream
     * @return
     */
    @SneakyThrows
    public byte[] unGZip(InputStream inputStream) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(inputStream)) {
            byte[] buf = new byte[4096];
            int len = -1;
            while ((len = gzipInputStream.read(buf, 0, buf.length)) != -1) {
                byteArrayOutputStream.write(buf, 0, len);
            }
            return byteArrayOutputStream.toByteArray();
        } finally {
            byteArrayOutputStream.close();
        }
    }

    /**
     * 将实体类参数封装为GET请求的URL后缀
     * @return
     */
    public String packageParamString(Object obj) {
        List<String> paramList = new ArrayList<>();

        if (obj instanceof Map) {
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) obj).entrySet()) {
                paramList.add(entry.getKey() + "=" + entry.getValue());
            }
        }else {
            //获得实体类名
            Class clazz = obj.getClass();

            while (clazz != null) {
                //获得属性
                Field[] fields = clazz.getDeclaredFields();
                //获得Object对象中的所有方法
                for (Field field : fields) {
                    try {
                        field.setAccessible(true);
                        //获取属性上的JsonProperty注解
                        JSONField jsonField = field.getAnnotation(JSONField.class);

                        PropertyDescriptor pd = new PropertyDescriptor(field.getName(), clazz);
                        //获得get方法
                        Method getMethod = pd.getReadMethod();
                        if (getMethod != null) {
                            Object value = getMethod.invoke(obj);
                            if (value != null) {
                                if (jsonField != null && StringUtils.isNoneEmpty(jsonField.name())) {
                                    //如果注解不为空，获取注解上的字段名对应的字段值
                                    paramList.add(jsonField.name() + "=" + value);
                                } else {
                                    paramList.add(field.getName() + "=" + value);
                                }
                            }
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }

                clazz = clazz.getSuperclass();
            }
        }

        if (paramList != null && paramList.size() > 0) {
            return "?" + String.join("&", paramList);
        }else {
            return "";
        }
    }

    /**
     * 将实体类参数封装为MultiValueMap类型的数据
     * @return
     */
    public MultiValueMap<String, Object> packageParamMultiValueMap(Object obj) {
        //获得实体类名
        Class clazz = obj.getClass();

        MultiValueMap<String, Object> paramMap = new LinkedMultiValueMap<>();

        if (obj instanceof MultiValueMap) {
            return (MultiValueMap<String, Object>) obj;
        }else if (obj instanceof Map) {
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) obj).entrySet()) {
                paramMap.add((String) entry.getKey(), entry.getValue());
            }
        }else {
            while (clazz != null) {
                //获得属性
                Field[] fields = clazz.getDeclaredFields();
                //获得Object对象中的所有方法
                for (Field field : fields) {
                    try {
                        field.setAccessible(true);
                        //获取属性上的JsonProperty注解
                        JSONField jsonField = field.getAnnotation(JSONField.class);

                        PropertyDescriptor pd = new PropertyDescriptor(field.getName(), clazz);
                        //获得get方法
                        Method getMethod = pd.getReadMethod();
                        if (getMethod != null) {
                            Object value = getMethod.invoke(obj);
                            if (value != null) {
                                if (value instanceof File) {
                                    value = new FileSystemResource((File) value);
                                }
                                if (jsonField != null && StringUtils.isNoneEmpty(jsonField.name())) {
                                    //如果注解不为空，获取注解上的字段名对应的字段值
                                    paramMap.add(jsonField.name(), value);
                                }else {
                                    paramMap.add(field.getName(), value);
                                }
                            }
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }

                clazz = clazz.getSuperclass();
            }
        }

        return paramMap;
    }
}
