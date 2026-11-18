package com.etheroal.os.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * A phone action the user can approve. Built only from validated arguments: the model picks a
 * function and fills its fields, but it can never supply an intent, a URI scheme or a package.
 */
sealed interface PhoneAction {
    val title: String
    val details: List<Pair<String, String>>

    data class Flashlight(val on: Boolean) : PhoneAction {
        override val title = if (on) "Turn the flashlight on" else "Turn the flashlight off"
        override val details = emptyList<Pair<String, String>>()
    }
    data object WifiSettings : PhoneAction {
        override val title = "Open Wi-Fi settings"
        override val details = emptyList<Pair<String, String>>()
    }
    data class CalendarEvent(val name: String, val start: LocalDateTime) : PhoneAction {
        override val title = "Add a calendar event"
        override val details = listOf("Title" to name, "When" to start.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")))
    }
    data class Map(val query: String) : PhoneAction {
        override val title = "Show on the map"
        override val details = listOf("Place" to query)
    }
    data class Email(val to: String, val subject: String, val body: String) : PhoneAction {
        override val title = "Write an email"
        override val details = listOf("To" to to, "Subject" to subject) + if (body.isNotEmpty()) listOf("Body" to body) else emptyList()
    }
    data class Contact(val first: String, val last: String, val phone: String, val email: String) : PhoneAction {
        override val title = "Add a contact"
        override val details = listOfNotNull("Name" to "$first $last".trim(), phone.takeIf { it.isNotEmpty() }?.let { "Phone" to it },
            email.takeIf { it.isNotEmpty() }?.let { "Email" to it })
    }
}

sealed interface ActionOutcome {
    data class Proposed(val action: PhoneAction, val call: String, val latencyMs: Long, val model: String) : ActionOutcome
    data class NoAction(val reason: String, val latencyMs: Long) : ActionOutcome
    data class Failed(val reason: String) : ActionOutcome
}

/**
 * On-device phone actions with Google's FunctionGemma 270M "mobile actions" model (LiteRT-LM).
 * The model turns "turn on the torch" or "lunch with Sam Friday 1pm" into one typed function
 * call; the app checks the arguments and shows the action for the user to approve. Everything
 * runs on the phone, and every action opens the system screen pre-filled rather than acting silently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickActions(private val context: Context) {

    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val lock = Mutex()
    private var engine: Engine? = null

    private val modelFile: File?
        get() = File(context.getExternalFilesDir(null), "models").listFiles()
            ?.firstOrNull { isActionModel(it.name) && it.length() > 0 }

    fun available(): Boolean = modelFile != null

    /** Frees FunctionGemma's memory; it loads again on the next request. */
    suspend fun unload() = lock.withLock { withContext(worker) { engine?.close(); engine = null } }

