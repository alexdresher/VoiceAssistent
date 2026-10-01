package com.alex.voiceassistent;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.CalendarContract;
import android.widget.Toast;

public class TaskIntentHelper {

    // Системный экшен Google Actions для заметок и задач
    public static final String ACTION_CREATE_NOTE = "com.google.android.gms.actions.CREATE_NOTE";
    public static final String EXTRA_NAME = "com.google.android.gms.actions.extra.NAME";
    public static final String EXTRA_TEXT = "com.google.android.gms.actions.extra.TEXT";
    public static final String EXTRA_NOTE_TYPE = "com.google.android.gms.actions.extra.NOTE_TYPE";
    public static final String VALUE_NOTE_TYPE_LIST = "LIST"; // создает элемент с чекбоксом

    /**
     * Отправляет системный интент на создание задачи/заметки
     */
    public static boolean createNoteOrTask(Context context, String taskTitle) {
        Intent intent = new Intent(ACTION_CREATE_NOTE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Передаем заголовок и текст
        intent.putExtra(EXTRA_NAME, taskTitle);
        intent.putExtra(EXTRA_TEXT, taskTitle);

        // Указываем, что хотим именно элемент списка (чекбокс), а не обычный текст
        intent.putExtra(EXTRA_NOTE_TYPE, VALUE_NOTE_TYPE_LIST);

        // Проверяем, есть ли на устройстве приложение, способное обработать интент
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(intent);
            return true;
        }

        // Фоллбэк: если специфичный экшен Google не поддерживается, пробуем стандартный SEND
        Intent fallbackIntent = new Intent(Intent.ACTION_SEND);
        fallbackIntent.setType("text/plain");
        fallbackIntent.putExtra(Intent.EXTRA_TEXT, taskTitle);
        fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (fallbackIntent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(fallbackIntent);
            return true;
        }

        return false;
    }

    /**
     * Открывает системное окно создания с предустановленным заголовком и временем начала
     */
    public static boolean createTimedTask(Context context, String taskTitle, int delayMinutes) {
        long startMillis = System.currentTimeMillis() + (delayMinutes * 60L * 1000L);

        Intent intent = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, taskTitle)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                // Для точечной задачи ставим одинаковое время начала и конца
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis)
                .putExtra(CalendarContract.Events.DESCRIPTION, "Создано голосовым помощником")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (intent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(intent);
            return true;
        }

        return false;
    }
}