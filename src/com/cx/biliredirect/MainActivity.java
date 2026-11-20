package com.cx.biliredirect;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String PREF = "config";
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(PREF, MODE_PRIVATE);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("B站跳转重定向");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("把 QQ 里「开启哔哩哔哩应用」的跳转，改到第三方客户端。\n"
                + "改完设置后无需重启，下一次跳转即生效。");
        sub.setTextSize(13);
        sub.setTextColor(Color.GRAY);
        sub.setPadding(0, 0, 0, dp(16));
        root.addView(sub);

        final Switch sw = new Switch(this);
        sw.setText("启用重定向");
        sw.setChecked(sp.getBoolean("enabled", true));
        sw.setPadding(0, dp(8), 0, dp(8));
        root.addView(sw);

        final Switch swImplicit = new Switch(this);
        swImplicit.setText("同时接管隐式链接（bilibili:// 与 b23.tv）");
        swImplicit.setChecked(sp.getBoolean("implicit_too", true));
        swImplicit.setPadding(0, dp(8), 0, dp(8));
        root.addView(swImplicit);

        final Switch swSkip = new Switch(this);
        swSkip.setText("跳过「即将离开QQ」确认弹窗");
        swSkip.setChecked(sp.getBoolean("skip_dialog", true));
        swSkip.setPadding(0, dp(8), 0, dp(8));
        root.addView(swSkip);

        final Switch swLog = new Switch(this);
        swLog.setText("日志取证：记录所有跳转 Intent");
        swLog.setChecked(sp.getBoolean("log_all", false));
        swLog.setPadding(0, dp(8), 0, dp(16));
        root.addView(swLog);

        TextView lbl = new TextView(this);
        lbl.setText("目标应用包名");
        lbl.setTextSize(14);
        lbl.setPadding(0, dp(8), 0, dp(4));
        root.addView(lbl);

        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(sp.getString("target_pkg", "com.example.piliplus"));
        et.setSingleLine(true);
        root.addView(et);

        TextView hint = new TextView(this);
        hint.setText("PiliPlus = com.example.piliplus\n"
                + "哔哩漫游/其他客户端请填它自己的包名。\n"
                + "官方 B 站包名 tv.danmaku.bili 会被忽略（防止自己跳自己）。");
        hint.setTextSize(12);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, dp(6), 0, dp(16));
        root.addView(hint);

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                sp.edit()
                        .putBoolean("enabled", sw.isChecked())
                        .putBoolean("implicit_too", swImplicit.isChecked())
                        .putBoolean("skip_dialog", swSkip.isChecked())
                        .putBoolean("log_all", swLog.isChecked())
                        .putString("target_pkg", et.getText().toString().trim())
                        .apply();
                Toast.makeText(MainActivity.this, "已保存", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(save);

        TextView scope = new TextView(this);
        scope.setText("\nLSPosed 里请把本模块的作用域勾选：\n"
                + "• QQ（com.tencent.mobileqq）\n"
                + "• 微信（com.tencent.mm）—— 微信里跳 B 站同理\n"
                + "勾选后强制停止 QQ 再打开，或重启手机。\n\n"
                + "排查用：打开「日志取证」后，在 LSPosed 管理器 → 日志 里\n"
                + "能看到 QQ 实际发出的每个 Intent。");
        scope.setTextSize(12);
        scope.setTextColor(Color.GRAY);
        scope.setPadding(0, dp(16), 0, 0);
        root.addView(scope);

        ScrollView sv = new ScrollView(this);
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
