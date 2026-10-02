package com.alex.voiceassistent.service;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.alex.voiceassistent.R;
import com.alex.voiceassistent.ReminderParser;
import com.alex.voiceassistent.TaskIntentHelper;
import com.alex.voiceassistent.VoskModelManager;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

public class MyVoiceInteractionSession extends VoiceInteractionSession implements RecognitionListener {

    private static final String TAG = "VoiceSession";
    private static final long SILENCE_TIMEOUT_MS = 3000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SpeechService speechService;
    private Recognizer recognizer;

    private TextView tvStatus;
    private TextView tvResult;
    private boolean isProcessingCommand = false;

    private final Runnable silenceTimeoutRunnable = () -> {
        Log.d(TAG, "Silence timeout - hiding session");
        if (tvStatus != null) {
            tvStatus.setText("Время ожидания истекло");
        }
        mainHandler.postDelayed(this::safeHide, 400);
    };

    public MyVoiceInteractionSession(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        View view = getLayoutInflater().inflate(R.layout.assistant_window, null);
        tvStatus = view.findViewById(R.id.tvAssistantStatus);
        tvResult = view.findViewById(R.id.tvAssistantResult);
        return view;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        Log.d(TAG, "onShow called");

        mainHandler.removeCallbacksAndMessages(null);
        isProcessingCommand = false;

        if (tvStatus != null) tvStatus.setText("Слушаю вас...");
        if (tvResult != null) tvResult.setText("");

        // Даем 200 мс форы аудиосистеме Android на полное освобождение микрофона,
        // затем запускаем распознавание без блокировки главного потока
        mainHandler.postDelayed(this::startRecognitionAsync, 200L);

        resetSilenceTimer(4000L);
    }

    @Override
    public void onHide() {
        super.onHide();
        Log.d(TAG, "onHide called");
        stopAndReleaseVosk(); // Полностью гасим микрофон и освобождаем железо
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy called");
        mainHandler.removeCallbacksAndMessages(null);
        stopAndReleaseVosk();
    }

    // ----------------------------------------------------
    // Управление потоком и микрофоном
    // ----------------------------------------------------

    private void startRecognitionAsync() {
        stopAndReleaseVosk(); // Гасим старый хвост, если остался

        Model model = VoskModelManager.getInstance().getModel();
        if (model == null) {
            Log.e(TAG, "Model is null in VoskModelManager");
            if (tvStatus != null) tvStatus.setText("Модель не загружена");
            return;
        }

        try {
            recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(this);
            Log.d(TAG, "Vosk microphone started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start SpeechService", e);
            if (tvStatus != null) tvStatus.setText("Ошибка микрофона");
        }
    }

    private void stopAndReleaseVosk() {
        mainHandler.removeCallbacksAndMessages(silenceTimeoutRunnable);
        if (speechService != null) {
            try {
                speechService.stop();
                speechService.shutdown();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping SpeechService", e);
            }
            speechService = null;
        }
        recognizer = null;
        Log.d(TAG, "Vosk stopped and microphone released");
    }

    private void safeHide() {
        isProcessingCommand = false;
        stopAndReleaseVosk();
        hide(); // Скрываем шторку, микрофон полностью выключен на уровне ОС
    }

    private void resetSilenceTimer(long timeoutMs) {
        mainHandler.removeCallbacks(silenceTimeoutRunnable);
        mainHandler.postDelayed(silenceTimeoutRunnable, timeoutMs);
    }

    // ----------------------------------------------------
    // RecognitionListener Callbacks
    // ----------------------------------------------------

    @Override
    public void onPartialResult(String hypothesis) {
        if (isProcessingCommand) return;

        String partial = extractField(hypothesis, "partial");
        if (!partial.isEmpty()) {
            mainHandler.post(() -> {
                if (tvResult != null) tvResult.setText(partial);
                resetSilenceTimer(SILENCE_TIMEOUT_MS);
            });
        }
    }

    @Override
    public void onResult(String hypothesis) {
        if (isProcessingCommand) return;

        String text = extractField(hypothesis, "text");
        Log.d(TAG, "onResult: " + text);

        if (!text.isEmpty()) {
            isProcessingCommand = true;
            mainHandler.removeCallbacks(silenceTimeoutRunnable);

            // Сразу гасим микрофон при успешном распознавании команды
            stopAndReleaseVosk();

            mainHandler.post(() -> {
                if (tvResult != null) tvResult.setText(text);
                processCommand(text);
            });
        }
    }

    @Override
    public void onFinalResult(String hypothesis) {}

    @Override
    public void onError(Exception exception) {
        Log.e(TAG, "Vosk error", exception);
        mainHandler.post(this::safeHide);
    }

    @Override
    public void onTimeout() {
        mainHandler.post(this::safeHide);
    }

    private void processCommand(String text) {
        ReminderParser.ParseResult result = ReminderParser.parse(text);

        if (result != null) {
            TaskIntentHelper.createTimedTask(getContext(), result.taskText, result.totalMinutes);
            if (tvStatus != null) tvStatus.setText("Напоминание создано!");
            mainHandler.postDelayed(this::safeHide, 1200);
        } else {
            if (tvStatus != null) tvStatus.setText("Команда не распознана");
            mainHandler.postDelayed(this::safeHide, 1500);
        }
    }

    private String extractField(String json, String field) {
        try {
            JSONObject obj = new JSONObject(json);
            return obj.optString(field, "").trim();
        } catch (Exception e) {
            return "";
        }
    }
}