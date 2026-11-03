// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import pt.creativemoods.scorepuff.metadata.ManualMatchRepository
import pt.creativemoods.scorepuff.config.ApiConfiguration
import pt.creativemoods.scorepuff.data.cache.RatingsDatabase
import pt.creativemoods.scorepuff.metadata.TitleResolver
import pt.creativemoods.scorepuff.ratings.*
import pt.creativemoods.scorepuff.data.models.NetflixTitle
import pt.creativemoods.scorepuff.metadata.SharedMatchRepository

object AppContainer {
    data class DetailVisit(val title: NetflixTitle, val seenAt: Long)
    @Volatile var lastDetails: DetailVisit? = null
    @Volatile private var database: RatingsDatabase? = null
    val matchChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val mappingWrites = Mutex()
    private fun database(context: Context): RatingsDatabase {
        val db = database ?: synchronized(this) {
            database ?: Room.databaseBuilder(context.applicationContext, RatingsDatabase::class.java,
                "netflix-ratings.db").addMigrations(RatingsDatabase.MIGRATION_1_2, RatingsDatabase.MIGRATION_2_3).build().also { database = it }
        }
        return db
    }
    fun mappings(context: Context) = database(context).ratings().observeMappings()
    fun manualMatches(context: Context) = ManualMatchRepository(database(context).ratings(),
        OmdbClient(ApiConfiguration.omdbApiKey()), mappingWrites, matchChanges)

    fun sharedMatches(context: Context): SharedMatchRepository {
        val omdb = OmdbClient(ApiConfiguration.omdbApiKey())
        return SharedMatchRepository(database(context).ratings(), TitleResolver(omdb, omdb), omdb, manualMatches(context))
    }

    fun ratings(context: Context, scope: CoroutineScope): RatingsRepository {
        val db = database(context)
        val omdb = OmdbClient(ApiConfiguration.omdbApiKey())
        return RatingsRepository(db.ratings(), TitleResolver(omdb, omdb), omdb, scope, ApiConfiguration.omdbApiKey().isNotBlank(), mappingWrites = mappingWrites)
    }
}
