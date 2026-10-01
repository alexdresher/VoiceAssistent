package com.alex.voiceassistent;

import org.junit.Test;
import static org.junit.Assert.*;

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
    public void testInvalidCommandsReturnNull() {
        // Фразы, которые парсер должен корректно отклонить
        assertNull("Не команда напоминания", ReminderParser.parse("какая сейчас погода"));
        assertNull("Нет задачи", ReminderParser.parse("напомни через 15 минут"));
        assertNull("Некорректное время", ReminderParser.parse("напомни через много минут сделать дело"));
        assertNull("Пустая строка", ReminderParser.parse(""));
        assertNull("Null строка", ReminderParser.parse(null));
    }
}