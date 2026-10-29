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

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.github.xfalcon.vhosts.util.LogUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则列表：应用里唯一可编辑的规则集合。
 *
 * 本地 hosts 文件与远程 hosts 地址都只是"导入到这份列表"的入口，
 * 导入完成后与来源无关，运行时也只读这份列表。
 */
public class RulesActivity extends AppCompatActivity {

    private static final String TAG = RulesActivity.class.getSimpleName();

    private static final int REQUEST_PICK_FILE = 0x21;

    private RecyclerView list;
    private View emptyView;
    private TextView summary;

    private final List<RuleStore.Rule> rules = new ArrayList<>();
    private RuleAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rules);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.rules_title);
        }

        // 首次进入先把老版本遗留的规则搬过来
        RuleStore.migrateIfNeeded(this);

        list = findViewById(R.id.rules_list);
        emptyView = findViewById(R.id.rules_empty);
        summary = findViewById(R.id.rules_summary);

        adapter = new RuleAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.button_add_rule).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddDialog();
            }
        });
        findViewById(R.id.button_import_local).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickLocalFile();
            }
        });
        findViewById(R.id.button_import_remote).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showImportRemoteDialog();
            }
        });

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    /** 重新从存储读取并刷新列表 */
    private void reload() {
        List<RuleStore.Rule> parsed = RuleStore.parse(this);
        rules.clear();
        rules.addAll(parsed);
        adapter.notifyDataSetChanged();

        boolean empty = rules.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);

        int count = rules.size();
        if (count == 0) {
            summary.setText(R.string.rules_summary_zero);
        } else {
            summary.setText(getString(R.string.rules_summary, count));
        }
    }

    /** 把当前列表写回存储；列表里一个域名一条，写回时同 IP 合并成一行，保持文件紧凑 */
    private void persist() {
        StringBuilder sb = new StringBuilder();
        String lastIp = null;
        StringBuilder line = new StringBuilder();
        for (RuleStore.Rule r : rules) {
            String ip = r.ip;
            if (!ip.equals(lastIp)) {
                if (line.length() > 0) sb.append(line).append('\n');
                line = new StringBuilder();
                line.append(ip);
                lastIp = ip;
            }
            line.append(' ').append(r.domain);
        }
        if (line.length() > 0) sb.append(line).append('\n');

        if (!RuleStore.write(this, sb.toString())) {
            Toast.makeText(this, R.string.rules_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 增删改 ----------

    private void showAddDialog() {
        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        container.setPadding(pad, pad, pad, 0);

        final EditText ipInput = new EditText(this);
        ipInput.setHint(R.string.rules_ip_hint);
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        ipInput.setSingleLine(true);
        container.addView(ipInput);

        final EditText domainInput = new EditText(this);
        domainInput.setHint(R.string.rules_domain_hint);
        domainInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        domainInput.setSingleLine(true);
        container.addView(domainInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.rules_add)
                .setView(container)
                .setPositiveButton(R.string.dialog_confirm, null)
                .setNegativeButton(R.string.dialog_cancel, null)
                .create();
        dialog.show();
        // 自己接管点击，校验不通过时不让对话框关闭
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ip = ipInput.getText().toString().trim();
                String domain = domainInput.getText().toString().trim();
                String error = validate(ip, domain);
                if (error != null) {
                    Toast.makeText(RulesActivity.this, error, Toast.LENGTH_SHORT).show();
                    return;
                }
                rules.add(new RuleStore.Rule(ip, normalizeDomain(domain), 0));
                persist();
                reload();
                dialog.dismiss();
            }
        });
    }

    private String validate(String ip, String domain) {
        if (ip.isEmpty()) return getString(R.string.rules_err_ip_empty);
        try {
            org.xbill.DNS.Address.getByAddress(ip);
        } catch (Exception e) {
            return getString(R.string.rules_err_ip_invalid);
        }
        if (domain.isEmpty()) return getString(R.string.rules_err_domain_empty);
        String d = domain.startsWith(".") ? domain.substring(1) : domain;
        if (d.isEmpty() || d.contains(" ") || d.contains("#")) {
            return getString(R.string.rules_err_domain_invalid);
        }
        return null;
    }

    private String normalizeDomain(String domain) {
        return domain.startsWith(".") ? domain : domain;
    }

    // ---------- 导入：本地文件 ----------

    private void pickLocalFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_PICK_FILE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.file_select_error, Toast.LENGTH_LONG).show();
            LogUtils.e(TAG, "open document failed", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FILE && resultCode == Activity.RESULT_OK && data != null) {
            importFromUri(data.getData());
        }
    }

    private void importFromUri(final Uri uri) {
        if (uri == null) return;
        try {
            // 长期保留读权限，否则重启后再次导入会失败
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            LogUtils.d(TAG, "take persistable permission failed: " + e.getMessage());
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                    in.close();
                    final String content = bos.toString("UTF-8");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            askImportMode(content, getString(R.string.import_from_file));
                        }
                    });
                } catch (Exception e) {
                    LogUtils.e(TAG, "read picked file failed", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(RulesActivity.this, R.string.rules_import_failed, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    // ---------- 导入：远程地址 ----------

    private void showImportRemoteDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.rules_url_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        input.setPadding(pad, pad, pad, 0);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.rules_import_remote)
                .setView(input)
                .setPositiveButton(R.string.rules_download, null)
                .setNegativeButton(R.string.dialog_cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = input.getText().toString().trim();
                if (!RemoteHostsSource.isValidUrl(url)) {
                    Toast.makeText(RulesActivity.this, R.string.url_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                fetchRemoteAndAsk(url);
            }
        });
    }

    private void fetchRemoteAndAsk(final String url) {
        if (!RemoteHostsSource.isValidUrl(url)) {
            Toast.makeText(this, R.string.url_error, Toast.LENGTH_LONG).show();
            return;
        }
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage(R.string.remote_testing)
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RemoteHostsSource.Result result = RemoteHostsSource.fetch(url);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        progress.dismiss();
                        if (result.success) {
                            askImportMode(result.content, url);
                        } else {
                            new AlertDialog.Builder(RulesActivity.this)
                                    .setTitle(R.string.remote_test_result)
                                    .setMessage(result.message)
                                    .setPositiveButton(R.string.dialog_confirm, null)
                                    .show();
                        }
                    }
                });
            }
        }).start();
    }

    // ---------- 导入方式：追加 or 替换 ----------

    private void askImportMode(final String content, final String sourceLabel) {
        final int incoming = RuleStore.parseText(content).size();
        if (incoming == 0) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.rules_import_failed)
                    .setMessage(R.string.rules_import_no_valid)
                    .setPositiveButton(R.string.dialog_confirm, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.rules_import_title)
                .setMessage(getString(R.string.rules_import_message, incoming, sourceLabel))
                .setPositiveButton(R.string.rules_import_replace, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        RuleStore.write(RulesActivity.this, content);
                        reload();
                        Toast.makeText(RulesActivity.this,
                                getString(R.string.rules_imported, incoming), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.rules_import_append, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        RuleStore.append(RulesActivity.this, content);
                        reload();
                        Toast.makeText(RulesActivity.this,
                                getString(R.string.rules_imported, incoming), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.dialog_cancel, null)
                .show();
    }

    // ---------- 列表适配器 ----------

    private class RuleAdapter extends RecyclerView.Adapter<RuleAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            TextView domain;
            TextView ip;
            TextView badge;
            View delete;

            VH(View v) {
                super(v);
                domain = v.findViewById(R.id.rule_domain);
                ip = v.findViewById(R.id.rule_ip);
                badge = v.findViewById(R.id.rule_badge);
                delete = v.findViewById(R.id.rule_delete);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_rule, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            final RuleStore.Rule rule = rules.get(position);
            holder.domain.setText(rule.displayDomain());
            holder.ip.setText(rule.ip);
            holder.badge.setVisibility(rule.isWildcard() ? View.VISIBLE : View.GONE);
            holder.delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int pos = holder.getAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    rules.remove(pos);
                    persist();
                    reload();
                }
            });
        }

        @Override
        public int getItemCount() {
            return rules.size();
        }
    }
}
