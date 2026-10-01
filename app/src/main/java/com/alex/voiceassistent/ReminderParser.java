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
    private static final java.util.Map<String, Integer> NUMBERS = new java.util.HashMap<>();
    static {
        // Единицы и формы родов/падежей
        NUMBERS.put("ноль", 0);
        NUMBERS.put("один", 1);
        NUMBERS.put("одну", 1);
        NUMBERS.put("одна", 1);
        NUMBERS.put("два", 2);
        NUMBERS.put("две", 2);
        NUMBERS.put("три", 3);
        NUMBERS.put("четыре", 4);
        NUMBERS.put("пять", 5);
        NUMBERS.put("шесть", 6);
        NUMBERS.put("семь", 7);
        NUMBERS.put("восемь", 8);
        NUMBERS.put("девять", 9);

        // 11-19
        NUMBERS.put("одиннадцать", 11);
        NUMBERS.put("двенадцать", 12);
        NUMBERS.put("тринадцать", 13);
        NUMBERS.put("четырнадцать", 14);
        NUMBERS.put("пятнадцать", 15);
        NUMBERS.put("шестнадцать", 16);
        NUMBERS.put("семнадцать", 17);
        NUMBERS.put("восемнадцать", 18);
        NUMBERS.put("девятнадцать", 19);

        // Десятки
        NUMBERS.put("десять", 10);
        NUMBERS.put("двадцать", 20);
        NUMBERS.put("тридцать", 30);
        NUMBERS.put("сорок", 40);
        NUMBERS.put("пятьдесят", 50);
        NUMBERS.put("шестьдесят", 60);
        NUMBERS.put("семьдесят", 70);
        NUMBERS.put("восемьдесят", 80);
        NUMBERS.put("девяносто", 90);
    }

    // Флаги с поддержкой кириллицы (UNICODE_CHARACTER_CLASS + CASE_INSENSITIVE)
    // Оставляем ТОЛЬКО CASE_INSENSITIVE (Android не поддерживает UNICODE_CHARACTER_CLASS)
    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    private static final Pattern TRIGGER = Pattern.compile(
            "^\\s*напомни(ть)?\\s+(мне\\s+)?", FLAGS);

    // "через два с половиной часа"
    private static final Pattern WITH_HALF_PATTERN = Pattern.compile(
            "^через\\s+([а-яё\\d]+)\\s+с\\s+половиной\\s+(ч?ас(?:а|ов)?|минут[уы]?)(?:\\s+|$)", FLAGS);

    // "полтора часа/аса", "полчаса", "час", "часик"
    private static final Pattern QUICK_TIME = Pattern.compile(
            "^через\\s+(полтора\\s+ч?аса?|пол\\s*часа?|ч?ас(?:ик)?)(?:\\s+|$)", FLAGS);

    // Составные числа ("через двадцать пять минут", "через 15 минут")
    private static final Pattern THROUGH_BLOCK = Pattern.compile(
            "^через\\s+((?:(?:[а-яё\\d]+\\s+)+(?:минут[уы]?|ч?ас(?:а|ов)?)\\s*)+)", FLAGS);

    private static final Pattern NUMBER_UNIT = Pattern.compile(
            "((?:[а-яё\\d]+\\s*)+?)\\s+(минут[уы]?|ч?ас(?:а|ов)?)", FLAGS);


    /**
     * Пытается распознать фразу. Возвращает null, если фраза не подходит под шаблон
     * "напомни через ...".
     */
    public static ParseResult parse(String rawText) {
        if (rawText == null) return null;
        String text = rawText.trim().toLowerCase();

        Matcher triggerMatcher = TRIGGER.matcher(text);
        if (!triggerMatcher.find()) {
            return null;
        }
        String afterTrigger = text.substring(triggerMatcher.end()).trim();

        String remainderAfterTime;
        int totalMinutes = 0;

        // 1. Проверяем "X с половиной часа / минут" (например, "два с половиной часа")
        Matcher halfMatcher = WITH_HALF_PATTERN.matcher(afterTrigger);
        if (halfMatcher.find()) {
            String numStr = halfMatcher.group(1);
            String unit = halfMatcher.group(2);

            int baseNumber = parseNumber(numStr);
            if (baseNumber <= 0) return null;

            if (unit.startsWith("ч") || unit.startsWith("ас")) {
                // baseNumber часов + 30 минут (например, 2 * 60 + 30 = 150)
                totalMinutes = baseNumber * 60 + 30;
            } else {
                // минуты с половиной (округляем или берем baseNumber минут + 30 сек)
                totalMinutes = baseNumber;
            }

            remainderAfterTime = afterTrigger.substring(halfMatcher.end());

            // 2. Проверяем "полтора часа", "полчаса", "час"
        } else {
            Matcher quickMatcher = QUICK_TIME.matcher(afterTrigger);
            if (quickMatcher.find()) {
                String token = quickMatcher.group(1).replaceAll("\\s+", "");
                if (token.startsWith("полтора")) {
                    totalMinutes = 90;
                } else if (token.startsWith("пол")) {
                    totalMinutes = 30;
                } else {
                    totalMinutes = 60;
                }
                remainderAfterTime = afterTrigger.substring(quickMatcher.end());

                // 3. Стандартный блок чисел ("15 минут", "2 часа 20 минут")
            } else {
                Matcher throughMatcher = THROUGH_BLOCK.matcher(afterTrigger);
                if (!throughMatcher.find()) {
                    return null;
                }
                String timeBlock = throughMatcher.group(1);
                totalMinutes = parseTimeBlock(timeBlock);
                if (totalMinutes <= 0) {
                    return null;
                }
                remainderAfterTime = afterTrigger.substring(throughMatcher.end());
            }
        }

        String taskText = remainderAfterTime.trim();
        if (taskText.isEmpty()) {
            return null;
        }

        taskText = Character.toUpperCase(taskText.charAt(0)) + taskText.substring(1);
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
            String numberToken = m.group(1).trim(); // Будет: "двадцать пять"
            String unit = m.group(2);               // Будет: "минут"

            int number = parseNumber(numberToken);  // Сложит 20 + 5 = 25
            if (number <= 0) continue;

            if (unit.startsWith("час")) {
                totalMinutes += number * 60;
            } else {
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
        if (token == null) return -1;
        token = token.trim().toLowerCase();

        // 1. Если число записано цифрами (например, "25" или "5")
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            // Не цифры, продолжаем разбор словами
        }

        // 2. Разбиваем строку по пробелам на отдельные слова: "двадцать пять" -> ["двадцать", "пять"]
        String[] words = token.split("\\s+");
        int total = 0;
        boolean hasMatched = false;

        for (String word : words) {
            if (word.isEmpty()) continue;

            Integer val = NUMBERS.get(word);
            if (val != null) {
                total += val;
                hasMatched = true;
            } else {
                // Если попалось неизвестное слово внутри числа
                return -1;
            }
        }

        return hasMatched ? total : -1;
    }
}