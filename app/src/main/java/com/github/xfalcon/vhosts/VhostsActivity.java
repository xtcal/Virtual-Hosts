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

import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.github.xfalcon.vhosts.util.LogUtils;
import com.github.xfalcon.vhosts.vservice.DnsChange;
import com.github.xfalcon.vhosts.vservice.VhostsService;
import com.github.xfalcon.vhosts.widget.LabeledSwitch;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.io.InputStream;
import java.lang.reflect.Field;

public class VhostsActivity extends AppCompatActivity {

    private static final String TAG = VhostsActivity.class.getSimpleName();

    private FirebaseAnalytics mFirebaseAnalytics;

    private boolean waitingForVPNStart;

    private LabeledSwitch switchVpn;
    private TextView statusText;
    private TextView statusDesc;
    private TextView ruleCountText;
    private View statusDot;
    private Button buttonSelectHosts;
    private Button buttonEditRules;
    private Button buttonBoot;
    private TextView sourceValueText;

    private BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (VhostsService.BROADCAST_VPN_STATE.equals(intent.getAction())) {
                if (intent.getBooleanExtra("running", false)) {
                    waitingForVPNStart = false;
                    refreshStatus();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launch();

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

        setContentView(R.layout.activity_vhosts);
        LogUtils.context = getApplicationContext();

        switchVpn = findViewById(R.id.switch_vpn);
        statusText = findViewById(R.id.status_text);
        statusDesc = findViewById(R.id.status_desc);
        ruleCountText = findViewById(R.id.rule_count_text);
        statusDot = findViewById(R.id.status_dot);
        buttonSelectHosts = findViewById(R.id.button_select_hosts);
        buttonEditRules = findViewById(R.id.button_edit_rules);
        buttonBoot = findViewById(R.id.button_boot);
        sourceValueText = findViewById(R.id.source_value_text);
        Button buttonSettings = findViewById(R.id.button_settings);
        Button buttonDonation = findViewById(R.id.button_donation);

        switchVpn.setOnCheckedChangeListener(new LabeledSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(LabeledSwitch view, boolean isChecked) {
                if (isChecked) {
                    if (!hasUsableRules()) {
                        showDialog();
                    } else {
                        startVPN();
                    }
                } else {
                    shutdownVPN();
                }
            }
        });

        buttonEditRules.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(getApplicationContext(), HostsEditorActivity.class));
            }
        });
        buttonSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(getApplicationContext(), SettingsActivity.class));
            }
        });
        buttonBoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = !BootReceiver.getEnabled(v.getContext());
                BootReceiver.setEnabled(v.getContext(), enabled);
                updateBootButton();
            }
        });
        buttonSelectHosts.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectFile();
            }
        });
        buttonDonation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(getApplicationContext(), DonationActivity.class));
            }
        });

        LocalBroadcastManager.getInstance(this).registerReceiver(vpnStateReceiver,
                new IntentFilter(VhostsService.BROADCAST_VPN_STATE));
    }

    private void launch() {
        Uri uri = getIntent().getData();
        if (uri == null) return;
        String data_str = uri.toString();
        if ("on".equals(data_str)) {
            if (!VhostsService.isRunning())
                VhostsService.startVService(this, 1);
            finish();
        } else if ("off".equals(data_str)) {
            VhostsService.stopVService(this);
            finish();
        }
    }

    private void selectFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        try {
            String SHOW_ADVANCED;
            try {
                Field f = android.provider.DocumentsContract.class.getField("EXTRA_SHOW_ADVANCED");
                SHOW_ADVANCED = f.get(f.getName()).toString();
            } catch (NoSuchFieldException e) {
                LogUtils.e(TAG, e.getMessage(), e);
                SHOW_ADVANCED = "android.content.extra.SHOW_ADVANCED";
            }
            intent.putExtra(SHOW_ADVANCED, true);
        } catch (Throwable e) {
            LogUtils.e(TAG, "SET EXTRA_SHOW_ADVANCED", e);
        }

        try {
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, SettingsFragment.SELECT_FILE_CODE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.file_select_error, Toast.LENGTH_LONG).show();
            LogUtils.e(TAG, "START SELECT_FILE_ACTIVE FAIL", e);
            SharedPreferences settings = getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = settings.edit();
            editor.putBoolean(SettingsFragment.IS_NET, true);
            editor.apply();
            startActivity(new Intent(getApplicationContext(), SettingsActivity.class));
        }

    }

    private void startVPN() {
        waitingForVPNStart = false;
        Intent vpnIntent = VhostsService.prepare(this);
        if (vpnIntent != null)
            startActivityForResult(vpnIntent, SettingsFragment.VPN_REQUEST_CODE);
        else
            onActivityResult(SettingsFragment.VPN_REQUEST_CODE, RESULT_OK, null);
    }

    /**
     * 判断当前来源下是否存在可用的规则。
     *
     * 原来的 checkHostUri() 只区分本地/远程两种，且远程文件缺失返回 -2 时
     * 按钮文案不会更新（代码里只处理了 -1），这里统一成"能不能用"的语义。
     */
    private boolean hasUsableRules() {
        String source = SettingsFragment.getHostsSource(this);
        SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        try {
            if (SettingsFragment.SOURCE_BUILTIN.equals(source)) {
                openFileInput(SettingsFragment.LOCAL_HOST_FILE).close();
                return true;
            } else if (SettingsFragment.SOURCE_URL.equals(source)) {
                openFileInput(SettingsFragment.NET_HOST_FILE).close();
                return true;
            } else {
                String uri = prefs.getString(SettingsFragment.HOSTS_URI, null);
                if (uri == null) return false;
                getContentResolver().openInputStream(Uri.parse(uri)).close();
                return true;
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "rules not usable", e);
            return false;
        }
    }

    /** 统计当前来源下的规则条数，显示在状态卡右上角 */
    private int countCurrentRules() {
        String source = SettingsFragment.getHostsSource(this);
        SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        try {
            InputStream in;
            if (SettingsFragment.SOURCE_BUILTIN.equals(source)) {
                in = openFileInput(SettingsFragment.LOCAL_HOST_FILE);
            } else if (SettingsFragment.SOURCE_URL.equals(source)) {
                in = openFileInput(SettingsFragment.NET_HOST_FILE);
            } else {
                String uri = prefs.getString(SettingsFragment.HOSTS_URI, null);
                if (uri == null) return 0;
                in = getContentResolver().openInputStream(Uri.parse(uri));
            }
            return DnsChange.countOnly(in);
        } catch (Exception e) {
            LogUtils.d(TAG, "count rules failed: " + e.getMessage());
            return 0;
        }
    }

    private void setUriByPREFS(Intent intent) {
        SharedPreferences settings = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = settings.edit();
        Uri uri = intent.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            editor.putString(SettingsFragment.HOSTS_URI, uri.toString());
            // 选了文件就把来源切过去，否则选了也不生效
            editor.putString(SettingsFragment.HOSTS_SOURCE, SettingsFragment.SOURCE_FILE);
            editor.apply();
            refreshStatus();
            if (!hasUsableRules()) {
                Toast.makeText(this, R.string.permission_error, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "permission error", e);
        }

    }

    private void shutdownVPN() {
        if (VhostsService.isRunning())
            startService(new Intent(this, VhostsService.class).setAction(VhostsService.ACTION_DISCONNECT));
        setSwitchState(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SettingsFragment.VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            waitingForVPNStart = true;
            startService(new Intent(this, VhostsService.class).setAction(VhostsService.ACTION_CONNECT));
            setSwitchState(false);
        } else if (requestCode == SettingsFragment.SELECT_FILE_CODE && resultCode == RESULT_OK) {
            setUriByPREFS(data);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setSwitchState(!waitingForVPNStart && !VhostsService.isRunning());
        refreshStatus();
        updateBootButton();
    }

    /**
     * 同步开关状态。
     *
     * 参数沿用原来的 "enable" 语义：true 表示停止态（可以操作），
     * 但用 setCheckedSilently 避免触发启动/停止回调。
     */
    private void setSwitchState(boolean stopped) {
        switchVpn.setCheckedSilently(!stopped);
        boolean interactive = stopped;
        buttonSelectHosts.setAlpha(interactive ? 1.0f : 0.5f);
        buttonSelectHosts.setClickable(interactive);
        buttonEditRules.setAlpha(interactive ? 1.0f : 0.5f);
        buttonEditRules.setClickable(interactive);
    }

    /** 刷新状态卡：运行状态、来源说明、规则条数 */
    private void refreshStatus() {
        boolean running = VhostsService.isRunning();

        statusText.setText(running ? R.string.status_running : R.string.status_stopped);
        statusDesc.setText(running ? R.string.status_running_desc : R.string.status_stopped_desc);
        statusDot.setBackgroundResource(running ? R.drawable.dot_running : R.drawable.dot_stopped);

        String source = SettingsFragment.getHostsSource(this);
        int sourceLabel;
        if (SettingsFragment.SOURCE_BUILTIN.equals(source)) {
            sourceLabel = R.string.source_builtin;
        } else if (SettingsFragment.SOURCE_URL.equals(source)) {
            sourceLabel = R.string.source_url;
        } else {
            sourceLabel = R.string.source_file;
        }
        sourceValueText.setText(sourceLabel);

        int count = countCurrentRules();
        if (count > 0) {
            ruleCountText.setText(getString(R.string.status_rule_count, count));
            ruleCountText.setTextColor(getResources().getColor(R.color.state_running));
            ruleCountText.setBackgroundResource(R.drawable.bg_status_running);
        } else {
            ruleCountText.setText(R.string.status_no_rules);
            ruleCountText.setTextColor(getResources().getColor(R.color.secondary_text));
            ruleCountText.setBackgroundResource(R.drawable.bg_status_stopped);
        }
    }

    private void updateBootButton() {
        boolean enabled = BootReceiver.getEnabled(this);
        buttonBoot.setText(enabled ? R.string.action_boot_on : R.string.action_boot_off);
    }

    private void showDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);
        builder.setTitle(R.string.dialog_title);
        builder.setMessage(R.string.dialog_message);
        builder.setPositiveButton(R.string.dialog_confirm, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                startActivity(new Intent(getApplicationContext(), HostsEditorActivity.class));
            }
        });

        builder.setNegativeButton(R.string.dialog_cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                setSwitchState(true);
            }
        });
        builder.show();
    }

}
