package com.alex.voiceassistent.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
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

import java.io.IOException;

public class MyVoiceInteractionSession extends VoiceInteractionSession {

    private static final String TAG = "VoiceSession";
    private static final int SAMPLE_RATE = 16000;
    private static final long SILENCE_TIMEOUT_MS = 3000L;

    private TextView tvStatus;
    private TextView tvResult;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler silenceHandler = new Handler(Looper.getMainLooper());
    private final Runnable silenceRunnable = this::stopListeningDueToSilence;

    private AudioRecord audioRecord;
    private Recognizer recognizer;
    private Thread recordingThread;
    private volatile boolean isRecording = false;
    private boolean isProcessingCommand = false;

    private AudioFocusRequest audioFocusRequest;

    public MyVoiceInteractionSession(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Включаем полноправное системное окно
        setUiEnabled(true);
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
        Log.i(TAG, "onShow: открытие сессии");

        // КРИТИЧЕСКИЙ МОМЕНТ 1: Заставляем окно захватить системный фокус
        if (getWindow() != null) {
            getWindow().show();
        }

        silenceHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        isProcessingCommand = false;

        if (tvStatus != null) tvStatus.setText("Запуск...");
        if (tvResult != null) tvResult.setText("");

        // КРИТИЧЕСКИЙ МОМЕНТ 2: Даем ОС 250 мс подключить аудиомаршрут к нашему окну
        mainHandler.postDelayed(this::initModelAndStart, 250L);
    }

    @Override
    public void onHide() {
        super.onHide();
        Log.i(TAG, "onHide: скрытие шторки");
        stopAndFinish("Свернуто");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        stopListeningAndClean("Уничтожено");
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }

    private void initModelAndStart() {
        Model model = VoskModelManager.getInstance().getModel();
        if (model != null) {
            startListening(model);
        } else {
            if (tvStatus != null) tvStatus.setText("Загрузка модели...");
            VoskModelManager.getInstance().init(getContext(), new VoskModelManager.OnInitListener() {
                @Override
                public void onReady(Model readyModel) {
                    mainHandler.post(() -> startListening(readyModel));
                }

                @Override
                public void onError(Exception exception) {
                    mainHandler.post(() -> {
                        if (tvStatus != null) tvStatus.setText("Ошибка модели: " + exception.getMessage());
                    });
                }
            });
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void startListening(Model model) {
        stopRecordingInternal();

        // 1. Запрашиваем аудиофокус с наивысшим голосовым приоритетом
        requestSystemAudioFocus();

        int minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int bufferSize = Math.max(minBufferSize, SAMPLE_RATE * 2);

        try {
            recognizer = new Recognizer(model, (float) SAMPLE_RATE);

            // 2. Для VoiceInteractionSession легальным источником является VOICE_RECOGNITION
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                if (tvStatus != null) tvStatus.setText("Ошибка: AudioRecord не готов");
                abandonSystemAudioFocus();
                return;
            }

            audioRecord.startRecording();

            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                if (tvStatus != null) tvStatus.setText("Ошибка: Запись блокирована ОС");
                abandonSystemAudioFocus();
                return;
            }

            isRecording = true;
            if (tvStatus != null) tvStatus.setText("Слушаю...");
            resetSilenceTimer();

            recordingThread = new Thread(this::readAudioLoop, "AudioRecordThread");
            recordingThread.start();

        } catch (IOException e) {
            Log.e(TAG, "Ошибка Recognizer", e);
            if (tvStatus != null) tvStatus.setText("Ошибка: " + e.getMessage());
            abandonSystemAudioFocus();
        } catch (Exception e) {
            Log.e(TAG, "Ошибка AudioRecord", e);
            if (tvStatus != null) tvStatus.setText("Ошибка аудио");
            abandonSystemAudioFocus();
        }
    }

