package com.etheroal.os.agent

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.etheroal.os.ai.PhoneActions
import com.etheroal.os.ai.QuickActions
import com.etheroal.os.core.agent.Param
import com.etheroal.os.core.agent.ParamType
import com.etheroal.os.core.agent.Tool
import com.etheroal.os.core.agent.ToolResult
import com.etheroal.os.core.agent.ToolSpec
import com.etheroal.os.core.agent.Verdict
import com.etheroal.os.core.agent.tool
import com.etheroal.os.home.AppCatalog

/**
 * The orchestrator's direct tools: Android intents and APIs that do a job in one step, with no
 * screen driving. They are the "API mode" path: instant and reliable, so the model is told to
 * prefer them. Anything they cannot do goes to [operatePhone].
 */
class PhoneTools(
    private val context: Context,
    private val apps: AppCatalog,
    private val actions: QuickActions,
    private val search: GeminiSearch,
    private val operatePhone: suspend (String) -> ToolResult,
) {
    private val own = context.packageName

    fun tools(): List<Tool> = listOf(
        tool(
            ToolSpec(
                "operate_phone",
                "Hand a task to the operator, which uses apps on the phone by reading the screen and tapping like a person. " +
                    "Slower than the other tools: use it only when they cannot do the job. Give the full task with every detail.",
                listOf(Param("task", ParamType.STRING, "The complete task, e.g. 'In WhatsApp, send Sam the message: running 10 minutes late'")),
            ),
            describe = { c -> "Using the phone: ${c.str("task").take(60)}" },
            check = { c -> if (c.str("task").isBlank()) Verdict.Refused("operate_phone needs a task.") else Verdict.Allowed },
        ) { c -> operatePhone(c.str("task")) },

        tool(
            ToolSpec("open_app", "Open an installed app for the owner.", listOf(Param("name", ParamType.STRING, "The app's name"))),
            describe = { c -> "Opening ${c.str("name")}" },
        ) { c -> OperatorContext.openApp(apps, c.str("name"), own) },

        tool(
            ToolSpec(
                "set_alarm", "Set an alarm in the phone's clock app.",
                listOf(
                    Param("hour", ParamType.INTEGER, "Hour, 0-23"),
                    Param("minute", ParamType.INTEGER, "Minute, 0-59"),
                    Param("label", ParamType.STRING, "Short label", required = false),
                ),
            ),
            describe = { c -> "Setting an alarm for %02d:%02d".format(c.int("hour") ?: 0, c.int("minute") ?: 0) },
            check = { c -> if ((c.int("hour") ?: -1) !in 0..23 || (c.int("minute") ?: -1) !in 0..59) Verdict.Refused("Hour must be 0-23 and minute 0-59.") else Verdict.Allowed },
        ) { c ->
            start(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, c.int("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, c.int("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, c.str("label").take(60))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                "Alarm set for %02d:%02d.".format(c.int("hour"), c.int("minute")),
            )
        },

        tool(
            ToolSpec(
                "set_timer", "Start a countdown timer in the phone's clock app.",
                listOf(Param("seconds", ParamType.INTEGER, "Length in seconds"), Param("label", ParamType.STRING, "Short label", required = false)),
            ),
            describe = { c -> "Starting a ${(c.int("seconds") ?: 0) / 60}-minute timer" },
            check = { c -> if ((c.int("seconds") ?: 0) !in 1..86_400) Verdict.Refused("A timer must be 1 second to 24 hours.") else Verdict.Allowed },
        ) { c ->
            start(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, c.int("seconds"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, c.str("label").take(60))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                "Timer started for ${c.int("seconds")} seconds.",
            )
        },

        action("create_calendar_event", "Open a new calendar event, filled in, for the owner to save.",
            listOf(Param("title", ParamType.STRING, "Event title"), Param("datetime", ParamType.STRING, "Start, as YYYY-MM-DDTHH:MM:SS")),
            "Calendar event opened for the owner to save."),
        action("show_map", "Show a place or search on the map.", listOf(Param("query", ParamType.STRING, "Place or search")), "Map opened."),
        action("send_email", "Open an email draft, filled in, for the owner to send.",
            listOf(Param("to", ParamType.STRING, "Address"), Param("subject", ParamType.STRING, "Subject"), Param("body", ParamType.STRING, "Body", required = false)),
            "Email draft opened; the owner sends it."),
        action("create_contact", "Open a new contact, filled in, for the owner to save.",
            listOf(
                Param("first_name", ParamType.STRING, "First name"), Param("last_name", ParamType.STRING, "Last name", required = false),
                Param("phone_number", ParamType.STRING, "Phone", required = false), Param("email", ParamType.STRING, "Email", required = false),
            ),
            "Contact form opened for the owner to save."),

        tool(
            ToolSpec("flashlight", "Turn the flashlight on or off.", listOf(Param("on", ParamType.BOOLEAN, "true to turn it on"))),
            describe = { c -> if (c.bool("on")) "Turning the flashlight on" else "Turning the flashlight off" },
        ) { c ->
            val action = PhoneActions.build(if (c.bool("on")) "turn_on_flashlight" else "turn_off_flashlight", emptyMap())!!
            actions.run(action)?.let { ToolResult(it, ok = false) } ?: ToolResult("Flashlight ${if (c.bool("on")) "on" else "off"}.")
        },

        tool(
            ToolSpec("web_search", "Search the web for current information.", listOf(Param("query", ParamType.STRING, "What to look up"))),
            describe = { c -> "Searching: ${c.str("query").take(50)}" },
        ) { c -> ToolResult(search.search(c.str("query")).ifBlank { "No results." }) },
    )

    /** One of the existing validated phone actions (the same rules FunctionGemma's calls pass). */
    private fun action(name: String, description: String, params: List<Param>, done: String): Tool = tool(
        ToolSpec(name, description, params),
        describe = { name.replace('_', ' ').replaceFirstChar { it.uppercase() } },
        check = { c -> if (PhoneActions.build(name, c.args) == null) Verdict.Refused("Those details don't check out for $name; fix them or ask the owner.") else Verdict.Allowed },
    ) { c ->
        val action = PhoneActions.build(name, c.args)!!
        actions.run(action)?.let { ToolResult(it, ok = false) } ?: ToolResult(done)
    }

    private fun start(intent: Intent, done: String): ToolResult = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ToolResult(done)
    } catch (e: Exception) {
        ToolResult("No app on this phone can do that.", ok = false)
    }
}
