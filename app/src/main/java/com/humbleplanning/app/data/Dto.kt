package com.humbleplanning.app.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Error body of every server route; `error` is already translated into the request language. */
@Serializable
data class ApiErrorBody(
    val error: String = "",
    val code: String = "",
    val params: Map<String, JsonPrimitive> = emptyMap(),
) {
    fun param(name: String): String = params[name]?.contentOrNull.orEmpty()
}

// ---- Mobile sign-in ----

@Serializable
data class MobileInfo(
    val service: String = "",
    val mobileApiVersion: Int = 0,
    val appTitle: String? = null,
    val loginEnabled: Boolean = true,
    val pairingEnabled: Boolean = true,
    val pushEnabled: Boolean = false,
    val alertsEnabled: Boolean = true,
    val forgotPasswordEnabled: Boolean = false,
    val maintenance: Boolean = false,
    val minimumAppVersion: String? = null,
    val storeUrl: String? = null,
)

@Serializable
data class MobileSession(
    val userId: String = "",
    val cookieName: String = "",
    val cookieValue: String = "",
    val expiresAt: String = "",
)

@Serializable
data class MobileLoginResponse(
    val status: String = "",
    val pendingToken: String? = null,
    val session: MobileSession? = null,
    val forcePasswordChange: Boolean = false,
    val setupTwoFactorRequired: Boolean = false,
)

@Serializable
data class DeviceInfo(val name: String, val platform: String = "android", val appVersion: String, val locale: String)

@Serializable data class MobileLoginRequest(val identifier: String, val password: String, val device: DeviceInfo)
@Serializable data class MobileTwoFactorRequest(val pendingToken: String, val code: String, val isBackupCode: Boolean, val device: DeviceInfo)
@Serializable data class MobilePairingRequest(val code: String, val device: DeviceInfo)
@Serializable data class ChangeRequiredPasswordRequest(val password: String)
@Serializable data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
@Serializable data class PasswordOnlyRequest(val password: String)
@Serializable data class CodeOnlyRequest(val code: String)
@Serializable data class TwoFactorSetup(val totpURI: String = "", val secret: String = "")
@Serializable data class BackupCodes(val backupCodes: List<String> = emptyList())

/** `pushToken: null` stops push and does go out; the server refuses a null version or language, left out instead. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateDeviceRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val appVersion: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val locale: String? = null,
    val pushToken: String? = null,
)

// ---- Reference data ----

@Serializable data class RoleDto(val id: String = "", val name: String = "", val isAdmin: Boolean = false)
@Serializable data class BusinessUnitDto(val id: String = "", val name: String = "", val number: String? = null, val color: String? = null)

@Serializable
data class UserDto(
    val id: String = "",
    val name: String = "",
    val firstName: String? = null,
    val username: String? = null,
    val email: String? = null,
    val color: String? = null,
    val image: String? = null,
    val teamId: String? = null,
    val businessUnitId: String? = null,
    val phone: String? = null,
    val mobile: String? = null,
    val job: String? = null,
    val active: Boolean = true,
    /** Order of the person inside their team on the planning grid. */
    val sortOrder: Int = 0,
    val role: RoleDto? = null,
    val twoFactorEnabled: Boolean = false,
    val forceTwoFactor: Boolean = false,
    val forcePasswordChange: Boolean = false,
    val mobilePushNotifications: Boolean = true,
    val mobilePushNotificationsUser: Boolean = true,
) {
    /** The people of a task come with `name` already prefixed by the first name: it is not added twice. */
    val displayName: String get() {
        val first = firstName?.trim().orEmpty()
        return if (first.isEmpty() || name == first || name.startsWith("$first ")) name else "$first $name"
    }
}

@Serializable data class GroupRefDto(val id: String = "", val name: String = "", val color: String? = null, val sortOrder: Int = 0)

@Serializable
data class ProjectDto(
    val id: String = "",
    val name: String = "",
    val color: String? = null,
    val businessNumber: String? = null,
    val businessUnitId: String? = null,
    val ownerId: String? = null,
    val status: String? = null,
    val address: String? = null,
    val addressLat: String? = null,
    val addressLng: String? = null,
    val group: GroupRefDto? = null,
    val businessUnit: BusinessUnitDto? = null,
) {
    /** `[number] name`, like the web planning. */
    val label: String get() = if (businessNumber.isNullOrBlank()) name else "[$businessNumber] $name"
}

