package com.example.keyboard.native

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionCaseTest {

    @Test
    fun `no typed prefix leaves the word unchanged`() {
        assertEquals("hello", SuggestionCase.apply("", "hello"))
    }

    @Test
    fun `lowercase prefix keeps the word lowercase`() {
        assertEquals("hello", SuggestionCase.apply("he", "hello"))
    }

    @Test
    fun `leading capital capitalises only the first letter`() {
        assertEquals("Hello", SuggestionCase.apply("He", "hello"))
    }

    @Test
    fun `all capitals uppercase the whole word`() {
        assertEquals("HELLO", SuggestionCase.apply("HE", "hello"))
    }

    @Test
    fun `non letter characters are ignored when detecting case`() {
        assertEquals("Hello", SuggestionCase.apply("H'", "hello"))
    }

    @Test
    fun `single capital letter capitalises`() {
        assertEquals("World", SuggestionCase.apply("W", "world"))
    }
}