    private void readAudioLoop() {
        short[] buffer = new short[4096];
        long samplesChecked = 0;
        boolean audioActivityDetected = false;

        while (isRecording && audioRecord != null) {
            int nread = audioRecord.read(buffer, 0, buffer.length);

            if (nread > 0) {
                // Проверка на живой звук в первые секунды
                if (!audioActivityDetected && samplesChecked < 32000) {
                    for (int i = 0; i < nread; i++) {
                        if (Math.abs(buffer[i]) > 100) {
                            audioActivityDetected = true;
                            break;
                        }
                    }
                    samplesChecked += nread;
                    if (samplesChecked >= 32000 && !audioActivityDetected) {
                        mainHandler.post(() -> {
                            if (tvStatus != null && !isProcessingCommand) {
                                tvStatus.setText("Микрофон заглушен системой");
                            }
                        });
                    }
                }

                if (recognizer != null) {
                    if (recognizer.acceptWaveForm(buffer, nread)) {
                        handleVoskResult(recognizer.getResult());
                    } else {
                        handleVoskPartial(recognizer.getPartialResult());
                    }
                }
            } else if (nread < 0) {
                Log.e(TAG, "Ошибка read: " + nread);
                break;
            }
        }
    }

    private void handleVoskPartial(String json) {
        if (isProcessingCommand) return;
        String text = extractField(json, "partial");
        if (!text.isEmpty()) {
            mainHandler.post(() -> {
                if (tvResult != null) tvResult.setText(text + " …");
                resetSilenceTimer();
            });
        }
    }

    private void handleVoskResult(String json) {
        if (isProcessingCommand) return;
        String text = extractField(json, "text");
        if (!text.isEmpty()) {
            isProcessingCommand = true;
            silenceHandler.removeCallbacksAndMessages(null);

            mainHandler.post(() -> {
                if (tvResult != null) tvResult.setText(text);
                handleRecognizedCommand(text);
            });
        }
    }

    private synchronized void stopRecordingInternal() {
        isRecording = false;

        if (recordingThread != null) {
            try {
                recordingThread.join(250);
            } catch (InterruptedException ignored) {}
            recordingThread = null;
        }

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "Ошибка AudioRecord release", e);
            }
            audioRecord = null;
        }
    }

    private void stopListeningDueToSilence() {
        stopAndFinish("Время вышло");
    }

    private void stopAndFinish(String statusMessage) {
        silenceHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);

        if (tvStatus != null) {
            tvStatus.setText(statusMessage);
        }

        stopRecordingInternal();
        abandonSystemAudioFocus();

        // КРИТИЧЕСКИЙ МОМЕНТ 3: Используем именно finish(), чтобы следующий клик кнопки
        // вызвал чистый onNewSession() в сервисе!
        mainHandler.postDelayed(this::finish, 300L);
    }

    private void stopListeningAndClean(String statusMessage) {
        silenceHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);

        if (tvStatus != null) {
            tvStatus.setText(statusMessage);
        }

        stopRecordingInternal();
        abandonSystemAudioFocus();
    }

    private void resetSilenceTimer() {
        silenceHandler.removeCallbacks(silenceRunnable);
        silenceHandler.postDelayed(silenceRunnable, SILENCE_TIMEOUT_MS);
    }

    private void requestSystemAudioFocus() {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .build();
            am.requestAudioFocus(audioFocusRequest);
        } else {
            am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
        }
    }

    private void abandonSystemAudioFocus() {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            am.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            am.abandonAudioFocus(null);
        }
    }

    private void handleRecognizedCommand(String text) {
        ReminderParser.ParseResult result = ReminderParser.parse(text);

        if (result == null) {
            if (tvStatus != null) tvStatus.setText("Не похоже на команду");
            stopAndFinish("Не распознано");
            return;
        }

        if (tvStatus != null) tvStatus.setText("Создаю событие...");
        stopRecordingInternal();

        boolean success = CalendarHelper.addReminderEvent(getContext(), result.taskText, result.totalMinutes);

        if (success) {
            if (tvStatus != null) {
                tvStatus.setText("Добавлено: \"" + result.taskText + "\" через " + result.totalMinutes + " мин.");
            }
            mainHandler.postDelayed(this::finish, 1400L);
        } else {
            if (tvStatus != null) {
                tvStatus.setText("Ошибка записи в календарь");
            }
            mainHandler.postDelayed(this::finish, 2000L);
        }
    }

    private String extractField(String json, String field) {
        try {
            JSONObject obj = new JSONObject(json);
            return obj.optString(field, "").trim();
        } catch (JSONException e) {
            return "";
        }
    }
}