@Serializable
data class StatusDto(
    val id: String = "",
    val name: String = "",
    val color: String? = null,
    val isDefault: Boolean = false,
    val showInList: Boolean = true,
    val sortOrder: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val underline: Boolean = false,
)

@Serializable data class PlaceDto(val id: String = "", val name: String = "", val color: String? = null)
@Serializable data class ResourceDto(val id: String = "", val name: String = "", val color: String? = null, val status: String? = null, val businessUnitId: String? = null)
@Serializable data class VehicleDto(val id: String = "", val name: String = "", val color: String? = null, val status: String? = null)
@Serializable data class TeamDto(val id: String = "", val name: String = "", val color: String? = null, val sortOrder: Int = 0, val userIds: List<String> = emptyList())
@Serializable data class HolidayDto(val id: String = "", val date: String = "", val label: String? = null, val color: String? = null)
@Serializable data class AssistantAccess(val available: Boolean = false, val canWrite: Boolean = false)

/** Everything loaded at start-up (`GET /api/mobile/bootstrap`); a list the role cannot read is null. */
@Serializable
data class Bootstrap(
    val serverTime: String = "",
    val user: UserDto = UserDto(),
    val permissions: List<String> = emptyList(),
    val isAdmin: Boolean = false,
    val config: Map<String, String?> = emptyMap(),
    val assistant: AssistantAccess = AssistantAccess(),
    val statuses: List<StatusDto>? = null,
    val places: List<PlaceDto>? = null,
    val projects: List<ProjectDto>? = null,
    val users: List<UserDto>? = null,
    val teams: List<TeamDto>? = null,
    val businessUnits: List<BusinessUnitDto>? = null,
    val holidays: List<HolidayDto>? = null,
    val resources: List<ResourceDto>? = null,
    val vehicles: List<VehicleDto>? = null,
)

// ---- Tasks ----

@Serializable data class RefName(val id: String = "", val name: String = "")

@Serializable
data class TaskFile(
    val id: String = "",
    val name: String = "",
    val originalName: String = "",
    val size: Long = 0,
    val mimeType: String = "",
    val uploadedAt: String? = null,
)

@Serializable
data class TaskDto(
    val id: String = "",
    val title: String? = null,
    val startDate: String = "",
    val endDate: String? = null,
    /** Wall-clock values with no time zone, "HH:MM:SS". */
    val startTime: String? = null,
    val endTime: String? = null,
    val notes: String? = null,
    val link: String? = null,
    val isLocked: Boolean = false,
    val isMilestone: Boolean = false,
    /** Set on every occurrence of a series; equal to `id` on its root. */
    val parentId: String? = null,
    val placeText: String? = null,
    val placeTextLat: String? = null,
    val placeTextLng: String? = null,
    val projectId: String = "",
    val statusId: String = "",
    val placeId: String? = null,
    val resourceId: String? = null,
    val creatorId: String? = null,
    val updatedAt: String? = null,
    val fileCount: Int = 0,
    val files: List<TaskFile> = emptyList(),
    val recurType: String? = null,
    val recurFrequency: Int? = null,
    val recurEndDate: String? = null,
    val recurException: String? = null,
    val project: ProjectDto? = null,
    val status: StatusDto? = null,
    val place: RefName? = null,
    val resource: RefName? = null,
    val users: List<UserDto> = emptyList(),
) {
    val isRecurring: Boolean get() = parentId != null
    val isSeriesRoot: Boolean get() = parentId == id
    val lastDate: String get() = endDate?.takeIf { it.isNotEmpty() } ?: startDate
    val isMultiDay: Boolean get() = lastDate != startDate
    val hasAttachments: Boolean get() = fileCount > 0 || files.isNotEmpty()
    val hasNotes: Boolean get() = !notes.isNullOrBlank()
    val placeLabel: String? get() = place?.name ?: placeText?.takeIf { it.isNotBlank() }
    val userIds: List<String> get() = users.map { it.id }
}

