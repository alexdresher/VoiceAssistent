package com.alex.voiceassistent;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;

public class MainActivity extends AppCompatActivity implements RecognitionListener {

    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private static final long SILENCE_TIMEOUT_MS = 3000; // 3 секунды тишины

    private Model model;
    private SpeechService speechService;

    private TextView statusText;
    private TextView resultText;
    private Button startButton;

    private final Handler silenceHandler = new Handler(Looper.getMainLooper());
    private final Runnable silenceRunnable = this::stopListeningDueToSilence;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        resultText = findViewById(R.id.resultText);
        startButton = findViewById(R.id.startButton);

        startButton.setOnClickListener(v -> toggleListening());

        requestRequiredPermissions();
    }

    /*
    @Override
    protected void onResume() {
        super.onResume();

        ComponentName component = new ComponentName(this, com.alex.voiceassistent.service.MyVoiceInteractionService.class);
        boolean active = android.service.voice.VoiceInteractionService.isActiveService(this, component);

        if (!active) {
            // Если привязка упала, дергаем сервис для самовосстановления
            Intent serviceIntent = new Intent(this, com.alex.voiceassistent.service.MyVoiceInteractionService.class);
            startService(serviceIntent);
        }
    }
     */

    /**
     * Запрашивает все нужные приложению опасные разрешения одним диалогом:
     * микрофон (для Vosk) и календарь (для записи напоминаний).
     */
    private void requestRequiredPermissions() {
        List<String> missing = new ArrayList<>();
        String[] required = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.READ_CALENDAR
        };
        for (String permission : required) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }

        if (missing.isEmpty()) {
            initModel();
        } else {
            ActivityCompat.requestPermissions(this,
                    missing.toArray(new String[0]),
                    PERMISSIONS_REQUEST_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            boolean allGranted = grantResults.length > 0;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                initModel();
            } else {
                statusText.setText("Нужны разрешения на микрофон и календарь");
                Toast.makeText(this, "Без этих разрешений приложение работать не может", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Распаковывает модель из assets/model-ru в filesDir (один раз при первом запуске,
     * дальше StorageService сам проверяет — распаковывать заново не будет).
     */
    private void initModel() {
        statusText.setText("Распаковка модели...");
        VoskModelManager.getInstance().init(this, new VoskModelManager.OnInitListener() {
            @Override
            public void onReady(Model readyModel) {
                model = readyModel;
                runOnUiThread(() -> {
                    statusText.setText("Модель готова");
                    startButton.setEnabled(true);
                });
            }

            @Override
            public void onError(Exception exception) {
                runOnUiThread(() ->
                        statusText.setText("Ошибка загрузки модели: " + exception.getMessage())
                );
            }
        });
    }

    private void toggleListening() {
        if (speechService != null) {
            // Ручная остановка пользователем
            stopListening("Остановлено");
        } else {
            startListening();
        }
    }

    private void startListening() {
        if (model == null) {
            Toast.makeText(this, "Модель ещё не загружена", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Recognizer recognizer = new Recognizer(model, 16000.0f);

            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(this); // this = RecognitionListener (реализован ниже)

            //очищаем распознанный текст
            resultText.setText("");

            startButton.setText("⏹ Остановить");
            statusText.setText("Слушаю...");

            resetSilenceTimer();

        } catch (IOException e) {
            statusText.setText("Ошибка запуска распознавания: " + e.getMessage());
        }
    }

    /**
     * Сбрасывает таймер тишины и запускает заново — вызывается при любой голосовой активности
     * (partial или final результат).
     */
    private void resetSilenceTimer() {
        silenceHandler.removeCallbacks(silenceRunnable);
        silenceHandler.postDelayed(silenceRunnable, SILENCE_TIMEOUT_MS);
    }

    /**
     * Останавливает прослушивание по причине истечения таймера тишины (3 секунды без речи).
     */
    private void stopListeningDueToSilence() {
        if (speechService != null) {
            stopListening("Тишина 3 сек — остановлено");
        }
    }

    /**
     * Единая точка остановки прослушивания — используется и при ручной остановке,
     * и при срабатывании таймера тишины.
     */
    private void stopListening(String statusMessage) {
        silenceHandler.removeCallbacks(silenceRunnable);
        if (speechService != null) {
            speechService.stop();
            speechService = null;
        }
        startButton.setText("🎤 Начать слушать");
        statusText.setText(statusText.getText() + "\n" + statusMessage);
    }

    // ---------- RecognitionListener callbacks ----------

    @Override
    public void onPartialResult(String hypothesis) {
        // Приходит по мере произнесения фразы, до финального результата.
        // JSON вида: {"partial" : "напомни через"}
        String text = extractField(hypothesis, "partial");
        if (text != null && !text.isEmpty()) {
            runOnUiThread(() -> {
                resultText.setText(text + " …");
                resetSilenceTimer(); // есть речь — откладываем автоостановку ещё на 3 сек
            });
        }
    }

    @Override
    public void onResult(String hypothesis) {
        // Финальный результат по завершении фразы (пауза в речи).
        // JSON вида: {"text" : "напомни через полчаса купить хлеб"}
        String text = extractField(hypothesis, "text");
        if (text != null && !text.isEmpty()) {
            runOnUiThread(() -> {
                resultText.setText(text);
                resetSilenceTimer(); // тоже считается активностью — таймер начинается заново
                handleRecognizedCommand(text);
            });
        }
    }

    @Override
    public void onFinalResult(String hypothesis) {
        // Вызывается при остановке распознавания (speechService.stop())
    }

    @Override
    public void onError(Exception exception) {
        runOnUiThread(() -> statusText.setText("Ошибка распознавания: " + exception.getMessage()));
    }

    @Override
    public void onTimeout() {
        runOnUiThread(() -> statusText.setText("Таймаут — тишина слишком долго"));
    }

    /**
     * Вытаскивает поле из JSON-ответа Vosk без лишних библиотек.
     */
    private String extractField(String json, String field) {
        try {
            JSONObject obj = new JSONObject(json);
            return obj.optString(field, "");
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Разбирает распознанную фразу и, если она соответствует шаблону
     * "напомни через ... [текст]", создаёт событие в системном календаре.
     */
    private void handleRecognizedCommand(String text) {
        ReminderParser.ParseResult result = ReminderParser.parse(text);

        if (result == null) {
            statusText.setText("Не похоже на команду напоминания");
            return;
        }

        boolean success = CalendarHelper.addReminderEvent(this, result.taskText, result.totalMinutes);

        // Вычисляем время срабатывания
        long triggerTimeMillis = System.currentTimeMillis() + (result.totalMinutes * 60L * 1000L);

// Форматируем дату и время (например: "06.10 в 16:45")
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd.MM в HH:mm", java.util.Locale.getDefault());
        String formattedDateTime = sdf.format(new java.util.Date(triggerTimeMillis));

        if (success) {
            //statusText.setText("Добавлено: \"" + result.taskText + "\" через " + result.totalMinutes + " мин.");
            statusText.setText("Добавлено: \"" + result.taskText + "\" " + formattedDateTime);
        } else {
            statusText.setText("Ошибка записи в календарь");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        silenceHandler.removeCallbacks(silenceRunnable);
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }
        if (model != null) {
            model.close();
        }
    }
}