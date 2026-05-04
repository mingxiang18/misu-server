package com.misu.fileServer.util.torrent;

import com.alibaba.fastjson2.TypeReference;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.common.util.CacheUtils;
import com.misu.fileServer.util.torrent.request.AddTorrentRequest;
import com.misu.fileServer.util.torrent.request.DeleteTorrentRequest;
import com.misu.fileServer.util.torrent.request.TorrentHashRequest;
import com.misu.fileServer.util.torrent.request.TorrentInfoRequest;
import com.misu.fileServer.util.torrent.response.TorrentInfoResponse;
import com.misu.framework.util.RestUtils;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * qBitTorrent磁力管理客户端相关Api
 *
 * @author misu
 */
@Component
public class QBitTorrentApi {

    @Value("${file-server.qBitTorrent.apiBaseUrl:http://10.8.0.1:30103}")
    private String apiBaseUrl;
    @Value("${file-server.qBitTorrent.username:admin}")
    private String username;
    @Value("${file-server.qBitTorrent.password:}")
    private String password;

    /**
     * qBitTorrent登陆后的cookie
     */
    private static final String cookieKey = "qBitTorrent-cookie";

    private final Pattern cookeSidRegex = Pattern.compile("SID=([^;]+)");

    @Resource
    private RestUtils restUtils;

    /**
     * 登录
     */
    public String login() {
        String loginUrl = apiBaseUrl + "/api/v2/auth/login";

        MultiValueMap<String, Object> dataMap = new LinkedMultiValueMap<>();
        dataMap.add("username", username);
        dataMap.add("password", password);

        //请求登录接口
        try (ClientHttpResponse response = restUtils.postByForm(loginUrl, dataMap, new TypeReference<ClientHttpResponse>() {});) {
            // 获取响应中的 Set-Cookie 头（如果有）
            List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (cookies != null) {
                for (String cookie : cookies) {
                    // 格式：SID=O+ro863UEuLukR3JVdBml/biw/CSDVqZ; HttpOnly; SameSite=Strict; path=/
                    // 提取SID
                    Matcher matcher = cookeSidRegex.matcher(cookie);
                    if (matcher.find()) {
                        // 提取 SID 值
                        return matcher.group(1);
                    } else {
                        throw new ServiceException(HttpStatus.ERROR, "无法获取qBitTorrent的cookie的sid");
                    }
                }
            } else {
                throw new ServiceException(HttpStatus.ERROR, "登录qBitTorrent失败");
            }
        }

        return null;
    }

    /**
     * 获取torrent列表
     */
    public List<TorrentInfoResponse> getTorrentList(TorrentInfoRequest torrentInfoRequest) {
        String fullUrl = apiBaseUrl + "/api/v2/torrents/info";

        //请求接口
        return restUtils.get(fullUrl, getCookieHeader(),
                new TypeReference<List<TorrentInfoResponse>>() {}, torrentInfoRequest);
    }

    /**
     * 添加torrent
     */
    public void addNewTorrent(AddTorrentRequest addTorrentRequest) {
        String fullUrl = apiBaseUrl + "/api/v2/torrents/add";

        //请求接口
        restUtils.postByForm(fullUrl, getCookieHeader(), addTorrentRequest, new TypeReference<String>() {});
    }

    /**
     * 暂停torrent下载
     */
    public void stopTorrent(TorrentHashRequest torrentHashRequest) {
        String fullUrl = apiBaseUrl + "/api/v2/torrents/stop";

        //请求接口
        restUtils.postByForm(fullUrl, getCookieHeader(), torrentHashRequest, new TypeReference<String>() {});
    }

    /**
     * 恢复torrent下载
     */
    public void startTorrent(TorrentHashRequest torrentHashRequest) {
        String fullUrl = apiBaseUrl + "/api/v2/torrents/start";

        //请求接口
        restUtils.postByForm(fullUrl, getCookieHeader(), torrentHashRequest, new TypeReference<String>() {});
    }

    /**
     * 删除torrent
     */
    public void deleteTorrent(DeleteTorrentRequest deleteTorrentRequest) {
        String fullUrl = apiBaseUrl + "/api/v2/torrents/delete";

        //请求接口
        restUtils.postByForm(fullUrl, getCookieHeader(), deleteTorrentRequest, new TypeReference<String>() {});
    }

    /**
     * 登录qBitTorrent获取cookie
     */
    private HttpHeaders getCookieHeader() {
        //从缓存获取，如果没有则调用登录接口获取
        String cookie = CacheUtils.getCacheObject(cookieKey);
        if (cookie == null) {
            cookie = login();
            CacheUtils.setCacheObject(cookieKey, cookie);
        }

        //封装为cookie的header
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.set(HttpHeaders.COOKIE, "SID=" + cookie);

        //检查cookie是否有效
        try {
            //调用一下获取版本接口看看有无异常
            String fullUrl = apiBaseUrl + "/api/v2/app/version";
            restUtils.get(fullUrl, httpHeaders, new TypeReference<String>() {});
        }catch (ServiceException se) {
            if (HttpStatus.FORBIDDEN == se.getCode()) {
                //如果cookie无效，重新登录并更新cookie
                cookie = login();
                CacheUtils.setCacheObject(cookieKey, cookie);
                httpHeaders.set(HttpHeaders.COOKIE, "SID=" + cookie);
            } else {
                throw se;
            }
        }

        return httpHeaders;
    }
}