/**
 * Body of a create or update. Every field goes out on an update, so that clearing
 * a value really clears it and `isMilestone` keeps its value (a missing one resets to false).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TaskPayload(
    val projectId: String,
    val userIds: List<String>,
    val statusId: String,
    val startDate: String,
    val endDate: String?,
    val startTime: String?,
    val endTime: String?,
    val title: String?,
    val notes: String?,
    val link: String? = null,
    val placeId: String?,
    val placeText: String?,
    /**
     * Coordinates of the typed address, which the app cannot work out itself: left out so that the server keeps
     * them, a JSON null when the text changed, since they would then point at the old address.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val placeTextLat: JsonElement? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val placeTextLng: JsonElement? = null,
    val resourceId: String?,
    val isMilestone: Boolean,
    /** Left out unless the user may lock: the server refuses null, and refuses the field without `tasks:lock`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val isLocked: Boolean? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RepeatRequest(
    val recurType: String,
    val recurFrequency: Int,
    val recurEndDate: String,
    val recurException: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val recurDayOfWeek: Int? = null,
)

@Serializable data class TaskSync(val serverTime: String = "", val changed: List<TaskDto> = emptyList(), val ids: List<String> = emptyList())

// ---- Alerts, devices, profile ----

@Serializable
data class MobileAlert(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val message: String = "",
    val actionUrl: String? = null,
    val createdAt: String = "",
) {
    /** Task an alert points at: its link looks like `/tasks?id=<uuid>`. */
    val taskId: String? get() = actionUrl?.substringAfter("id=", "")?.substringBefore('&')?.ifBlank { null }
}

/** `more`: the page was full, `serverTime` is then the date of its last alert, to read on from. */
@Serializable data class AlertsPage(val serverTime: String = "", val alerts: List<MobileAlert> = emptyList(), val more: Boolean = false)

@Serializable
data class DeviceDto(
    val id: String = "",
    val platform: String = "",
    val deviceName: String? = null,
    val appVersion: String? = null,
    val pushEnabled: Boolean = false,
    val current: Boolean = false,
    val lastSeenAt: String? = null,
)

@Serializable data class SuccessResponse(val success: Boolean = true)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateProfileRequest(
    val name: String,
    val firstName: String?,
    val phone: String?,
    val mobile: String?,
    val job: String?,
    /** Left out when there is none: the server refuses null. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val color: String? = null,
)

@Serializable data class MobilePushPreference(val mobilePushNotifications: Boolean)
@Serializable data class LocaleRequest(val preference: String)

// ---- Assistant (web chat routes) ----

@Serializable
data class AssistantState(
    val enabled: Boolean = false,
    val level: String = "READ",
    val defaultModel: String? = null,
    val models: List<String> = emptyList(),
    val modelLabels: Map<String, String> = emptyMap(),
    val quota: ChatQuota? = null,
)

/** Where the user stands in their monthly assistant quota: a percentage, never an amount (web: `quota-gauge.tsx`). */
@Serializable
data class ChatQuota(
    /** False when the quota is off or has no amount: no gauge. */
    val enabled: Boolean = false,
    val unlimited: Boolean = false,
    /** From 0 to 100. */
    val percent: Int = 0,
    val reached: Boolean = false,
    /** Next reset, ISO instant. */
    val resetsAt: String = "",
) {
    /** No more questions until the reset; reading stays possible. */
    val blocksQuestions: Boolean get() = enabled && reached && !unlimited

    val level: QuotaLevel get() = when {
        percent >= 90 -> QuotaLevel.Danger
        percent >= 70 -> QuotaLevel.Warning
        else -> QuotaLevel.Success
    }
}

/** Green up to 69%, orange from 70 to 89%, red from 90%, like the web. */
enum class QuotaLevel { Success, Warning, Danger }

@Serializable
data class ConversationDto(
    val id: String = "",
    val title: String = "",
    val pinned: Boolean = false,
    val updatedAt: String? = null,
    val projectId: String? = null,
)

@Serializable data class ConversationMessage(val id: String = "", val role: String = "", val content: String = "", val status: String? = null, val error: String? = null)

@Serializable
data class ConversationDetail(
    val id: String = "",
    val title: String = "",
    val messages: List<ConversationMessage> = emptyList(),
)

@Serializable data class NewConversationRequest(val title: String? = null, val projectId: String? = null)
@Serializable data class RenameConversationRequest(val title: String)
