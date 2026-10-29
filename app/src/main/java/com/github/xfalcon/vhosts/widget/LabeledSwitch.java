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

package com.github.xfalcon.vhosts.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import com.github.xfalcon.vhosts.R;

/**
 * 带左右两侧状态文字的开关。
 *
 * 用来替代原先 com.suke.widget.SwitchButton + rotation="-90" 的做法：
 * 旋转控件会让触控热区和实际绘制位置错位，且该库并不读取 android:text。
 * 这里用系统 Switch 加两侧文字，语义和点击区域都是明确的。
 */
public class LabeledSwitch extends FrameLayout {

    private Switch toggle;
    private TextView textOn;
    private TextView textOff;
    private OnCheckedChangeListener listener;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(LabeledSwitch view, boolean isChecked);
    }

    public LabeledSwitch(Context context) {
        super(context);
        init(context);
    }

    public LabeledSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public LabeledSwitch(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.widget_labeled_switch, this, true);
        toggle = findViewById(R.id.toggle_switch);
        textOn = findViewById(R.id.toggle_text_on);
        textOff = findViewById(R.id.toggle_text_off);

        // 整行都可点，避免只有那个小开关能点中
        toggle.setClickable(false);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle.setChecked(!toggle.isChecked());
            }
        });

        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                applyState(isChecked);
                if (listener != null) listener.onCheckedChanged(LabeledSwitch.this, isChecked);
            }
        });

        applyState(false);
    }

    /** 切换状态但不触发回调，供外部同步 UI 用 */
    public void setCheckedSilently(boolean checked) {
        if (toggle.isChecked() != checked) {
            toggle.setChecked(checked);
        }
        applyState(checked);
    }

    public boolean isChecked() {
        return toggle.isChecked();
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        this.listener = l;
    }

    /**
     * 只刷新两侧文字，不回头设置 toggle 的选中态。
     *
     * 早先这里也调了 toggle.setChecked()，会和 OnCheckedChangeListener 形成递归。
     */
    private void applyState(boolean checked) {
        textOn.setVisibility(checked ? VISIBLE : GONE);
        textOff.setVisibility(checked ? GONE : VISIBLE);
    }
}
