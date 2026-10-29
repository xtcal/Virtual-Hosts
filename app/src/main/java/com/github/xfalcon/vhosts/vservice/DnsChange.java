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

package com.github.xfalcon.vhosts.vservice;

import com.github.xfalcon.vhosts.util.LogUtils;
import org.xbill.DNS.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

public class DnsChange {

    static String TAG = DnsChange.class.getSimpleName();
    static ConcurrentHashMap<String, String> DOMAINS_IP_MAPS4 = null;
    static ConcurrentHashMap<String, String> DOMAINS_IP_MAPS6 = null;


    public static ByteBuffer handle_dns_packet(Packet packet) {
        if (DOMAINS_IP_MAPS4 == null) {
            LogUtils.d(TAG, "DOMAINS_IP_MAPS IS　NULL　HOST FILE ERROR");
            return null;
        }
        try {
            ByteBuffer packet_buffer = packet.backingBuffer;
            packet_buffer.mark();
            byte[] tmp_bytes = new byte[packet_buffer.remaining()];
            packet_buffer.get(tmp_bytes);
            packet_buffer.reset();
            Message message = new Message(tmp_bytes);
            org.xbill.DNS.Record question = message.getQuestion();
            ConcurrentHashMap<String, String> DOMAINS_IP_MAPS;
            int type = question.getType();
            if (type == Type.A)
                DOMAINS_IP_MAPS = DOMAINS_IP_MAPS4;
            else if (type == Type.AAAA)
                DOMAINS_IP_MAPS = DOMAINS_IP_MAPS6;
            else return null;
            Name query_domain = message.getQuestion().getName();
            String query_string = query_domain.toString();
            LogUtils.d(TAG, "query: " + question.getType() + " :" + query_string);
            if (!DOMAINS_IP_MAPS.containsKey(query_string)) {
                query_string = "." + query_string;
                int j = 0;
                while (true) {
                    int i = query_string.indexOf(".", j);
                    if (i == -1) {
                        return null;
                    }
                    String str = query_string.substring(i);

                    if (".".equals(str) || "".equals(str)) {
                        return null;
                    }
                    if (DOMAINS_IP_MAPS.containsKey(str)) {
                        query_string = str;
                        break;
                    }
                    j = i + 1;
                }
            }
            InetAddress address = Address.getByAddress(DOMAINS_IP_MAPS.get(query_string));
            org.xbill.DNS.Record record;
            if (type == Type.A) record = new ARecord(query_domain, 1, 86400, address);
            else record = new AAAARecord(query_domain, 1, 86400, address);
            message.addRecord(record, 1);
            message.getHeader().setFlag(Flags.QR);
            packet_buffer.limit(packet_buffer.capacity());
            packet_buffer.put(message.toWire());
            packet_buffer.limit(packet_buffer.position());
            packet_buffer.reset();
            packet.swapSourceAndDestination();
            packet.updateUDPBuffer(packet_buffer, packet_buffer.remaining());
            packet_buffer.position(packet_buffer.limit());
            LogUtils.d(TAG, "hit: " + question.getType() + " :" + query_domain.toString() + " :" + address.getHostName());
            return packet_buffer;
        } catch (Exception e) {
            LogUtils.d(TAG, "dns hook error", e);
            return null;
        }

    }

    /**
     * 解析一行 hosts 内容，把其中的 IP 与全部域名逐个交给回调。
     *
     * 标准 hosts 语义允许一行多域名：
     *   127.0.0.1  localhost  myapp.local  api.myapp.local
     * 三个域名都该指向 127.0.0.1。原实现把 `([^#]*)` 整串当成一个域名去 put，
     * 结果键里带着中间空格，任何 DNS 查询都匹配不上，三个域名全部静默失效。
     * 这里改成按空白切分取出每个域名。
     *
     * 注释规则保持不变：行首 # 为整行注释，行内 # 之后为注释。
     */
    public interface RuleVisitor {
        /** @return false 表示中止解析（例如只统计时不需要继续） */
        boolean onRule(String ip, String domain);
    }

    public static void parseHosts(String content, RuleVisitor visitor) {
        if (content == null) return;
        String[] lines = content.split("\r?\n", -1);
        for (String rawLine : lines) {
            if (Thread.interrupted()) return;
            if (rawLine.length() > 1000) continue;
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash == 0) continue;              // 整行注释
            if (hash > 0) line = line.substring(0, hash);   // 去掉行内注释
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) continue;
            String ip = parts[0];
            try {
                Address.getByAddress(ip);
            } catch (Exception e) {
                continue;
            }
            for (int i = 1; i < parts.length; i++) {
                String domain = parts[i];
                if (domain.isEmpty()) continue;
                if (!visitor.onRule(ip, domain)) return;
            }
        }
    }

    /** 从规则文本装载运行用的规则表 */
    public static int loadFromText(String content) {
        final ConcurrentHashMap<String, String> maps4 = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, String> maps6 = new ConcurrentHashMap<>();
        parseHosts(content, new RuleVisitor() {
            @Override
            public boolean onRule(String ip, String domain) {
                if (ip.contains(":")) {
                    maps6.put(domain + ".", ip);
                } else {
                    maps4.put(domain + ".", ip);
                }
                return true;
            }
        });
        // 解析完成后一次性替换，避免边解析边改让运行中的查询看到半张表
        DOMAINS_IP_MAPS4 = maps4;
        DOMAINS_IP_MAPS6 = maps6;
        LogUtils.d(TAG, "loaded rules4=" + maps4.size() + " rules6=" + maps6.size());
        return maps4.size() + maps6.size();
    }

    /** 只统计条数，不碰运行中的规则表 */
    public static int countOnly(InputStream inputStream) {
        try {
            return countText(readAll(inputStream));
        } catch (IOException e) {
            LogUtils.d(TAG, "count hosts error", e);
            return 0;
        }
    }

    public static int countText(String content) {
        final int[] count = {0};
        parseHosts(content, new RuleVisitor() {
            @Override
            public boolean onRule(String ip, String domain) {
                count[0]++;
                return true;
            }
        });
        return count[0];
    }

    private static String readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = inputStream.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        inputStream.close();
        return bos.toString("UTF-8");
    }

    /** 兼容保留：从流装载规则表 */
    public static int handle_hosts(InputStream inputStream) {
        try {
            return loadFromText(readAll(inputStream));
        } catch (IOException e) {
            LogUtils.d(TAG, "Hook dns error", e);
            return 0;
        }
    }

}
