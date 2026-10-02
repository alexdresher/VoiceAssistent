package com.alex.voiceassistent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Calendar;

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

    private static final java.util.Map<String, Integer> DAYS_OF_WEEK = new java.util.HashMap<>();
    static {
        DAYS_OF_WEEK.put("понедельник", Calendar.MONDAY);
        DAYS_OF_WEEK.put("вторник", Calendar.TUESDAY);
        DAYS_OF_WEEK.put("среду", Calendar.WEDNESDAY);
        DAYS_OF_WEEK.put("среда", Calendar.WEDNESDAY);
        DAYS_OF_WEEK.put("четверг", Calendar.THURSDAY);
        DAYS_OF_WEEK.put("пятницу", Calendar.FRIDAY);
        DAYS_OF_WEEK.put("пятница", Calendar.FRIDAY);
        DAYS_OF_WEEK.put("субботу", Calendar.SATURDAY);
        DAYS_OF_WEEK.put("суббота", Calendar.SATURDAY);
        DAYS_OF_WEEK.put("воскресенье", Calendar.SUNDAY);
    }



    private static class DateOffsetResult {
        final int daysToAdd;         // сколько дней прибавить (0, 1, 2, ...)
        final Integer exactDayOfWeek; // если указан день недели (Calendar.MONDAY и т.д.), иначе null
        final boolean isNextWeek;     // было ли слово "следующий"
        final String remainder;       // строка после удаления блока даты

        DateOffsetResult(int daysToAdd, Integer exactDayOfWeek, boolean isNextWeek, String remainder) {
            this.daysToAdd = daysToAdd;
            this.exactDayOfWeek = exactDayOfWeek;
            this.isNextWeek = isNextWeek;
            this.remainder = remainder;
        }
    }

    /**
     * Ищет конструкции: "завтра", "послезавтра", "после завтра",
     * "в следующий вторник", "в эту пятницу", "во вторник"
     */
    private static DateOffsetResult extractDateOffset(String text) {
        text = text.trim();

        // 1. "послезавтра" / "после завтра"
        Pattern afterTomorrow = Pattern.compile("^после\\s*завтра(?:\\s+|$)", FLAGS);
        Matcher m = afterTomorrow.matcher(text);
        if (m.find()) {
            return new DateOffsetResult(2, null, false, text.substring(m.end()).trim());
        }

        // 2. "завтра"
        Pattern tomorrow = Pattern.compile("^завтра(?:\\s+|$)", FLAGS);
        m = tomorrow.matcher(text);
        if (m.find()) {
            return new DateOffsetResult(1, null, false, text.substring(m.end()).trim());
        }

        // 3. "сегодня"
        Pattern today = Pattern.compile("^сегодня(?:\\s+|$)", FLAGS);
        m = today.matcher(text);
        if (m.find()) {
            return new DateOffsetResult(0, null, false, text.substring(m.end()).trim());
        }

        // 4. Дни недели: "(в/во) (следующий/эту/этот)? (понедельник|вторник|...)"
        Pattern dowPattern = Pattern.compile(
                "^(?:в[о]?\\s+)?(?:(следующ(?:ий|ую|ее)|эт(?:от|у|о))\\s+)?([а-яё]+)(?:\\s+|$)", FLAGS);
        m = dowPattern.matcher(text);
        if (m.find()) {
            String modifier = m.group(1);
            String dayWord = m.group(2);

            if (dayWord != null && DAYS_OF_WEEK.containsKey(dayWord)) {
                int targetDow = DAYS_OF_WEEK.get(dayWord);
                boolean nextWeek = modifier != null && modifier.startsWith("следующ");
                return new DateOffsetResult(0, targetDow, nextWeek, text.substring(m.end()).trim());
            }
        }

        // Дата не указана явно — оставляем как есть
        return new DateOffsetResult(0, null, false, text);
    }


    // Флаги с поддержкой кириллицы (UNICODE_CHARACTER_CLASS + CASE_INSENSITIVE)
    // Оставляем ТОЛЬКО CASE_INSENSITIVE (Android не поддерживает UNICODE_CHARACTER_CLASS)
    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    private static final Pattern TRIGGER = Pattern.compile(
            "^\\s*напомн[а-яё]*(?:\\s+|$)(?:мне(?:\\s+|$))?", FLAGS);

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


    // 1. Формат "в 13:45", "в 14.00"
    private static final Pattern AT_DIGIT_TIME = Pattern.compile(
            "^в[о]?\\s+(\\d{1,2})[:.](\\d{2})\\b\\s*", FLAGS);

    // 2. Формат словами: "в тринадцать сорок пять", "в два часа", "в пять вечера"
