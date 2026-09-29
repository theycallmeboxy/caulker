package com.theycallmeboxy.caulker.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.theycallmeboxy.caulker.data.db.dao.*
import com.theycallmeboxy.caulker.data.db.entity.*

@Database(
    entities = [
        PlatformEntity::class,
        RomEntity::class,
        CollectionEntity::class,
        SaveEntity::class
    ],
    // v6: adds RomEntity.titleId/saveTarget/saveTargetLayout (RomM 5.3+ save-
    // matching fields, save-sync design doc Part 2 §4). No Migration object --
    // follows the project's existing pattern of a destructive migration
    // (AppModule.provideDatabase) since this is a re-fetchable server cache.
    version = 6,
    exportSchema = false
)
abstract class CaulkerDatabase : RoomDatabase() {
    abstract fun platformDao(): PlatformDao
    abstract fun romDao(): RomDao
    abstract fun collectionDao(): CollectionDao
    abstract fun saveDao(): SaveDao
}
