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

import android.content.Context;
import com.github.xfalcon.vhosts.util.FileUtils;
import com.github.xfalcon.vhosts.util.LogUtils;
import com.github.xfalcon.vhosts.vservice.DnsChange;
import org.xbill.DNS.Address;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则列表的唯一存取入口。
 *
 * 应用里现在只有一份规则：用户可编辑的列表。
 * 本地 hosts 文件、远程 hosts 地址都只是"导入到这份列表"的来源，
 * 导入之后与来源再无关系，也不再参与运行时读取。
 *
 * 这样做的理由：原先规则来源有内置/本地文件/远程三套并存，
 * 用户改了一处不知道另一处会不会覆盖，运行结果取决于哪个开关是开的。
 * 统一成一份列表后，"看到的就是生效的"。
 *
 * 存储格式仍是纯文本（IP 域名 每行一条），因为 DnsChange 的解析
 * 与泛解析匹配都建立在这个格式上，换格式没有收益还会破坏兼容。
 */
public class RuleStore {

    private static final String TAG = RuleStore.class.getSimpleName();

    /** 规则列表文件名 */
    public static final String RULES_FILE = "rules.hosts";

    /** 老版本遗留的文件，仅用于一次性迁移 */
    private static final String LEGACY_LOCAL_FILE = "local_hosts";
    private static final String LEGACY_NET_FILE = "net_hosts";

    /** 读取完整规则文本 */
    public static String read(Context context) {
        try {
            InputStream in = context.openFileInput(RULES_FILE);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            LogUtils.d(TAG, "rules file not exists yet");
            return "";
        }
    }

    /** 覆盖写入 */
    public static boolean write(Context context, String content) {
        try {
            FileUtils.writeFile(
                    context.openFileOutput(RULES_FILE, Context.MODE_PRIVATE),
                    content == null ? "" : content);
            return true;
        } catch (Exception e) {
            LogUtils.e(TAG, "write rules failed", e);
            return false;
        }
    }

    /** 追加一批规则，自动补换行，避免和已有内容粘在一行 */
    public static boolean append(Context context, String content) {
        if (content == null || content.trim().isEmpty()) return false;
        String existing = read(context);
        StringBuilder sb = new StringBuilder(existing);
        if (existing.length() > 0 && !existing.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append(content);
        if (!content.endsWith("\n")) {
            sb.append('\n');
        }
        return write(context, sb.toString());
    }

    /** 规则条数（用运行时同一套解析规则统计） */
    public static int count(Context context) {
        return countOf(read(context));
    }

    public static int countOf(String content) {
        if (content == null || content.isEmpty()) return 0;
        try {
            return DnsChange.countOnly(
                    new ByteArrayInputStream(content.getBytes(Charset.forName("UTF-8"))));
        } catch (Exception e) {
            LogUtils.e(TAG, "count rules failed", e);
            return 0;
        }
    }

    public static boolean isEmpty(Context context) {
        return read(context).trim().isEmpty();
    }

    /**
     * 解析成 (ip, domain) 列表，供列表式 UI 展示。
     *
     * 这里刻意不依赖 DnsChange 的内部 map：那里按域名建索引，会丢原始顺序，
     * 而列表要按用户书写顺序显示。
     */
    public static List<Rule> parse(Context context) {
        return parseText(read(context));
    }

    public static List<Rule> parseText(String content) {
        List<Rule> out = new ArrayList<>();
        if (content == null) return out;
        String[] lines = content.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) continue;
            String ip = parts[0];
            try {
                Address.getByAddress(ip);
            } catch (Exception e) {
                continue;
            }
            for (int j = 1; j < parts.length; j++) {
                String domain = parts[j];
                if (domain.isEmpty()) continue;
                out.add(new Rule(ip, domain, i + 1));
            }
        }
        return out;
    }

    /**
     * 一次性迁移老版本数据。
     *
     * 老版本可能把规则放在 local_hosts（内置编辑）或 net_hosts（远程下载），
     * 升级后如果 rules.hosts 还不存在，就把已有的那份搬过来，
     * 免得用户升级完发现规则没了。
     */
    public static void migrateIfNeeded(Context context) {
        try {
            if (!read(context).trim().isEmpty()) return;

            String fromLocal = readFileQuietly(context, LEGACY_LOCAL_FILE);
            if (fromLocal != null && !fromLocal.trim().isEmpty()) {
                write(context, fromLocal);
                LogUtils.d(TAG, "migrated rules from " + LEGACY_LOCAL_FILE);
                return;
            }
            String fromNet = readFileQuietly(context, LEGACY_NET_FILE);
            if (fromNet != null && !fromNet.trim().isEmpty()) {
                write(context, fromNet);
                LogUtils.d(TAG, "migrated rules from " + LEGACY_NET_FILE);
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "migration failed", e);
        }
    }

    private static String readFileQuietly(Context context, String name) {
        try {
            InputStream in = context.openFileInput(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** 列表展示用的一条规则 */
    public static class Rule {
        public final String ip;
        public final String domain;
        public final int lineNumber;

        public Rule(String ip, String domain, int lineNumber) {
            this.ip = ip;
            this.domain = domain;
            this.lineNumber = lineNumber;
        }

        /** hosts 语义里域名带前导点表示泛解析 */
        public boolean isWildcard() {
            return domain.startsWith(".");
        }

        public String displayDomain() {
            return isWildcard() ? domain.substring(1) : domain;
        }
    }
}
