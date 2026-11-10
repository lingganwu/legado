package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.AiBookMetadata

@Dao
interface AiBookMetadataDao {
    @Query("SELECT * FROM aiBookMetadata WHERE bookUrl = :bookUrl")
    suspend fun getByUrl(bookUrl: String): AiBookMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: AiBookMetadata)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(metadata: List<AiBookMetadata>)

    @Query("DELETE FROM aiBookMetadata WHERE bookUrl = :bookUrl")
    suspend fun deleteByUrl(bookUrl: String)
}
