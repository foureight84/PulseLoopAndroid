package com.pulseloop.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.pulseloop.MainActivity
import com.pulseloop.coach.openai.OpenAIResponsesClient
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.settings.ApiKeyStore
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Ported from CoachNotificationGenerator + CoachNotificationService in the iOS app.
 * Daily AI check-in notifications via WorkManager periodic task. Uses OpenAI to
 * generate personalized check-in text based on the user's ring data.
 */
object CoachNotifications {
    private const val CHANNEL_ID = "coach_checkins"
    private const val DAILY_WORK_NAME = "coach_daily_checkin"
    private const val NOTIFICATION_ID = 2001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Coach Check-ins", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily AI-generated health insights based on your ring data"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /** Schedule a daily check-in notification. Requires POST_NOTIFICATIONS on Android 13+. */
    fun schedule(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val request = PeriodicWorkRequestBuilder<CoachNotificationWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DAILY_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DAILY_WORK_NAME)
    }

    /** Show an immediate check-in notification. */
    fun showNow(context: Context, title: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}

/**
 * WorkManager worker for generating daily coach check-in notifications.
 * Ported from CoachNotificationGenerator.swift — generates AI-powered
 * personalized notifications via OpenAI, with a deterministic fallback.
 */
class CoachNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = PulseLoopDatabase.getInstance(applicationContext)
            val keyStore = ApiKeyStore(applicationContext)

            // Only generate if coach is enabled and we have an API key
            if (!keyStore.coachEnabled || keyStore.apiKey.isBlank()) {
                CoachNotifications.showNow(
                    applicationContext,
                    "PulseLoop Coach",
                    "Good morning! Sync your ring and check your vitals to start the day.",
                )
                return Result.success()
            }

            // Determine current slot
            val now = LocalDateTime.now()
            val hour = now.hour
            val slot = CoachNotificationSlot.current(hour, keyStore.morningHour, keyStore.eveningHour)
                ?: CoachNotificationSlot.forcedSlot(hour)

            // Build context
            val packet = NotificationContextBuilder.build(slot, db)

            // Generate via AI or fallback
            val notification = try {
                generateWithAI(slot, packet, keyStore.apiKey, keyStore.model)
            } catch (e: Exception) {
                scripted(slot, packet)
            }

            CoachNotifications.showNow(applicationContext, notification.title, notification.body)
            Result.success()
        } catch (e: Exception) {
            // Ultimate fallback
            CoachNotifications.showNow(
                applicationContext,
                "PulseLoop Coach",
                "Good morning! Sync your ring and check your vitals to start the day.",
            )
            Result.success()
        }
    }

    private suspend fun generateWithAI(
        slot: CoachNotificationSlot,
        packet: NotificationContextPacket,
        apiKey: String,
        model: String,
    ): CoachNotificationContent {
        val client = OpenAIResponsesClient(apiKey)

        val input = JsonArray(listOf(
            JsonObject(mapOf(
                "role" to JsonPrimitive("system"),
                "content" to JsonPrimitive(NotificationPromptBuilder.systemPrompt(slot)),
            )),
            JsonObject(mapOf(
                "role" to JsonPrimitive("developer"),
                "content" to JsonPrimitive(NotificationPromptBuilder.developerMessage(packet)),
            )),
        ))

        val schemaProps = JsonObject(mapOf(
            "title" to JsonObject(mapOf("type" to JsonPrimitive("string"), "maxLength" to JsonPrimitive(50))),
            "body" to JsonObject(mapOf("type" to JsonPrimitive("string"), "maxLength" to JsonPrimitive(160))),
        ))
        val schema = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to schemaProps,
            "required" to JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("body"))),
            "additionalProperties" to JsonPrimitive(false),
        ))
        val format = JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"),
            "name" to JsonPrimitive("coach_notification"),
            "schema" to schema,
            "strict" to JsonPrimitive(true),
        ))
        val text = JsonObject(mapOf("format" to format))

        val requestBody = JsonObject(mapOf(
            "model" to JsonPrimitive(model),
            "input" to input,
            "tools" to JsonArray(emptyList()),
            "text" to text,
        ))

        val response = client.send(requestBody.toString().toByteArray())
        val output = response.outputText
        return CoachNotificationContent.decodeFromJson(output)
            ?: scripted(slot, packet)
    }

    /** Deterministic fallback — ported from CoachNotificationGenerator.scripted(). */
    private fun scripted(slot: CoachNotificationSlot, packet: NotificationContextPacket): CoachNotificationContent {
        val name = packet.profileName?.let { ", $it" } ?: ""
        return when (slot) {
            CoachNotificationSlot.MORNING -> {
                val sleep = packet.latestSleep
                if (sleep != null) {
                    val h = sleep.totalMin / 60
                    val m = sleep.totalMin % 60
                    CoachNotificationContent(
                        title = "Good morning$name",
                        body = "You logged ${h}h ${m}m of sleep. Here's to a strong day — get moving when you can.",
                    )
                } else {
                    CoachNotificationContent(
                        title = "Good morning$name",
                        body = "Ready to start the day? Take a measurement and I'll help you plan it.",
                    )
                }
            }
            CoachNotificationSlot.EVENING -> {
                val steps = packet.today.steps
                if (steps != null) {
                    val goal = packet.goals.stepsDaily
                    val hit = if (steps >= goal) "You hit your $goal step goal — nice work." else "${goal - steps} steps to your goal."
                    CoachNotificationContent(
                        title = "Evening check-in",
                        body = "$steps steps today. $hit Time to start winding down.",
                    )
                } else {
                    CoachNotificationContent(
                        title = "Evening check-in",
                        body = "How did today feel? Sync your ring and I'll recap your day.",
                    )
                }
            }
        }
    }
}
