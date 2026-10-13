/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.XmltvParser
import com.google.common.truth.Truth.assertThat
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Guards the single most consequential piece of arithmetic in the app.
 *
 * If XMLTV timestamps are parsed wrong, every programme in the guide is displayed at the
 * wrong time — the classic "my EPG is an hour out" complaint. The device's own time zone is
 * changed between tests here precisely because the bug usually hides until someone is on a
 * different offset from the machine the code was written on.
 */
class XmltvTimeTest {

    private lateinit var original: TimeZone

    @Before
    fun setUp() {
        original = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(original)
    }

    @Test
    fun `parses a timestamp with a positive offset`() {
        // 2026-07-27 18:30:00 +0100 == 17:30:00 UTC
        val expected = utcMillis(2026, 7, 27, 17, 30, 0)

        assertThat(XmltvParser.parseXmltvTime("20260727183000 +0100")).isEqualTo(expected)
    }

    @Test
    fun `parses a timestamp with a negative offset`() {
        // 2026-07-27 13:30:00 -0400 == 17:30:00 UTC
        val expected = utcMillis(2026, 7, 27, 17, 30, 0)

        assertThat(XmltvParser.parseXmltvTime("20260727133000 -0400")).isEqualTo(expected)
    }

    @Test
    fun `a missing offset is treated as UTC regardless of the device zone`() {
        val expected = utcMillis(2026, 7, 27, 18, 30, 0)

        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        assertThat(XmltvParser.parseXmltvTime("20260727183000")).isEqualTo(expected)

        TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"))
        assertThat(XmltvParser.parseXmltvTime("20260727183000")).isEqualTo(expected)
    }

    @Test
    fun `result does not depend on the device time zone`() {
        val raw = "20260727183000 +0100"

        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        val inLondon = XmltvParser.parseXmltvTime(raw)

        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        val inAuckland = XmltvParser.parseXmltvTime(raw)

        assertThat(inLondon).isEqualTo(inAuckland)
    }

    @Test
    fun `handles half hour and colon separated offsets`() {
        // 2026-07-27 23:00:00 +0530 == 17:30:00 UTC
        val expected = utcMillis(2026, 7, 27, 17, 30, 0)

        assertThat(XmltvParser.parseXmltvTime("20260727230000 +0530")).isEqualTo(expected)
        assertThat(XmltvParser.parseXmltvTime("20260727230000 +05:30")).isEqualTo(expected)
    }

    @Test
    fun `accepts truncated forms`() {
        assertThat(XmltvParser.parseXmltvTime("202607271830"))
            .isEqualTo(utcMillis(2026, 7, 27, 18, 30, 0))
        assertThat(XmltvParser.parseXmltvTime("2026072718"))
            .isEqualTo(utcMillis(2026, 7, 27, 18, 0, 0))
        assertThat(XmltvParser.parseXmltvTime("20260727"))
            .isEqualTo(utcMillis(2026, 7, 27, 0, 0, 0))
    }

    @Test
    fun `rejects rubbish instead of guessing`() {
        assertThat(XmltvParser.parseXmltvTime(null)).isNull()
        assertThat(XmltvParser.parseXmltvTime("")).isNull()
        assertThat(XmltvParser.parseXmltvTime("not a time")).isNull()
        assertThat(XmltvParser.parseXmltvTime("2026")).isNull()
    }

    @Test
    fun `Z and UTC suffixes mean zero offset`() {
        val expected = utcMillis(2026, 7, 27, 18, 30, 0)

        assertThat(XmltvParser.parseXmltvTime("20260727183000 Z")).isEqualTo(expected)
        assertThat(XmltvParser.parseXmltvTime("20260727183000 UTC")).isEqualTo(expected)
    }

    private fun utcMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long {
        val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        return calendar.timeInMillis
    }
}
