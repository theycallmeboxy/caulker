package com.theycallmeboxy.caulker.data.api

import com.theycallmeboxy.caulker.data.api.model.*
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface RommApiService {

    @GET("api/heartbeat")
    suspend fun heartbeat(): HeartbeatResponse

    @POST("api/client-tokens/exchange")
    suspend fun exchangeCode(@Body request: ExchangeCodeRequest): TokenResponse

    @GET("api/users/me")
    suspend fun getCurrentUser(): UserResponse

    // --- Platforms ---

    @GET("api/platforms")
    suspend fun getPlatforms(
        @Query("updated_after") updatedAfter: String? = null
    ): List<PlatformResponse>

    @GET("api/platforms/{id}")
    suspend fun getPlatform(@Path("id") id: Int): PlatformResponse

    @GET("api/platforms/identifiers")
    suspend fun getPlatformIdentifiers(): List<Int>

    // --- ROMs ---

    @GET("api/roms")
    suspend fun getRoms(
        @Query("platform_ids") platformId: Int? = null,
        @Query("collection_id") collectionId: Int? = null,
        @Query("virtual_collection_id") virtualCollectionId: String? = null,
        @Query("smart_collection_id") smartCollectionId: Int? = null,
        @Query("search") search: String? = null,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 50,
        @Query("order_by") orderBy: String? = null,
        @Query("order_dir") orderDir: String? = null,
        @Query("updated_after") updatedAfter: String? = null
    ): RomPageResponse

    @GET("api/roms/{id}")
    suspend fun getRom(@Path("id") id: Int): RomResponse

    @GET("api/roms/identifiers")
    suspend fun getRomIdentifiers(): List<Int>

    @GET("api/roms/by-hash")
    suspend fun getRomByHash(
        @Query("crc_hash") crcHash: String? = null,
        @Query("md5_hash") md5Hash: String? = null,
        @Query("sha1_hash") sha1Hash: String? = null
    ): RomResponse

    // --- Collections ---

    @GET("api/collections")
    suspend fun getCollections(
        @Query("updated_after") updatedAfter: String? = null
    ): List<CollectionResponse>

    @GET("api/collections/smart")
    suspend fun getSmartCollections(
        @Query("updated_after") updatedAfter: String? = null
    ): List<CollectionResponse>

    @GET("api/collections/identifiers")
    suspend fun getCollectionIdentifiers(): List<Int>

    // `type` is required server-side (e.g. "genre", "franchise"); "all" returns
    // every type. Omitting it returns HTTP 422.
    @GET("api/collections/virtual")
    suspend fun getVirtualCollections(
        @Query("type") type: String = "all"
    ): List<VirtualCollectionResponse>

    @GET("api/collections/virtual/{id}")
    suspend fun getVirtualCollection(@Path("id") id: String): VirtualCollectionResponse

    // --- Saves ---

    @GET("api/saves")
    suspend fun getSaves(
        @Query("rom_id") romId: Int? = null,
        @Query("platform_id") platformId: Int? = null,
        @Query("device_id") deviceId: String? = null,
        @Query("emulator") emulator: String? = null,
        @Query("slot") slot: String? = null
    ): List<SaveResponse>

    @GET("api/saves/{id}/content")
    suspend fun downloadSave(
        @Path("id") id: Int,
        @Query("device_id") deviceId: String,
        @Query("session_id") sessionId: Int? = null,
        @Query("optimistic") optimistic: Boolean = true
    ): Response<ResponseBody>

    @POST("api/saves/{id}/downloaded")
    suspend fun markSaveDownloaded(
        @Path("id") id: Int,
        @Body request: MarkDownloadedRequest
    ): Response<Unit>

    @POST("api/saves/{id}/track")
    suspend fun trackSave(
        @Path("id") id: Int,
        @Body request: MarkDownloadedRequest
    ): SaveResponse

    @POST("api/saves/{id}/untrack")
    suspend fun untrackSave(
        @Path("id") id: Int,
        @Body request: MarkDownloadedRequest
    ): SaveResponse

    @Multipart
    @POST("api/saves")
    suspend fun uploadSave(
        @Query("rom_id") romId: Int,
        @Query("device_id") deviceId: String,
        @Query("slot") slot: String,
        @Query("emulator") emulator: String? = null,
        @Query("overwrite") overwrite: Boolean = false,
        @Query("session_id") sessionId: Int? = null,
        @Query("autocleanup") autocleanup: Boolean = false,
        @Query("autocleanup_limit") autocleanupLimit: Int = 10,
        @Part saveFile: MultipartBody.Part
    ): SaveResponse

    @Multipart
    @PUT("api/saves/{id}")
    suspend fun updateSave(
        @Path("id") id: Int,
        @Part saveFile: MultipartBody.Part
    ): SaveResponse

    // --- Save sync engine (RomM 4.9) ---

    @POST("api/sync/negotiate")
    suspend fun negotiateSync(@Body request: SyncNegotiateRequest): SyncNegotiateResponse

    @POST("api/sync/sessions/{id}/complete")
    suspend fun completeSyncSession(
        @Path("id") id: Int,
        @Body request: SyncCompleteRequest
    ): SyncCompleteResponse

    @GET("api/sync/sessions")
    suspend fun getSyncSessions(
        @Query("device_id") deviceId: String? = null,
        @Query("limit") limit: Int = 50
    ): List<SyncSessionResponse>

    // --- Devices ---

    @GET("api/devices")
    suspend fun getDevices(): List<DeviceResponse>

    @POST("api/devices")
    suspend fun registerDevice(@Body request: RegisterDeviceRequest): DeviceResponse

    @PUT("api/devices/{id}")
    suspend fun updateDevice(
        @Path("id") id: String,
        @Body request: Map<String, @JvmSuppressWildcards Any>
    ): DeviceResponse

    @DELETE("api/devices/{id}")
    suspend fun deleteDevice(@Path("id") id: String): Response<Unit>

    // --- Firmware ---

    @GET("api/firmware")
    suspend fun getFirmware(@Query("platform_id") platformId: Int): List<FirmwareResponse>

    // --- Downloads ---

    @Streaming
    @GET
    suspend fun downloadFile(@Url url: String): Response<ResponseBody>
}
