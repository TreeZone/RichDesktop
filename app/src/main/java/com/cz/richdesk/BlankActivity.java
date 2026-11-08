package com.cz.richdesk;

import android.app.Activity;
import android.os.Bundle;

/**
 * 关闭画中画时在虚拟屏上短暂打开：
 * 用 CLEAR_TASK 把目标应用顶出虚拟屏，避免画面残影，随即自己 finish。
 */
public class BlankActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
