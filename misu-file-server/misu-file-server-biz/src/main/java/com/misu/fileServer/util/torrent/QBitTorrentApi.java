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
     * qBitTorrent登陆后的cookie ("name=value" 整段；老版本是 SID=xxx，新版本 v5+ 是 QBT_SID_<port>=xxx)
     */
    private static final String cookieKey = "qBitTorrent-cookie";

    /**
     * 匹配 qBitTorrent 登录返回的 Set-Cookie 中的 SID 段。
     * 兼容两种格式：
     *   - 旧版: "SID=xxx"
     *   - v5+ : "QBT_SID_<port>=xxx" (端口后缀方便用户同时跑多实例)
     * 捕获完整的 "name=value" 整段（含 cookie name 自身）；后续直接拼到
     * Cookie 请求头里，避免硬编码"SID"前缀对不上服务端实际期待的 cookie name。
     */
    private final Pattern cookieSidRegex = Pattern.compile("((?:QBT_)?SID(?:_\\d+)?=[^;]+)");

    @Resource
    private RestUtils restUtils;

    /**
     * 登录
     * @return 完整的 "cookieName=value" 字符串（不带分号/HttpOnly 等属性），供后续请求 Cookie 头直接复用
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
            if (cookies == null || cookies.isEmpty()) {
                throw new ServiceException(HttpStatus.ERROR, "登录qBitTorrent失败：响应无 Set-Cookie，多半是账号密码错或 qBitTorrent 配了 IP 白名单豁免（白名单豁免下 body=Ok. 但不发 cookie）");
            }
            for (String cookie : cookies) {
                // 格式举例：QBT_SID_30120=Bp6V/...; HttpOnly; SameSite=Strict; path=/
                Matcher matcher = cookieSidRegex.matcher(cookie);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            throw new ServiceException(HttpStatus.ERROR, "无法在 qBitTorrent 的 Set-Cookie 里找到 SID 段：" + cookies);
        }
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
     * 返回的 HttpHeaders 已经设置好 Cookie 头，可直接交给后续请求。
     */
    private HttpHeaders getCookieHeader() {
        //从缓存获取，如果没有则调用登录接口获取
        String cookie = CacheUtils.getCacheObject(cookieKey);
        if (cookie == null) {
            cookie = login();
            CacheUtils.setCacheObject(cookieKey, cookie);
        }

        //封装为cookie的header。cookie 变量本身就是 "name=value" 整段，直接放进 Cookie 头。
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.set(HttpHeaders.COOKIE, cookie);

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
                httpHeaders.set(HttpHeaders.COOKIE, cookie);
            } else {
                throw se;
            }
        }

        return httpHeaders;
    }
}
