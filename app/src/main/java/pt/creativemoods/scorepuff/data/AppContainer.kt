// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import pt.creativemoods.scorepuff.metadata.ManualMatchRepository
import pt.creativemoods.scorepuff.config.AndroidOmdbKeyStorage
import pt.creativemoods.scorepuff.config.OmdbKeySettings
import kotlinx.coroutines.sync.withLock
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
    @Volatile private var settings: OmdbKeySettings? = null
    private val clientMutex = Mutex()
    private data class Session(val revision: Long, val client: OmdbClient, val configured: Boolean)
    private var session: Session? = null

    fun omdbSettings(context: Context): OmdbKeySettings = settings ?: synchronized(this) {
        settings ?: OmdbKeySettings(AndroidOmdbKeyStorage(context)).also { settings = it }
    }

    private suspend fun omdbSession(context: Context): Session = clientMutex.withLock {
        val credentials = omdbSettings(context).credentials()
        session?.takeIf { it.revision == credentials.revision } ?: run {
            // A new credential must not inherit a previous key's authentication/quota backoff.
            // Keep positive scores, ambiguous matches and all manual associations.
            if (credentials.revision > 0) database(context).ratings().resetProviderFailures()
            Session(credentials.revision, OmdbClient(credentials.key), credentials.key.isNotEmpty()).also { session = it }
        }
    }

    private fun manualMatches(context: Context, client: OmdbClient) =
        ManualMatchRepository(database(context).ratings(), client, mappingWrites, matchChanges)

    suspend fun manualMatches(context: Context): ManualMatchRepository = manualMatches(context, omdbSession(context).client)

    suspend fun sharedMatches(context: Context): SharedMatchRepository {
        val omdb = omdbSession(context).client
        return SharedMatchRepository(database(context).ratings(), TitleResolver(omdb, omdb), omdb, manualMatches(context, omdb))
    }

    suspend fun ratings(context: Context, scope: CoroutineScope): RatingsRepository {
        val active = omdbSession(context)
        return RatingsRepository(database(context).ratings(), TitleResolver(active.client, active.client), active.client,
            scope, active.configured, mappingWrites = mappingWrites)
    }
}
