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

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.github.xfalcon.vhosts.util.FileUtils;
import com.github.xfalcon.vhosts.util.LogUtils;
import com.github.xfalcon.vhosts.vservice.DnsChange;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 全屏 hosts 规则编辑器。
 *
 * 这里刻意复用 {@link DnsChange#handle_hosts(InputStream)} 做校验，而不是自己再写一套解析：
 * 编辑器里头数出来的条数必须和 VPN 启动时真正加载的条数一致，否则用户会看到
 * "编辑器说 5 条、状态卡说 3 条"这种对不上的情况。
 */
public class HostsEditorActivity extends AppCompatActivity {

    private static final String TAG = HostsEditorActivity.class.getSimpleName();

    private EditText input;
    private TextView countText;

    /** 打开编辑器时的原始内容，用于判断是否真的改过 */
    private String originalContent = "";
    private boolean saved = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hosts_editor);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.editor_title);
        }

        input = findViewById(R.id.editor_input);
        countText = findViewById(R.id.editor_count_text);
        Button sample = findViewById(R.id.editor_button_sample);
        Button save = findViewById(R.id.editor_button_save);

        originalContent = readStoredRules();
        input.setText(originalContent);
        // 光标放到末尾，避免打开就全选/停在开头
        input.setSelection(input.getText().length());
        updateCount(input.getText().toString());

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateCount(s.toString());
            }
        });

        sample.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String sampleText = getString(R.string.editor_sample);
                int start = Math.max(input.getSelectionStart(), 0);
                input.getText().insert(start, sampleText);
            }
        });

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveRules();
            }
        });
    }

    /** 用与运行时完全相同的解析规则算条数，但不改动运行中的规则表 */
    private int countRules(String content) {
        try {
            InputStream in = new ByteArrayInputStream(content.getBytes());
            return DnsChange.countOnly(in);
        } catch (Exception e) {
            LogUtils.e(TAG, "count rules failed", e);
            return 0;
        }
    }

    private void updateCount(String content) {
        int count = countRules(content);
        if (count > 0) {
            countText.setText(getString(R.string.editor_parsed_count, count));
            countText.setTextColor(getResources().getColor(R.color.state_running));
        } else {
            countText.setText(R.string.editor_parsed_zero);
            countText.setTextColor(getResources().getColor(R.color.secondary_text));
        }
    }

    private String readStoredRules() {
        try {
            InputStream in = openFileInput(SettingsFragment.LOCAL_HOST_FILE);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toString();
        } catch (Exception e) {
            // 首次进入没有文件，属正常情况
            LogUtils.d(TAG, "no local hosts file yet");
            return "";
        }
    }

    private void saveRules() {
        String content = input.getText().toString();
        try {
            FileUtils.writeFile(openFileOutput(SettingsFragment.LOCAL_HOST_FILE, MODE_PRIVATE), content);

            // 保存后把来源切到内置，否则用户编完发现没生效
            SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putString(SettingsFragment.HOSTS_SOURCE, SettingsFragment.SOURCE_BUILTIN).apply();

            saved = true;
            Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            LogUtils.e(TAG, "save rules failed", e);
            Toast.makeText(this, R.string.down_error, Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasUnsavedChanges() {
        return !saved && !input.getText().toString().equals(originalContent);
    }

    private void confirmDiscard() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_discard_title)
                .setMessage(R.string.editor_discard_message)
                .setPositiveButton(R.string.editor_discard_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setNegativeButton(R.string.editor_cancel, null)
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            // 有未保存改动时先确认，避免误触返回键丢内容
            if (hasUnsavedChanges()) {
                confirmDiscard();
            } else {
                finish();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (hasUnsavedChanges()) {
            confirmDiscard();
        } else {
            super.onBackPressed();
        }
    }
}
