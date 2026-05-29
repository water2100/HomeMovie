package com.example.localmovielibrary.data.local

import androidx.room.TypeConverter

class MovieTypeConverters {
    private val separator = "\u001F"

    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(separator)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        value.split(separator).filter { it.isNotBlank() }
}