    suspend fun propose(request: String): ActionOutcome {
        val file = modelFile ?: return ActionOutcome.Failed(
            "FunctionGemma isn't installed. Copy mobile_actions_q8_ekv1024.litertlm into the models folder.",
        )
        val started = SystemClock.elapsedRealtime()
        return try {
            val calls = lock.withLock {
                withContext(worker) {
                    val e = engine ?: Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.path))
                        .also { it.initialize(); engine = it }
                    e.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(developerPrompt()),
                            tools = TOOLS.map { tool(it) },
                            automaticToolCalling = false,
                            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                        ),
                    ).use { it.sendMessage(request.take(500)).toolCalls }
                }
            }
            val latency = SystemClock.elapsedRealtime() - started
            Log.i(TAG, "calls=${calls.map { it.name }} latency=${latency}ms")
            val call = calls.firstOrNull() ?: return ActionOutcome.NoAction("The model didn't pick a phone action for that.", latency)
            val action = PhoneActions.build(call.name, call.arguments)
                ?: return ActionOutcome.NoAction("The model chose ${call.name} with arguments that didn't check out.", latency)
            ActionOutcome.Proposed(action, "${call.name}(${call.arguments.entries.joinToString { "${it.key}=${it.value}" }})", latency, file.nameWithoutExtension)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionOutcome.Failed("FunctionGemma error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Runs an action the user approved. Returns null on success or a message to show. */
    fun run(action: PhoneAction): String? = try {
        when (action) {
            is PhoneAction.Flashlight -> torch(action.on)
            PhoneAction.WifiSettings -> start(Intent(Settings.Panel.ACTION_WIFI))
            is PhoneAction.CalendarEvent -> start(
                Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, action.name)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, action.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
            )
            is PhoneAction.Map -> start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(action.query))))
            is PhoneAction.Email -> start(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                    .putExtra(Intent.EXTRA_EMAIL, arrayOf(action.to))
                    .putExtra(Intent.EXTRA_SUBJECT, action.subject)
                    .putExtra(Intent.EXTRA_TEXT, action.body),
            )
            is PhoneAction.Contact -> start(
                Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.NAME, "${action.first} ${action.last}".trim())
                    .apply { if (action.phone.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.PHONE, action.phone) }
                    .apply { if (action.email.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.EMAIL, action.email) },
            )
        }
    } catch (e: ActivityNotFoundException) {
        "No app on this phone can do that"
    } catch (e: Exception) {
        e.message ?: "That didn't work"
    }

    private fun start(intent: Intent): String? {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    private fun torch(on: Boolean): String? {
        val cameras = context.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone has no flashlight"
        cameras.setTorchMode(id, on)
        return null
    }

    private fun developerPrompt(): String {
        val now = LocalDateTime.now().withNano(0)
        return "Current date and time given in YYYY-MM-DDTHH:MM:SS format: ${now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\n" +
            "Day of week is ${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}\n" +
            "You are a model that can do function calling with the following functions\n"
    }

    companion object {
        private const val TAG = "EtheroalActions"
        fun isActionModel(name: String) = PhoneActions.isActionModel(name)

        private fun fn(name: String, description: String, properties: String = "", required: List<String> = emptyList()) =
            object : OpenApiTool {
                override fun getToolDescriptionJsonString() =
                    """{"name":"$name","description":"$description","parameters":{"type":"OBJECT","properties":{$properties}""" +
                        (if (required.isNotEmpty()) ""","required":[${required.joinToString(",") { "\"$it\"" }}]""" else "") + "}}"
                override fun execute(paramsJsonString: String) = "{}"   // never called: automatic tool calling is off
            }

        private fun prop(name: String, description: String) = """"$name":{"type":"STRING","description":"$description"}"""

        /** Exactly the functions the model was fine-tuned on (google/mobile-actions). */
        private val TOOLS = listOf(
            fn("turn_off_flashlight", "Turns the flashlight off."),
            fn("open_wifi_settings", "Opens the Wi-Fi settings."),
            fn("create_calendar_event", "Creates a new calendar event.",
                prop("title", "The title of the event.") + "," +
                    prop("datetime", "The date and time of the event in the format YYYY-MM-DDTHH:MM:SS."),
                listOf("title", "datetime")),
            fn("show_map", "Shows a location on the map.",
                prop("query", "The location to search for. May be the name of a place, a business, or an address."), listOf("query")),
            fn("send_email", "Sends an email.",
                prop("subject", "The subject of the email.") + "," + prop("body", "The body of the email.") + "," +
                    prop("to", "The email address of the recipient."), listOf("to", "subject")),
            fn("turn_on_flashlight", "Turns the flashlight on."),
            fn("create_contact", "Creates a contact in the phone's contact list.",
                prop("first_name", "The first name of the contact.") + "," + prop("last_name", "The last name of the contact.") + "," +
                    prop("phone_number", "The phone number of the contact.") + "," + prop("email", "The email address of the contact."),
                listOf("first_name", "last_name")),
        )
    }
}
