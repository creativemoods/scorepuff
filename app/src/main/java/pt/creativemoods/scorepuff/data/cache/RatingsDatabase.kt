// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.data.cache

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "title_mappings")
data class TitleMapping(
    @PrimaryKey val lookupKey: String,
    val netflixId: String?,
    val netflixTitle: String,
    val contentType: String?,
    val imdbId: String? = null,
    val canonicalTitle: String? = null,
    val releaseYear: String? = null,
    val resolvedType: String? = null,
    val status: String,
    val matchConfidence: String? = null,
    val lastVerifiedAt: Long? = null,
    val lastAttemptAt: Long,
    val retryAfter: Long,
    val failures: Int = 0,
    val netflixReleaseYear: Int? = null,
    val netflixRuntimeMinutes: Int? = null
)

@Entity(tableName = "ratings")
data class CachedRating(
    @PrimaryKey val imdbId: String,
    val imdb: Double? = null,
    val rottenTomatoes: Int? = null,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long,
    val retryAfter: Long,
    val failures: Int = 0
)

/** Small interface also permits deterministic repository tests without Android. */
interface RatingsCache {
    suspend fun mapping(key: String): TitleMapping?
    suspend fun putMapping(mapping: TitleMapping)
    suspend fun deleteMapping(key: String)
    suspend fun rating(imdbId: String): CachedRating?
    suspend fun putRating(rating: CachedRating)
}

@Dao
interface RatingsDao : RatingsCache {
    @Query("SELECT * FROM title_mappings WHERE lookupKey = :key")
    override suspend fun mapping(key: String): TitleMapping?
    @Query("SELECT * FROM title_mappings ORDER BY netflixTitle COLLATE NOCASE")
    fun observeMappings(): Flow<List<TitleMapping>>
    @Query("DELETE FROM title_mappings WHERE lookupKey = :key")
    override suspend fun deleteMapping(key: String)
    @Upsert override suspend fun putMapping(mapping: TitleMapping)
    @Query("SELECT * FROM ratings WHERE imdbId = :imdbId")
    override suspend fun rating(imdbId: String): CachedRating?
    @Upsert override suspend fun putRating(rating: CachedRating)
}

@Database(entities = [TitleMapping::class, CachedRating::class], version = 3, exportSchema = true)
abstract class RatingsDatabase : RoomDatabase() {
    abstract fun ratings(): RatingsDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE title_mappings ADD COLUMN netflixRuntimeMinutes INTEGER")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE title_mappings ADD COLUMN netflixReleaseYear INTEGER")
            }
        }
    }
}
