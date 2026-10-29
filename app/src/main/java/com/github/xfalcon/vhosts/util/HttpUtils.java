package com.github.xfalcon.vhosts.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class HttpUtils {

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 20000;
    private static final int MAX_REDIRECTS = 5;
    /** 防止把一个超大文件整个读进内存，hosts 列表很少超过几 MB */
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    /** 部分 CDN 会拒绝 Java 默认的 User-Agent */
    private static final String USER_AGENT = "VirtualHosts/2.2.3 (Android)";

    private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    public static HttpResult get(String url) throws IOException {
        return fetchText(url, null);
    }

    /**
     * 抓取纯文本内容。
     *
     * 与原实现相比的关键差异：
     * - 手动处理 301/302/303/307/308 全部重定向，并支持相对 Location。
     *   HttpURLConnection 默认只对同协议自动跟随，http -> https 这类跨协议跳转不会跟，
     *   原实现又只判 301，导致不少地址下载失败。
     * - 先取状态码再读流。原实现直接 getInputStream()，4xx/5xx 会抛异常，
     *   无法区分"网络断"和"服务器返回 404"。
     * - 按 Content-Type 里的 charset 解码，没有则按 UTF-8，并处理 BOM。
     *   原实现 new String(bytes) 用平台默认编码，中文/非 ASCII 会乱码。
     * - 处理 gzip 响应。
     * - 限制最大字节数。
     */
    public static HttpResult fetchText(String url, Map<String, String> headers) throws IOException {
        long startAt = System.currentTimeMillis();
        String currentUrl = url;
        int redirects = 0;

        while (true) {
            HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            // 自己处理重定向，才能覆盖跨协议与全部状态码
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "text/plain, text/*, */*");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (headers != null) {
                for (String key : headers.keySet()) {
                    conn.setRequestProperty(key, headers.get(key));
                }
            }
            try {
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("HTTP " + code + " without Location header");
                    }
                    if (++redirects > MAX_REDIRECTS) {
                        throw new IOException("Too many redirects (> " + MAX_REDIRECTS + ")");
                    }
                    // Location 可能是相对路径
                    currentUrl = new URL(new URL(currentUrl), location).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    String msg = conn.getResponseMessage();
                    throw new IOException("HTTP " + code + (msg == null ? "" : " " + msg));
                }

                InputStream stream = conn.getInputStream();
                if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                    stream = new GZIPInputStream(stream);
                }
                byte[] data = readAll(stream, MAX_BYTES);
                Charset charset = detectCharset(conn.getContentType(), data);
                String text = stripBom(new String(data, charset));

                return new HttpResult(text, currentUrl, code, redirects,
                        System.currentTimeMillis() - startAt, data.length);
            } finally {
                conn.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                in.close();
                throw new IOException("Response larger than " + (maxBytes / 1024 / 1024) + " MB");
            }
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    private static Charset detectCharset(String contentType, byte[] data) {
        if (contentType != null) {
            Matcher m = CHARSET_PATTERN.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1).trim());
                } catch (Exception ignored) {
                    // 落到下面的默认值
                }
            }
        }
        // 没有声明就按 UTF-8，hosts 列表基本都是 ASCII/UTF-8
        return Charset.forName("UTF-8");
    }

    private static String stripBom(String s) {
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    /** 保留旧接口：只要正文，不要元信息 */
    static public String getBody(String url) throws IOException {
        return get(url).content;
    }

    static public String streamToString(InputStream in) throws IOException {
        return new String(readAll(in, MAX_BYTES), Charset.forName("UTF-8"));
    }

    /** 一次请求的结果，供"测试连接"展示 */
    public static class HttpResult {
        public final String content;
        public final String finalUrl;
        public final int statusCode;
        public final int redirects;
        public final long elapsedMs;
        public final int byteCount;

        public HttpResult(String content, String finalUrl, int statusCode,
                          int redirects, long elapsedMs, int byteCount) {
            this.content = content;
            this.finalUrl = finalUrl;
            this.statusCode = statusCode;
            this.redirects = redirects;
            this.elapsedMs = elapsedMs;
            this.byteCount = byteCount;
        }
    }
}
