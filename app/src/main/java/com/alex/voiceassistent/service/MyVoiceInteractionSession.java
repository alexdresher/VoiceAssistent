package com.alex.voiceassistent.service;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.alex.voiceassistent.CalendarHelper;
import com.alex.voiceassistent.R;
import com.alex.voiceassistent.ReminderParser;
import com.alex.voiceassistent.VoskModelManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.IOException;

public class MyVoiceInteractionSession extends VoiceInteractionSession implements RecognitionListener {

    private static final String TAG = "VoiceSession";
    private static final long SILENCE_TIMEOUT_MS = 3000;

    private TextView tvStatus;
    private TextView tvResult;
    private SpeechService speechService;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable silenceRunnable = this::stopAndDismiss;

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

        tvStatus.setText("Инициализация...");
        tvResult.setText("");

        // Получаем модель и сразу стартуем распознавание
        VoskModelManager.getInstance().init(getContext(), new VoskModelManager.OnInitListener() {
            @Override
            public void onReady(Model model) {
                mainHandler.post(() -> startListening(model));
            }

            @Override
            public void onError(Exception e) {
                mainHandler.post(() -> tvStatus.setText("Ошибка модели: " + e.getMessage()));
            }
        });
    }

    private void startListening(Model model) {
        try {
            Recognizer recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(this);

            tvStatus.setText("Слушаю...");
            resetSilenceTimer();
        } catch (IOException e) {
            Log.e(TAG, "Failed to start speech service", e);
            tvStatus.setText("Ошибка микрофона");
        }
    }

    private void resetSilenceTimer() {
        mainHandler.removeCallbacks(silenceRunnable);
        mainHandler.postDelayed(silenceRunnable, SILENCE_TIMEOUT_MS);
    }

    private void stopAndDismiss() {
        cleanup();
        // Закрываем оверлей ассистента с экрана
        hide();
    }

    private void cleanup() {
        mainHandler.removeCallbacks(silenceRunnable);
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
            speechService = null;
        }
    }

    @Override
    public void onHide() {
        super.onHide();
        cleanup();
    }

    // ---------- Vosk RecognitionListener ----------

    @Override
    public void onPartialResult(String hypothesis) {
        String partial = extractField(hypothesis, "partial");
        if (partial != null && !partial.isEmpty()) {
            mainHandler.post(() -> {
                tvResult.setText(partial + " …");
                resetSilenceTimer();
            });
        }
    }

    @Override
    public void onResult(String hypothesis) {
        String text = extractField(hypothesis, "text");
        if (text != null && !text.isEmpty()) {
            mainHandler.post(() -> {
                tvResult.setText(text);
                tvStatus.setText("Обработка...");

                // Разбираем напоминание
                ReminderParser.ParseResult result = ReminderParser.parse(text);
                if (result != null) {
                    boolean ok = CalendarHelper.addReminderEvent(getContext(), result.taskText, result.totalMinutes);
                    tvStatus.setText(ok ? "Добавлено в календарь!" : "Ошибка календаря");
                } else {
                    tvStatus.setText("Команда не распознана");
                }

                // Закрываем окно ассистента через 1.5 секунды после завершения
                mainHandler.postDelayed(this::stopAndDismiss, 1500);
            });
        }
    }

    @Override
    public void onFinalResult(String hypothesis) {}

    @Override
    public void onError(Exception exception) {
        mainHandler.post(() -> tvStatus.setText("Ошибка: " + exception.getMessage()));
    }

    @Override
    public void onTimeout() {
        mainHandler.post(this::stopAndDismiss);
    }

    private String extractField(String json, String field) {
        try {
            JSONObject obj = new JSONObject(json);
            return obj.optString(field, "");
        } catch (JSONException e) {
            return null;
        }
    }
}