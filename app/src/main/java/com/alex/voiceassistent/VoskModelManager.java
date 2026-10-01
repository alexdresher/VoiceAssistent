package com.alex.voiceassistent;

import android.content.Context;
import org.vosk.Model;
import org.vosk.android.StorageService;

public class VoskModelManager {
    private static VoskModelManager instance;
    private Model model;
    private boolean isInitializing = false;

    public interface OnInitListener {
        void onReady(Model model);
        void onError(Exception e);
    }

    private VoskModelManager() {}

    public static synchronized VoskModelManager getInstance() {
        if (instance == null) {
            instance = new VoskModelManager();
        }
        return instance;
    }

    public synchronized void init(Context context, OnInitListener listener) {
        if (model != null) {
            listener.onReady(model);
            return;
        }
        if (isInitializing) {
            return;
        }
        isInitializing = true;
        Context appCtx = context.getApplicationContext();
        StorageService.unpack(appCtx, "model-ru", "model",
                unpackedModel -> {
                    model = unpackedModel;
                    isInitializing = false;
                    listener.onReady(model);
                },
                exception -> {
                    isInitializing = false;
                    listener.onError(exception);
                });
    }

    public Model getModel() {
        return model;
    }
}