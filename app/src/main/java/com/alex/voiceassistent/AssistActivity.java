package com.alex.voiceassistent;

import android.app.Activity;
import android.os.Bundle;

public class AssistActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Здесь при необходимости можно открыть UI или сразу закрыть
        finish();
    }
}