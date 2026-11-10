package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.constant.BookType

@Entity(tableName = "aiBookMetadata")
data class AiBookMetadata(
    @PrimaryKey
    val bookUrl: String = "",
    val kind: String? = null,
    val tags: String? = null,
    val summary: String? = null,
    val authorIntro: String? = null,
    val matchedAt: Long = System.currentTimeMillis()
)
