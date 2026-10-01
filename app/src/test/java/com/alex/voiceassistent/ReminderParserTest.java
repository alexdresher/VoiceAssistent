package com.alex.voiceassistent;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Calendar;

public class ReminderParserTest {

    @Test
    public void testMinutesAsDigits() {
        ReminderParser.ParseResult result = ReminderParser.parse("напомни через 15 минут сделать дело");

        assertNotNull("Результат не должен быть null", result);
        assertEquals("Время в минутах должно быть 15", 15, result.totalMinutes);
        assertEquals("Текст задачи должен совпадать", "Сделать дело", result.taskText);
    }

    @Test
    public void testMinutesAsWords() {
        ReminderParser.ParseResult result = ReminderParser.parse("напомни через пятнадцать минут помыть посуду");

        assertNotNull(result);
        assertEquals(15, result.totalMinutes);
        assertEquals("Помыть посуду", result.taskText);

        ReminderParser.ParseResult result2 = ReminderParser.parse("напомни через двадцать пять минут помыть посуду");

        assertNotNull(result2);
        assertEquals(25, result2.totalMinutes);
        assertEquals("Помыть посуду", result.taskText);
    }

    @Test
    public void testDifferentDeclensions() {
        // Проверка склонений: 1 минуту, 2 минуты, 5 минут
        ReminderParser.ParseResult res1 = ReminderParser.parse("напомни через одну минуту позвонить маме");
        assertNotNull(res1);
        assertEquals(1, res1.totalMinutes);
        assertEquals("Позвонить маме", res1.taskText);

        ReminderParser.ParseResult res2 = ReminderParser.parse("напомни через 2 минуты выключить чайник");
        assertNotNull(res2);
        assertEquals(2, res2.totalMinutes);
        assertEquals("Выключить чайник", res2.taskText);
    }

    @Test
    public void testQuickKeywords() {
        // Проверка ключевых слов "полчаса" и "час"
        ReminderParser.ParseResult resHalfHour = ReminderParser.parse("напомни через полчаса выйти на прогулку");
        assertNotNull(resHalfHour);
        assertEquals(30, resHalfHour.totalMinutes);
        assertEquals("Выйти на прогулку", resHalfHour.taskText);

        ReminderParser.ParseResult resHour = ReminderParser.parse("напомни через час забрать заказ");
        assertNotNull(resHour);
        assertEquals(60, resHour.totalMinutes);
        assertEquals("Забрать заказ", resHour.taskText);

        ReminderParser.ParseResult res15Hour = ReminderParser.parse("напомни через полтора часа выйти на прогулку");
        assertNotNull(res15Hour);
        assertEquals(90, res15Hour.totalMinutes);
        assertEquals("Выйти на прогулку", res15Hour.taskText);

        ReminderParser.ParseResult res25Hour = ReminderParser.parse("напомни через два с половиной часа выйти на прогулку");
        assertNotNull(res25Hour);
        assertEquals(150, res25Hour.totalMinutes);
        assertEquals("Выйти на прогулку", res25Hour.taskText);
    }

    @Test
    public void testExactTimeWords() {
        // Фраза с точным временем словами: 13:45
        String phrase = "напомни в тринадцать сорок пять выйти на прогулку";
        ReminderParser.ParseResult result = ReminderParser.parse(phrase);

        assertNotNull("Результат не должен быть null", result);
        assertEquals("Текст задачи должен быть корректным", "Выйти на прогулку", result.taskText);

        // Проверяем математику времени: сколько минут от текущего момента до 13:45
        int expectedMinutes = calculateExpectedMinutesUntil(13, 45);

        // Допускаем погрешность в 1 минуту на случай смены минуты во время прогона теста
        assertEquals("Оставшееся время до 13:45 должно совпадать",
                expectedMinutes, result.totalMinutes, 1.0);
    }

    @Test
    public void testExactTimeDigits() {
        // Фраза с точным временем цифрами: 18:30
        String phrase = "напомни в 18:30 приготовить ужин";
        ReminderParser.ParseResult result = ReminderParser.parse(phrase);

        assertNotNull(result);
        assertEquals("Приготовить ужин", result.taskText);

        int expectedMinutes = calculateExpectedMinutesUntil(18, 30);
        assertEquals(expectedMinutes, result.totalMinutes, 1.0);
    }