// Захватывает слова после "в" до текста задачи
    private static final Pattern AT_WORD_TIME = Pattern.compile(
            "^в[о]?\\s+([а-яё\\d\\s]+?)(?:\\s+(?:часов|часа|час))?\\s+(.+)$", FLAGS);



    private static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        for (char c : str.toCharArray()) {
            if (!Character.isDigit(c)) return false;
        }
        return true;
    }

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

        // ------------------------------------------------------------------
        // 1. Проверяем наличие блока даты ("завтра", "послезавтра", "во вторник")
        // ------------------------------------------------------------------
        DateOffsetResult dateInfo = extractDateOffset(afterTrigger);
        String afterDate = dateInfo.remainder;

        // ------------------------------------------------------------------
        // 2. ВЕТКА ТОЧНОГО ВРЕМЕНИ: "в 13:45" или "в тринадцать сорок пять"
        // ------------------------------------------------------------------
        if (afterDate.startsWith("в ") || afterDate.startsWith("во ")) {

            // 2.1 Цифровое время ("в 13:45 выйти на прогулку")
            Matcher digitMatcher = AT_DIGIT_TIME.matcher(afterDate);
            if (digitMatcher.find()) {
                int h = Integer.parseInt(digitMatcher.group(1));
                int m = Integer.parseInt(digitMatcher.group(2));
                int delay = calculateMinutesUntil(h, m, dateInfo);
                if (delay > 0) {
                    String task = afterDate.substring(digitMatcher.end()).trim();
                    return buildResult(delay, task);
                }
            }

            // 2.2 Словесное время ("в тринадцать сорок пять выйти на прогулку")
            String afterPrep = afterDate.replaceFirst("^в[о]?\\s+", "").trim();
            String[] words = afterPrep.split("\\s+");

            StringBuilder timeWordsBuilder = new StringBuilder();
            int splitIndex = 0;

            for (int i = 0; i < words.length; i++) {
                String w = words[i];
                if (NUMBERS.containsKey(w) || isNumeric(w)) {
                    timeWordsBuilder.append(w).append(" ");
                    splitIndex = i + 1;
                } else if (w.matches("ч?ас(?:а|ов)?|минут[уы]?")) {
                    splitIndex = i + 1;
                } else {
                    break;
                }
            }

            if (splitIndex > 0) {
                String timeWords = timeWordsBuilder.toString().trim();
                int[] time = parseHourAndMinuteWords(timeWords);

                if (time != null) {
                    int delay = calculateMinutesUntil(time[0], time[1], dateInfo);
                    if (delay > 0) {
                        StringBuilder taskBuilder = new StringBuilder();
                        for (int i = splitIndex; i < words.length; i++) {
                            taskBuilder.append(words[i]).append(" ");
                        }
                        return buildResult(delay, taskBuilder.toString().trim());
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // 3. ВЕТКА ОТНОСИТЕЛЬНОГО ВРЕМЕНИ ("через 15 минут", "через два часа")
        // ------------------------------------------------------------------
        Matcher halfMatcher = WITH_HALF_PATTERN.matcher(afterTrigger);
        if (halfMatcher.find()) {
            String numStr = halfMatcher.group(1);
            String unit = halfMatcher.group(2);
            int baseNumber = parseNumber(numStr);
            if (baseNumber <= 0) return null;

            int totalMinutes = (unit.startsWith("ч") || unit.startsWith("ас"))
                    ? baseNumber * 60 + 30
                    : baseNumber;

            return buildResult(totalMinutes, afterTrigger.substring(halfMatcher.end()));
        }

        Matcher quickMatcher = QUICK_TIME.matcher(afterTrigger);
        if (quickMatcher.find()) {
            String token = quickMatcher.group(1).replaceAll("\\s+", "");
            int totalMinutes = token.startsWith("полтора") ? 90 : (token.startsWith("пол") ? 30 : 60);
            return buildResult(totalMinutes, afterTrigger.substring(quickMatcher.end()));
        }

        Matcher throughMatcher = THROUGH_BLOCK.matcher(afterTrigger);
        if (throughMatcher.find()) {
            String timeBlock = throughMatcher.group(1);
            int totalMinutes = parseTimeBlock(timeBlock);
            if (totalMinutes > 0) {
                return buildResult(totalMinutes, afterTrigger.substring(throughMatcher.end()));
            }
        }

        return null;
    }

    private static ParseResult buildResult(int totalMinutes, String taskText) {
        if (taskText == null) return null;
        taskText = taskText.trim();
        if (taskText.isEmpty()) return null;

        taskText = Character.toUpperCase(taskText.charAt(0)) +
                (taskText.length() > 1 ? taskText.substring(1) : "");
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

    private static int calculateMinutesUntil(int targetHour, int targetMinute, DateOffsetResult dateInfo) {
        if (targetHour < 0 || targetHour > 23 || targetMinute < 0 || targetMinute > 59) {
            return -1;
        }

        Calendar now = Calendar.getInstance();
        Calendar target = (Calendar) now.clone();

        target.set(Calendar.HOUR_OF_DAY, targetHour);
        target.set(Calendar.MINUTE, targetMinute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        if (dateInfo.exactDayOfWeek != null) {
            // Вычисляем целевой день недели
            int currentDow = target.get(Calendar.DAY_OF_WEEK);
            int daysUntilDow = (dateInfo.exactDayOfWeek - currentDow + 7) % 7;

            // Если день тот же, но время уже прошло — переносим на следующую неделю (+7)
            if (daysUntilDow == 0 && (target.before(now) || dateInfo.isNextWeek)) {
                daysUntilDow = 7;
            } else if (dateInfo.isNextWeek) {
                daysUntilDow += 7;
            }

            target.add(Calendar.DAY_OF_YEAR, daysUntilDow);

        } else if (dateInfo.daysToAdd > 0) {
            // "завтра" (+1) или "послезавтра" (+2)
            target.add(Calendar.DAY_OF_YEAR, dateInfo.daysToAdd);

        } else {
            // День не был указан (сегодня). Если время уже прошло — планируем на завтра
            if (target.before(now)) {
                target.add(Calendar.DAY_OF_YEAR, 1);
            }
        }

        long diffMillis = target.getTimeInMillis() - now.getTimeInMillis();
        int diffMinutes = (int) Math.round(diffMillis / (60.0 * 1000.0));
        return Math.max(diffMinutes, 1);
    }

    private static int[] parseHourAndMinuteWords(String timeStr) {
        String[] words = timeStr.trim().split("\\s+");
        if (words.length == 0) return null;

        // Вариант А: Одно число (например, "в тринадцать", "в пять", "в 15") -> 13:00, 05:00
        if (words.length == 1) {
            int h = parseNumber(words[0]);
            if (h >= 0 && h <= 23) {
                return new int[]{h, 0};
            }
            return null;
        }

        // Вариант Б: Два и более слов (например, "тринадцать" + "сорок пять" или "двадцать" + "два" + "ноль ноль")
        // Пробуем жадно перебирать разбиение на (часы) и (минуты)
        for (int split = 1; split < words.length; split++) {
            StringBuilder hourPart = new StringBuilder();
            for (int i = 0; i < split; i++) {
                hourPart.append(words[i]).append(" ");
            }

            StringBuilder minutePart = new StringBuilder();
            for (int i = split; i < words.length; i++) {
                minutePart.append(words[i]).append(" ");
            }

            int h = parseNumber(hourPart.toString().trim());
            int m = parseNumber(minutePart.toString().trim());

            if (h >= 0 && h <= 23 && m >= 0 && m <= 59) {
                return new int[]{h, m};
            }
        }

        return null;
    }
}