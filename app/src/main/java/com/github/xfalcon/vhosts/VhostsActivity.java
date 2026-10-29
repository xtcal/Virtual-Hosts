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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.github.xfalcon.vhosts.util.LogUtils;
import com.github.xfalcon.vhosts.vservice.VhostsService;
import com.github.xfalcon.vhosts.widget.LabeledSwitch;
import com.google.firebase.analytics.FirebaseAnalytics;

public class VhostsActivity extends AppCompatActivity {

    private static final String TAG = VhostsActivity.class.getSimpleName();

    private FirebaseAnalytics mFirebaseAnalytics;

    private boolean waitingForVPNStart;

    private LabeledSwitch switchVpn;
    private TextView statusText;
    private TextView statusDesc;
    private TextView ruleCountText;
    private View statusDot;
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
        buttonEditRules = findViewById(R.id.button_edit_rules);
        buttonBoot = findViewById(R.id.button_boot);
        sourceValueText = findViewById(R.id.source_value_text);
        Button buttonSettings = findViewById(R.id.button_settings);
        Button buttonDonation = findViewById(R.id.button_donation);

        switchVpn.setOnCheckedChangeListener(new LabeledSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(LabeledSwitch view, boolean isChecked) {
                if (isChecked) {
                    if (RuleStore.count(VhostsActivity.this) == 0) {
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
                startActivity(new Intent(getApplicationContext(), RulesActivity.class));
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

    private void startVPN() {
        waitingForVPNStart = false;
        Intent vpnIntent = VhostsService.prepare(this);
        if (vpnIntent != null)
            startActivityForResult(vpnIntent, SettingsFragment.VPN_REQUEST_CODE);
        else
            onActivityResult(SettingsFragment.VPN_REQUEST_CODE, RESULT_OK, null);
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
        buttonEditRules.setAlpha(interactive ? 1.0f : 0.5f);
        buttonEditRules.setClickable(interactive);
    }

    /** 刷新状态卡：运行状态与规则条数 */
    private void refreshStatus() {
        boolean running = VhostsService.isRunning();

        statusText.setText(running ? R.string.status_running : R.string.status_stopped);
        statusDesc.setText(running ? R.string.status_running_desc : R.string.status_stopped_desc);
        statusDot.setBackgroundResource(running ? R.drawable.dot_running : R.drawable.dot_stopped);

        int count = RuleStore.count(this);
        if (count > 0) {
            ruleCountText.setText(getString(R.string.status_rule_count, count));
            ruleCountText.setTextColor(getResources().getColor(R.color.state_running));
            ruleCountText.setBackgroundResource(R.drawable.bg_status_running);
            sourceValueText.setText(getString(R.string.rules_summary, count));
        } else {
            ruleCountText.setText(R.string.status_no_rules);
            ruleCountText.setTextColor(getResources().getColor(R.color.secondary_text));
            ruleCountText.setBackgroundResource(R.drawable.bg_status_stopped);
            sourceValueText.setText(R.string.rules_empty_hint);
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
                startActivity(new Intent(getApplicationContext(), RulesActivity.class));
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
