package com.drivercall.data.repository

import com.drivercall.data.local.dao.UserDao
import com.drivercall.data.local.entity.toDomain
import com.drivercall.data.remote.UserApi
import com.drivercall.data.remote.dto.UpdateProfileRequest
import com.drivercall.data.remote.dto.toDomain
import com.drivercall.data.remote.dto.toEntity
import com.drivercall.domain.model.User
import com.drivercall.domain.repository.UserRepository
import com.drivercall.domain.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userApi: UserApi,
    private val userDao: UserDao
) : UserRepository {

    override fun observeProfile(): Flow<User?> =
        userDao.observeUser().map { it?.toDomain() }

    override suspend fun refreshProfile(): Result<User> {
        return try {
            val dto = userApi.getMe().user
            userDao.upsert(dto.toEntity())
            Result.Success(dto.toDomain())
        } catch (e: IOException) {
            Result.Error("No internet connection.", e)
        } catch (e: HttpException) {
            Result.Error("Could not load your profile.", e)
        } catch (e: Exception) {
            Result.Error("Something went wrong.", e)
        }
    }

    override suspend fun updateProfile(name: String, phone: String?): Result<User> {
        val cleanName = name.trim()
        if (cleanName.length < 2) return Result.Error("Please enter your name.")
        return try {
            val dto = userApi.updateMe(
                UpdateProfileRequest(cleanName, phone?.trim().orEmpty().ifBlank { null })
            ).user
            userDao.upsert(dto.toEntity())
            Result.Success(dto.toDomain())
        } catch (e: IOException) {
            Result.Error("No internet connection.", e)
        } catch (e: HttpException) {
            Result.Error("Could not update your profile.", e)
        } catch (e: Exception) {
            Result.Error("Something went wrong.", e)
        }
    }
}
