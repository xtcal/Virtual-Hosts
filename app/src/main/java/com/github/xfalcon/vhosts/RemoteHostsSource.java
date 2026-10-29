/*
 **Copyright (C) 2017  xfalcon
 **
 **This program is free software: you can redistribute it and/or modify
 **it under the terms of the GNU General Public License as published by
 **the Free Software Foundation, either version 3 of the License, or
 **(at your option) any later version.
 **
 **This program is distributed in the hope that it will be useful,
 **but WITHOUT ANY WARRANTY; without even the implied warranty of
 **MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 **GNU General Public License for more details.
 **
 **You should have received a copy of the GNU General Public License
 **along with this program.  If not, see <http://www.gnu.org/licenses/>.
 **
 */

package com.github.xfalcon.vhosts;

import com.github.xfalcon.vhosts.util.HttpUtils;
import com.github.xfalcon.vhosts.util.LogUtils;

import java.net.URL;

/**
 * 远程 hosts 内容的抓取与校验。
 *
 * 只负责"把远端内容拿回来并判断能不能用"，不落盘、不改来源。
 * 落盘与合并由调用方（RulesActivity）决定，因为远程只是导入规则的
 * 一种入口，导入方式（替换/追加）由用户当场选择。
 */
public class RemoteHostsSource {

    private static final String TAG = RemoteHostsSource.class.getSimpleName();

    /** 下载并统计有效规则条数，供导入前确认 */
    public static Result fetch(String url) {
        Result r = new Result();
        r.url = url;
        if (!isValidUrl(url)) {
            r.message = "URL 格式不正确";
            return r;
        }
        try {
            HttpUtils.HttpResult http = HttpUtils.get(url);
            r.httpStatus = http.statusCode;
            r.finalUrl = http.finalUrl;
            r.redirects = http.redirects;
            r.elapsedMs = http.elapsedMs;
            r.byteCount = http.byteCount;
            r.content = http.content;

            if (http.content == null || http.content.trim().isEmpty()) {
                r.message = "服务器返回空内容";
                return r;
            }

            // 用与运行时相同的解析规则统计，"测试通过"就等于"导入后真的能用"
            int rules = RuleStore.countOf(http.content);
            r.ruleCount = rules;
            if (rules == 0) {
                r.message = "内容里没有可用的 hosts 规则（需要 \"IP 域名\" 格式）";
                return r;
            }
            r.success = true;
            r.message = "下载成功";
        } catch (Exception e) {
            LogUtils.e(TAG, "fetch remote hosts failed", e);
            r.message = describeError(e);
        }
        return r;
    }

    /**
     * URL 校验。
     *
     * 原实现用一条自带的正则，会误拒带端口、带 query、带 # 片段、
     * jsdelivr 那种 @ 版本号的地址。这里改用 java.net.URL 解析，
     * 只看协议与主机是否成立。
     */
    public static boolean isValidUrl(String url) {
        if (url == null) return false;
        String s = url.trim();
        if (s.isEmpty()) return false;
        try {
            URL u = new URL(s);
            String protocol = u.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                return false;
            }
            return u.getHost() != null && !u.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static String describeError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) msg = e.getClass().getSimpleName();
        if (msg.startsWith("HTTP ")) {
            if (msg.contains("404")) return "地址不存在（HTTP 404）";
            if (msg.contains("403")) return "服务器拒绝访问（HTTP 403）";
            if (msg.contains("401")) return "需要认证（HTTP 401）";
            if (msg.startsWith("HTTP 5")) return "服务器错误（" + msg + "）";
            return "请求失败（" + msg + "）";
        }
        if (e instanceof java.net.UnknownHostException) return "无法解析域名，检查地址或网络";
        if (e instanceof java.net.SocketTimeoutException) return "连接超时";
        if (e instanceof javax.net.ssl.SSLException) return "HTTPS 握手失败";
        return "下载失败: " + msg;
    }

    public static class Result {
        public String url;
        public String finalUrl;
        public boolean success;
        public int httpStatus;
        public int redirects;
        public long elapsedMs;
        public int byteCount;
        public int ruleCount;
        public String content;
        public String message;
    }
}
