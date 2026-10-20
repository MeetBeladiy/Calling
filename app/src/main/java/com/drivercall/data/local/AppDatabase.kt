package com.drivercall.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.drivercall.data.local.dao.CallHistoryDao
import com.drivercall.data.local.dao.FriendDao
import com.drivercall.data.local.dao.UserDao
import com.drivercall.data.local.entity.CallRecordEntity
import com.drivercall.data.local.entity.FriendEntity
import com.drivercall.data.local.entity.UserEntity

@Database(
    entities = [UserEntity::class, FriendEntity::class, CallRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun friendDao(): FriendDao
    abstract fun callHistoryDao(): CallHistoryDao
}
