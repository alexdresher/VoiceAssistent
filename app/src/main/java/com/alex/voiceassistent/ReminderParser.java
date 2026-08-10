package com.alex.voiceassistent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбирает фразы вида:
 *   "напомни через 30 минут купить хлеб"
 *   "напомни через полчаса позвонить маме"
 *   "напомни через час встреча с клиентом"
 *   "напомни через 2 часа 15 минут забрать посылку"
 *
 * Первый этап: поддерживаем только относительные интервалы ("через ...").
 * Абсолютное время ("в 15:00", "завтра") — следующий шаг.
 */
public class ReminderParser {

    /**
     * Результат разбора: сколько минут добавить к текущему времени и текст задачи.
     */
    public static class ParseResult {
        public final int totalMinutes;
        public final String taskText;

        public ParseResult(int totalMinutes, String taskText) {
            this.totalMinutes = totalMinutes;
            this.taskText = taskText;
        }
    }

    // Числительные словами — только то, что реально встречается в бытовой речи
    private static final java.util.Map<String, Integer> WORD_NUMBERS = new java.util.HashMap<>();
    static {
        WORD_NUMBERS.put("один", 1);
        WORD_NUMBERS.put("одну", 1);
        WORD_NUMBERS.put("два", 2);
        WORD_NUMBERS.put("две", 2);
        WORD_NUMBERS.put("три", 3);
        WORD_NUMBERS.put("четыре", 4);
        WORD_NUMBERS.put("пять", 5);
        WORD_NUMBERS.put("шесть", 6);
        WORD_NUMBERS.put("семь", 7);
        WORD_NUMBERS.put("восемь", 8);
        WORD_NUMBERS.put("девять", 9);
        WORD_NUMBERS.put("десять", 10);
    }

    // "напомни" / "напомнить" в начале фразы — с необязательным "мне"
    private static final Pattern TRIGGER = Pattern.compile(
            "^\\s*напомни(ть)?\\s+(мне\\s+)?", Pattern.CASE_INSENSITIVE);

    // "через ЧИСЛО unit [ЧИСЛО unit ...]"
    // unit: минут(а/ы/у) | час(а/ов/)
    private static final Pattern THROUGH_BLOCK = Pattern.compile(
            "через\\s+((?:[а-яё\\d]+\\s+(?:минут[уы]?|час(?:а|ов)?)\\s*)+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NUMBER_UNIT = Pattern.compile(
            "([а-яё\\d]+)\\s+(минут[уы]?|час(?:а|ов)?)", Pattern.CASE_INSENSITIVE);

    /**
     * Пытается распознать фразу. Возвращает null, если фраза не подходит под шаблон
     * "напомни через ...".
     */
    public static ParseResult parse(String rawText) {
        if (rawText == null) return null;
        String text = rawText.trim().toLowerCase();

        Matcher triggerMatcher = TRIGGER.matcher(text);
        if (!triggerMatcher.find()) {
            return null; // фраза не начинается с "напомни"
        }
        String afterTrigger = text.substring(triggerMatcher.end());

        // Особый случай: "через полчаса" / "через пол часа"
        int minutesFromHalfHour = -1;
        Pattern halfHour = Pattern.compile("через\\s+пол\\s*часа?", Pattern.CASE_INSENSITIVE);
        Matcher halfHourMatcher = halfHour.matcher(afterTrigger);
        String remainderAfterTime;
        int totalMinutes;

        if (halfHourMatcher.find() && halfHourMatcher.start() == 0) {
            totalMinutes = 30;
            remainderAfterTime = afterTrigger.substring(halfHourMatcher.end());
        } else {
            Matcher throughMatcher = THROUGH_BLOCK.matcher(afterTrigger);
            if (!throughMatcher.find() || throughMatcher.start() != 0) {
                return null; // нет блока "через ..." сразу после триггера
            }
            String timeBlock = throughMatcher.group(1);
            totalMinutes = parseTimeBlock(timeBlock);
            if (totalMinutes <= 0) {
                return null;
            }
            remainderAfterTime = afterTrigger.substring(throughMatcher.end());
        }

        String taskText = remainderAfterTime.trim();
        if (taskText.isEmpty()) {
            taskText = "Напоминание"; // fallback, если тело задачи не распознано
        } else {
            // Делаем первую букву заглавной для красоты заголовка события
            taskText = Character.toUpperCase(taskText.charAt(0)) + taskText.substring(1);
        }

        return new ParseResult(totalMinutes, taskText);
    }

    /**
     * Парсит блок вида "2 часа 15 минут" / "полтора часа" / "тридцать минут"
     * в суммарное количество минут.
     */
    private static int parseTimeBlock(String block) {
        int totalMinutes = 0;
        Matcher m = NUMBER_UNIT.matcher(block);
        while (m.find()) {
            String numberToken = m.group(1);
            String unit = m.group(2);

            int number = parseNumber(numberToken);
            if (number <= 0) continue;

            if (unit.startsWith("час")) {
                totalMinutes += number * 60;
            } else { // минут(а/ы/у)
                totalMinutes += number;
            }
        }
        return totalMinutes;
    }

    /**
     * Число цифрами ("30") или словом ("тридцать" — только для базовых чисел 1-10,
     * этого достаточно для реалистичных фраз-напоминаний).
     */
    private static int parseNumber(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            Integer word = WORD_NUMBERS.get(token);
            return word != null ? word : -1;
        }
    }
}