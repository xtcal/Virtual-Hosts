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
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.*;
import android.widget.Toast;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import com.github.xfalcon.vhosts.util.LogUtils;
import org.xbill.DNS.Address;

/**
 * 设置页。
 *
 * 远程 hosts 地址与下载已经移到规则列表页（RulesActivity），
 * 因为"导入规则"和"管理规则"是同一件事，分在两处会让人不知道该去哪。
 * 这里只留与规则无关的网络配置。
 */
public class SettingsFragment extends PreferenceFragmentCompat implements
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static String TAG = SettingsFragment.class.getName();

    public static final int VPN_REQUEST_CODE = 0x0F;
    public static final int SELECT_FILE_CODE = 0x05;
    public static final String PREFS_NAME = SettingsFragment.class.getName();
    public static final String IS_NET = "IS_NET";
    public static final String HOSTS_URL = "HOSTS_URL";
    public static final String HOSTS_URI = "HOST_URI";
    public static final String NET_HOST_FILE = "net_hosts";
    public static final String IPV4_DNS = "IPV4_DNS";
    public static final String IS_CUS_DNS = "IS_CUS_DNS";

    /** 内置规则文件（由 RuleStore 管理） */
    public static final String LOCAL_HOST_FILE = "local_hosts";

    /** 规则来源常量：保留以兼容升级路径，运行时已统一走 RuleStore */
    public static final String HOSTS_SOURCE = "HOSTS_SOURCE";
    public static final String SOURCE_BUILTIN = "BUILTIN";
    public static final String SOURCE_FILE = "FILE";
    public static final String SOURCE_URL = "URL";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        PreferenceScreen prefScreen = getPreferenceScreen();
        Preference dnsCustomPref = findPreference(IPV4_DNS);

        if (dnsCustomPref != null) {
            dnsCustomPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {

                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    String ipv4_dns = (String) newValue;
                    try {
                        Address.getByAddress(ipv4_dns);
                        return true;
                    } catch (Exception e) {
                        LogUtils.e(TAG, e.getMessage(), e);
                        Toast.makeText(preference.getContext(),
                                getString(R.string.dns4_error), Toast.LENGTH_LONG).show();
                    }
                    return false;
                }
            });
        }

        updateRuleSummary();
    }

    private void updateRuleSummary() {
        Preference pref = findPreference(PREF_RULES_ENTRY);
        if (pref == null) return;
        Context ctx = getContext();
        if (ctx == null) return;
        int count = RuleStore.count(ctx);
        pref.setSummary(getString(R.string.rules_summary, count));
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                startActivity(new android.content.Intent(
                        preference.getContext(), RulesActivity.class));
                return true;
            }
        });
    }

    public static final String PREF_RULES_ENTRY = "PREF_RULES_ENTRY";

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Preference preference = findPreference(key);
        if (null != preference && preference instanceof androidx.preference.EditTextPreference) {
            String value = sharedPreferences.getString(preference.getKey(), "");
            preference.setSummary(value);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        inflater.getContext().setTheme(R.style.AppPreferenceSettingsFragmentTheme);
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        updateRuleSummary();
    }

    @Override
    public void onPause() {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

}
