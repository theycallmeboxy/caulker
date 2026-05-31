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
    version = 5,
    exportSchema = false
)
abstract class CaulkerDatabase : RoomDatabase() {
    abstract fun platformDao(): PlatformDao
    abstract fun romDao(): RomDao
    abstract fun collectionDao(): CollectionDao
    abstract fun saveDao(): SaveDao
}
