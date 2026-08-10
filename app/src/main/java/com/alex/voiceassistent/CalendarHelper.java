package com.alex.voiceassistent;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.util.Log;

import java.util.Calendar;
import java.util.TimeZone;

public class CalendarHelper {

    private static final String TAG = "CalendarHelper";

    /**
     * Создаёт событие в системном календаре: начало = сейчас + minutesFromNow,
     * длительность события — 15 минут (чисто для видимости в календаре, само
     * по себе значение не критично для напоминания).
     *
     * @return true, если событие успешно создано
     */
    public static boolean addReminderEvent(Context context, String title, int minutesFromNow) {
        long calendarId = getCalendarId(context);
        if (calendarId == -1) {
            Log.e(TAG, "Не найден ни один календарь на устройстве");
            return false;
        }

        Calendar startTime = Calendar.getInstance();
        startTime.add(Calendar.MINUTE, minutesFromNow);

        Calendar endTime = (Calendar) startTime.clone();
        endTime.add(Calendar.MINUTE, 15);

        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, title);
        values.put(CalendarContract.Events.DTSTART, startTime.getTimeInMillis());
        values.put(CalendarContract.Events.DTEND, endTime.getTimeInMillis());
        values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());

        // Напоминание за 0 минут до начала (то есть точно в момент старта)
        // добавим отдельной вставкой в CalendarContract.Reminders после создания события

        ContentResolver resolver = context.getContentResolver();
        Uri eventUriString = Uri.parse("content://com.android.calendar/events");
        Uri resultUri = resolver.insert(eventUriString, values);

        if (resultUri == null) {
            Log.e(TAG, "Не удалось создать событие — insert вернул null");
            return false;
        }

        long eventId = Long.parseLong(resultUri.getLastPathSegment());
        addReminderAlert(context, eventId);

        Log.i(TAG, "Событие создано: id=" + eventId + ", title=" + title
                + ", start=" + startTime.getTime());
        return true;
    }

    /**
     * Добавляет push-напоминание (алерт) на момент начала события,
     * чтобы сработало системное уведомление календаря, а не просто запись без звука.
     */
    private static void addReminderAlert(Context context, long eventId) {
        ContentValues reminderValues = new ContentValues();
        reminderValues.put(CalendarContract.Reminders.EVENT_ID, eventId);
        reminderValues.put(CalendarContract.Reminders.MINUTES, 0); // напомнить точно в момент начала
        reminderValues.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);

        context.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, reminderValues);
    }

    /**
     * Находит ID основного (первого доступного) календаря на устройстве —
     * обычно это основной Google-аккаунт, если он есть.
     */
    private static long getPrimaryCalendarId(Context context) {
        String[] projection = {CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL};

        Cursor cursor = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null);

        long fallbackId = -1;
        long primaryId = -1;

        if (cursor != null) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                int isPrimary = cursor.getInt(1);
                int accessLevel = cursor.getInt(2);

                // Нужен календарь с правом записи
                if (accessLevel < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    continue;
                }
                if (fallbackId == -1) {
                    fallbackId = id;
                }
                if (isPrimary != 0) {
                    primaryId = id;
                }
            }
            cursor.close();
        }

        return primaryId != -1 ? primaryId : fallbackId;
    }

    private static final String TARGET_ACCOUNT_NAME = "advnoob@gmail.com";
    private static long getCalendarId(Context context) {
        String[] projection = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        };

        String selection = CalendarContract.Calendars.ACCOUNT_NAME + " = ?";
        String[] selectionArgs = {TARGET_ACCOUNT_NAME};

        Cursor cursor = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
        );

        while (cursor != null && cursor.moveToNext()) {
            String calendarName=cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME));
            if (calendarName.equals("advnoob@gmail.com")) {
                long calendarId = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID));
                cursor.close();
                return calendarId;
            }
        }

        if (cursor != null) cursor.close();
        return -1;
    }
}