    /**
     * Вспомогательный метод для расчета ожидаемых минут в тесте
     */
    private int calculateExpectedMinutesUntil(int targetHour, int targetMinute) {
        Calendar now = Calendar.getInstance();
        Calendar target = (Calendar) now.clone();

        target.set(Calendar.HOUR_OF_DAY, targetHour);
        target.set(Calendar.MINUTE, targetMinute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        // Если время уже прошло сегодня, переносим на завтра
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }

        long diffMillis = target.getTimeInMillis() - now.getTimeInMillis();
        int diffMinutes = (int) Math.round(diffMillis / (60.0 * 1000.0));
        return Math.max(diffMinutes, 1);
    }

    @Test
    public void testInvalidCommandsReturnNull() {
        // Фразы, которые парсер должен корректно отклонить
        assertNull("Не команда напоминания", ReminderParser.parse("какая сейчас погода"));
        assertNull("Нет задачи", ReminderParser.parse("напомни через 15 минут"));
        assertNull("Некорректное время", ReminderParser.parse("напомни через много минут сделать дело"));
        assertNull("Пустая строка", ReminderParser.parse(""));
        assertNull("Null строка", ReminderParser.parse(null));
    }

    @Test
    public void testTomorrowExactTimeWords() {
        // "напомни завтра в тринадцать сорок пять выйти на прогулку"
        String phrase = "напомни завтра в тринадцать сорок пять выйти на прогулку";
        ReminderParser.ParseResult result = ReminderParser.parse(phrase);

        assertNotNull("Результат не должен быть null", result);
        assertEquals("Текст задачи должен совпадать", "Выйти на прогулку", result.taskText);

        // Ожидаем 13:45 следующего дня (+1 день)
        int expectedMinutes = calculateExpectedMinutesForDayOffset(1, 13, 45);

        // Допускаем погрешность в 1 минуту на случай смены минуты во время прогона
        assertEquals("Количество минут до завтра 13:45 должно совпадать",
                expectedMinutes, result.totalMinutes, 1.0);
    }

    @Test
    public void testDayAfterTomorrowDigits() {
        // "напомни послезавтра в 10:00 сдать отчет"
        String phrase = "напомни послезавтра в 10:00 сдать отчет";
        ReminderParser.ParseResult result = ReminderParser.parse(phrase);

        assertNotNull(result);
        assertEquals("Сдать отчет", result.taskText);

        // Ожидаем 10:00 через 2 дня (+2 дня)
        int expectedMinutes = calculateExpectedMinutesForDayOffset(2, 10, 0);
        assertEquals(expectedMinutes, result.totalMinutes, 1.0);
    }

    @Test
    public void testNextTuesday() {
        // "напомни в следующий вторник в двенадцать часов созвон"
        String phrase = "напомни в следующий вторник в двенадцать часов созвон";
        ReminderParser.ParseResult result = ReminderParser.parse(phrase);

        assertNotNull(result);
        assertEquals("Созвон", result.taskText);

        // Вычисляем целевой вторник на следующей неделе
        Calendar now = Calendar.getInstance();
        Calendar target = (Calendar) now.clone();
        target.set(Calendar.HOUR_OF_DAY, 12);
        target.set(Calendar.MINUTE, 0);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        int currentDow = target.get(Calendar.DAY_OF_WEEK);
        int daysUntilDow = (Calendar.TUESDAY - currentDow + 7) % 7;
        if (daysUntilDow == 0) {
            daysUntilDow = 7;
        }
        // Так как сказано "в следующий", добавляем еще 7 дней, если вторник еще не прошел на этой неделе
        daysUntilDow += 7;
        target.add(Calendar.DAY_OF_YEAR, daysUntilDow);

        long diffMillis = target.getTimeInMillis() - now.getTimeInMillis();
        int expectedMinutes = (int) Math.round(diffMillis / (60.0 * 1000.0));

        assertEquals(expectedMinutes, result.totalMinutes, 1.0);
    }

    /**
     * Вспомогательный метод расчета оставшихся минут со смещением в днях
     */
    private int calculateExpectedMinutesForDayOffset(int daysToAdd, int targetHour, int targetMinute) {
        Calendar now = Calendar.getInstance();
        Calendar target = (Calendar) now.clone();

        target.add(Calendar.DAY_OF_YEAR, daysToAdd);
        target.set(Calendar.HOUR_OF_DAY, targetHour);
        target.set(Calendar.MINUTE, targetMinute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        long diffMillis = target.getTimeInMillis() - now.getTimeInMillis();
        return (int) Math.round(diffMillis / (60.0 * 1000.0));
    }
}