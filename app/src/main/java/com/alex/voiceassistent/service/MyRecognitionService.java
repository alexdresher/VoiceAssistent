package com.alex.voiceassistent;

import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.IOException;
import java.util.ArrayList;

public class MyRecognitionService extends RecognitionService implements RecognitionListener {

    private static final String TAG = "MyRecognitionService";
    private SpeechService speechService;
    private Callback currentCallback;

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        this.currentCallback = listener;

        VoskModelManager.getInstance().init(this, new VoskModelManager.OnInitListener() {
            @Override
            public void onReady(Model model) {
                startVoskPipeline(model);
            }

            @Override
            public void onError(Exception e) {
                notifyError(SpeechRecognizer.ERROR_SERVER);
            }
        });
    }

    private void startVoskPipeline(Model model) {
        try {
            Recognizer recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(this);

            if (currentCallback != null) {
                try {
                    // Оповещаем систему, что микрофон открыт и готов к записи
                    currentCallback.readyForSpeech(new Bundle());
                } catch (RemoteException e) {
                    Log.e(TAG, "Error notifying readyForSpeech", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to start Vosk recognizer", e);
            notifyError(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    @Override
    protected void onStopListening(Callback listener) {
        // Вызывается системой, когда пользователь отпустил кнопку или замолчал
        if (speechService != null) {
            speechService.stop();
        }
    }

    @Override
    protected void onCancel(Callback listener) {
        // Отмена запроса (например, диалог закрыли)
        cleanup();
    }

    private void cleanup() {
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
            speechService = null;
        }
        currentCallback = null;
    }

    private void notifyError(int errorCode) {
        if (currentCallback != null) {
            try {
                currentCallback.error(errorCode);
            } catch (RemoteException e) {
                Log.e(TAG, "Error dispatching recognition error", e);
            }
        }
        cleanup();
    }

    // ---------- Реализация Vosk RecognitionListener ----------

    @Override
    public void onPartialResult(String hypothesis) {
        if (currentCallback == null) return;
        String partial = extractField(hypothesis, "partial");
        if (partial != null && !partial.isEmpty()) {
            try {
                // Передаем промежуточный текст системе
                Bundle bundle = new Bundle();
                ArrayList<String> texts = new ArrayList<>();
                texts.add(partial);
                bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, texts);
                currentCallback.partialResults(bundle);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException onPartialResult", e);
            }
        }
    }

    @Override
    public void onResult(String hypothesis) {
        String text = extractField(hypothesis, "text");
        if (text != null && !text.isEmpty()) {
            // Выполняем вашу логику парсинга и записи в календарь
            executeCommand(text);

            // Передаем финальный распознанный текст системе/сессии
            if (currentCallback != null) {
                try {
                    Bundle bundle = new Bundle();
                    ArrayList<String> texts = new ArrayList<>();
                    texts.add(text);
                    bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, texts);
                    currentCallback.results(bundle);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException onResult", e);
                }
            }
        }
    }

    @Override
    public void onFinalResult(String hypothesis) {
        cleanup();
    }

    @Override
    public void onError(Exception exception) {
        Log.e(TAG, "Vosk error", exception);
        notifyError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
    }

    @Override
    public void onTimeout() {
        notifyError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
    }

    private void executeCommand(String text) {
        ReminderParser.ParseResult result = ReminderParser.parse(text);
        if (result != null) {
            CalendarHelper.addReminderEvent(this, result.taskText, result.totalMinutes);
        }
    }

    private String extractField(String json, String field) {
        try {
            JSONObject obj = new JSONObject(json);
            return obj.optString(field, "");
        } catch (JSONException e) {
            return null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanup();
    }
}