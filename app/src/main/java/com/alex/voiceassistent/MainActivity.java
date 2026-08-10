package com.alex.voiceassistent;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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

    private static final long SILENCE_TIMEOUT_MS = 2000; // 3 секунды тишины

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

        // Запрашиваем разрешение на микрофон перед инициализацией модели
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initModel();
            } else {
                statusText.setText("Нет разрешения на микрофон");
                Toast.makeText(this, "Без доступа к микрофону приложение работать не может", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Распаковывает модель из assets/model-ru в filesDir (один раз при первом запуске,
     * дальше StorageService сам проверяет — распаковывать заново не будет).
     */
    private void initModel() {
        statusText.setText("Распаковка модели...");
        StorageService.unpack(this, "model-ru", "model",
                (unpackedModel) -> {
                    model = unpackedModel;
                    statusText.setText("Модель готова");
                    startButton.setEnabled(true);
                },
                (exception) -> {
                    statusText.setText("Ошибка загрузки модели: " + exception.getMessage());
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
            stopListening("Тишина 2 сек — остановлено");
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
        statusText.setText(statusMessage);
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
     * Точка, куда дальше подключится regex-парсер даты/времени и запись в CalendarContract.
     */
    private void handleRecognizedCommand(String text) {
        // TODO: сюда воткнём DateTimeParser + CalendarWriter на следующем шаге
        statusText.setText("Распознано: готово к парсингу");
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