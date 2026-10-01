package com.alex.voiceassistent.service;

import android.service.voice.VoiceInteractionService;

public class MyVoiceInteractionService extends VoiceInteractionService {
    @Override
    public void onReady() {
        super.onReady();
        // Сервис инициализирован и активен как системный ассистент
    }
}
