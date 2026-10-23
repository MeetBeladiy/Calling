package com.drivercall.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.drivercall.data.local.entity.FriendEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FriendDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(friends: List<FriendEntity>)

    @Transaction
    suspend fun replaceAll(friends: List<FriendEntity>) {
        clear()
        upsertAll(friends)
    }

    @Query("SELECT * FROM friends ORDER BY name COLLATE NOCASE ASC")
    fun observeFriends(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM friends WHERE id = :id LIMIT 1")
    fun observeFriend(id: String): Flow<FriendEntity?>

    @Query("SELECT * FROM friends WHERE id = :id LIMIT 1")
    suspend fun getFriend(id: String): FriendEntity?

    @Query("DELETE FROM friends")
    suspend fun clear()
}
