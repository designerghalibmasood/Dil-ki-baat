package com.example.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey val id: String, // We will use the file name as the ID
    val filePath: String,
    val fileName: String,
    val formattedDate: String,
    val formattedTime: String,
    val durationMs: Long,
    val createdAt: Long
)
