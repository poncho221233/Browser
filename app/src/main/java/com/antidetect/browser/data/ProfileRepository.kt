package com.antidetect.browser.data

import kotlinx.coroutines.flow.Flow

class ProfileRepository(private val dao: ProfileDao) {

    fun getAllProfiles(): Flow<List<ProfileEntity>> = dao.getAllProfiles()

    suspend fun getProfile(id: Long): ProfileEntity? = dao.getProfileById(id)

    suspend fun save(profile: ProfileEntity): Long {
        return if (profile.id == 0L) {
            dao.insert(profile)
        } else {
            dao.update(profile)
            profile.id
        }
    }

    suspend fun delete(profile: ProfileEntity) = dao.delete(profile)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun touchLastUsed(id: Long) {
        dao.updateLastUsed(id, System.currentTimeMillis())
    }
}
