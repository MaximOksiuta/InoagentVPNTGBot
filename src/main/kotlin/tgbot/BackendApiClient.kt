package tgbot

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class BackendApiClient(
    private val baseUrl: String,
    private val superKey: String
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    fun register(request: RegisterRequest): RegisterResponse =
        sendJson("POST", "/api/auth/register", RegisterResponse.serializer(), body = request, bodySerializer = RegisterRequest.serializer())

    fun getCurrentUser(phone: String): CurrentUserResponse =
        sendJson("GET", "/api/auth/me", CurrentUserResponse.serializer(), phone = phone)

    fun listDevices(phone: String): List<DeviceResponse> =
        sendJson("GET", "/api/devices", ListSerializer(DeviceResponse.serializer()), phone = phone)

    fun getDevice(phone: String, deviceId: Long): DeviceDetailsResponse =
        sendJson("GET", "/api/devices/$deviceId", DeviceDetailsResponse.serializer(), phone = phone)

    fun createDevice(phone: String, request: CreateDeviceRequest): DeviceResponse =
        sendJson("POST", "/api/devices", DeviceResponse.serializer(), phone = phone, body = request, bodySerializer = CreateDeviceRequest.serializer())

    fun updateDevice(phone: String, deviceId: Long, request: UpdateDeviceRequest): DeviceDetailsResponse =
        sendJson("PUT", "/api/devices/$deviceId", DeviceDetailsResponse.serializer(), phone = phone, body = request, bodySerializer = UpdateDeviceRequest.serializer())

    fun deleteDevice(phone: String, deviceId: Long) {
        sendWithoutBody("DELETE", "/api/devices/$deviceId", phone)
    }

    fun listServers(phone: String): List<ServerListItemResponse> =
        sendJson("GET", "/api/servers", ListSerializer(ServerListItemResponse.serializer()), phone = phone)

    fun generateConfig(phone: String, deviceId: Long, serverId: Long): DeviceServerResponse =
        sendJson(
            "POST",
            "/api/devices/$deviceId/configs/generate",
            DeviceServerResponse.serializer(),
            phone = phone,
            body = GenerateDeviceConfigRequest(serverId),
            bodySerializer = GenerateDeviceConfigRequest.serializer()
        )

    fun downloadConfigFile(phone: String, deviceId: Long, configId: Long): BinaryPayload =
        sendBinary("GET", "/api/devices/$deviceId/configs/$configId/file", phone)

    fun downloadConfigQr(phone: String, deviceId: Long, configId: Long): BinaryPayload =
        sendBinary("GET", "/api/devices/$deviceId/configs/$configId/qr", phone)

    fun listUsers(phone: String): List<AdminUserResponse> =
        sendJson("GET", "/api/users", ListSerializer(AdminUserResponse.serializer()), phone = phone)

    fun approveUser(phone: String, userId: Long): AdminUserResponse =
        sendJson("POST", "/api/users/$userId/approve", AdminUserResponse.serializer(), phone = phone)

    fun banUser(phone: String, userId: Long): AdminUserResponse =
        sendJson("POST", "/api/users/$userId/ban", AdminUserResponse.serializer(), phone = phone)

    fun adminListServers(phone: String): List<ServerListItemResponse> = listServers(phone)

    fun getServer(phone: String, serverId: Long): ServerResponse =
        sendJson("GET", "/api/servers/$serverId", ServerResponse.serializer(), phone = phone)

    fun createServer(phone: String, request: UpsertServerRequest): ServerResponse =
        sendJson("POST", "/api/servers", ServerResponse.serializer(), phone = phone, body = request, bodySerializer = UpsertServerRequest.serializer())

    fun updateServer(phone: String, serverId: Long, request: UpsertServerRequest): ServerResponse =
        sendJson("PUT", "/api/servers/$serverId", ServerResponse.serializer(), phone = phone, body = request, bodySerializer = UpsertServerRequest.serializer())

    fun deleteServer(phone: String, serverId: Long) {
        sendWithoutBody("DELETE", "/api/servers/$serverId", phone)
    }

    fun listAllConfigs(phone: String): List<AdminDeviceConfigResponse> =
        sendJson("GET", "/api/configs", ListSerializer(AdminDeviceConfigResponse.serializer()), phone = phone)

    fun deleteAdminConfig(phone: String, configId: Long) {
        sendWithoutBody("DELETE", "/api/configs/$configId", phone)
    }

    private fun <T : Any> sendJson(
        method: String,
        path: String,
        responseSerializer: kotlinx.serialization.DeserializationStrategy<T>,
        phone: String? = null
    ): T {
        val response = sendRaw(method, path, phone)
        if (response.statusCode() !in 200..299) {
            throw parseApiException(response.statusCode(), response.body())
        }
        return json.decodeFromString(responseSerializer, response.body())
    }

    private fun <T : Any, B : Any> sendJson(
        method: String,
        path: String,
        responseSerializer: kotlinx.serialization.DeserializationStrategy<T>,
        phone: String? = null,
        body: B,
        bodySerializer: SerializationStrategy<B>
    ): T {
        val response = sendRaw(method, path, phone, body, bodySerializer)
        if (response.statusCode() !in 200..299) {
            throw parseApiException(response.statusCode(), response.body())
        }
        return json.decodeFromString(responseSerializer, response.body())
    }

    private fun sendWithoutBody(
        method: String,
        path: String,
        phone: String
    ) {
        val response = sendRaw(method, path, phone)
        if (response.statusCode() !in 200..299) {
            throw parseApiException(response.statusCode(), response.body())
        }
    }

    private fun sendBinary(
        method: String,
        path: String,
        phone: String
    ): BinaryPayload {
        val request = requestBuilder(method, path, phone)
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            throw parseApiException(response.statusCode(), String(response.body()))
        }
        return BinaryPayload(
            bytes = response.body(),
            contentType = response.headers().firstValue("Content-Type").orElse(null),
            disposition = response.headers().firstValue("Content-Disposition").orElse(null)
        )
    }

    private fun sendRaw(
        method: String,
        path: String,
        phone: String? = null
    ): HttpResponse<String> {
        val builder = requestBuilder(method, path, phone)
        val request = builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun <B : Any> sendRaw(
        method: String,
        path: String,
        phone: String? = null,
        body: B,
        bodySerializer: SerializationStrategy<B>
    ): HttpResponse<String> {
        val builder = requestBuilder(method, path, phone)
        builder.header("Content-Type", "application/json")
        val request = builder
            .method(method, HttpRequest.BodyPublishers.ofString(json.encodeToString(bodySerializer, body)))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun requestBuilder(
        method: String,
        path: String,
        phone: String?
    ): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(45))
            .header("Accept", "application/json")

        if (phone != null) {
            builder.header("X-Super-Key", superKey)
            builder.header("X-Phone", phone)
        }
        if (method == "POST" || method == "PUT") {
            builder.header("Content-Type", "application/json")
        }
        return builder
    }

    private fun parseApiException(statusCode: Int, body: String): ApiException {
        val message = runCatching { json.decodeFromString<ErrorResponse>(body).message }
            .getOrDefault(body.ifBlank { "API error" })
        return ApiException(statusCode, message)
    }
}
