package dev.corruptedark.diditakemymeds

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.res.ResourcesCompat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AlarmReceiver : BroadcastReceiver() {
    private var alarmManager: AlarmManager? = null
    private lateinit var alarmIntent: PendingIntent
    val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    companion object {
        const val NOTIFY_ACTION = "NOTIFY"
        const val TOOK_MED_ACTION = "TOOK_MED"
        const val REMIND_ACTION = "REMIND"
        const val DISMISS_ACTION = "DISMISS"
        const val NO_ICON = 0
    }

    private fun createNotificationChannel(context: Context) {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.channel_name)
            val descriptionText = context.getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(name, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildAndShowNotification(context: Context, medication: Medication) {
        val calendar = Calendar.getInstance()
        val currentTime = calendar.timeInMillis
        MedicationDB.getInstance(context).medicationDao().updateMedications(medication)

        val actionIntent = Intent(context, MedDetailActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(context.getString(R.string.med_id_key), medication.id)
        }

        val pendingIntent = PendingIntent.getActivity(context, medication.id.toInt() + System.currentTimeMillis().toInt(), actionIntent, 0)

        val closestDose = medication.calculateClosestDose()
        val hour = closestDose.schedule.hour
        val minute = closestDose.schedule.minute
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        val isSystem24Hour = DateFormat.is24HourFormat(context)

        val formattedTime = if (isSystem24Hour) DateFormat.format(
            context.getString(R.string.time_24),
            calendar
        )
        else DateFormat.format(context.getString(R.string.time_12), calendar)

        //Start building "took med" notification action
        val tookMedIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = TOOK_MED_ACTION
            putExtra(context.getString(R.string.med_id_key), medication.id)
        }
        val tookMedPendingIntent = PendingIntent.getBroadcast(context, medication.id.toInt(), tookMedIntent, 0)
        //End building "took med" notification action

        val builder = NotificationCompat.Builder(
            context,
            context.getString(R.string.channel_name)
        )
            .setSmallIcon(R.drawable.ic_small_notification)
            .setColor(
                ResourcesCompat.getColor(
                    context.resources,
                    R.color.purple_500,
                    context.theme
                )
            )
            .setContentTitle(medication.name)
            .setSubText(formattedTime)
            .setContentText(context.getString(R.string.time_for_your_dose))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .addAction(NO_ICON, context.getString(R.string.took_it), tookMedPendingIntent)
        with(NotificationManagerCompat.from(context.applicationContext)) {
            notify(
                (currentTime + medication.name.hashCode()).toInt(),
                builder.build()
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        createNotificationChannel(context)
        executorService.execute {
            val medications = MedicationDB.getInstance(context).medicationDao().getAll()

            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> {
                    medications.forEach { medication ->
                        medication.updateStartsToFuture()
                        if (medication.notify) {
                            //Create alarm
                            alarmIntent =
                                Intent(context, AlarmReceiver::class.java).let { innerIntent ->
                                    innerIntent.action = NOTIFY_ACTION
                                    innerIntent.putExtra(
                                        context.getString(R.string.med_id_key),
                                        medication.id
                                    )
                                    PendingIntent.getBroadcast(
                                        context,
                                        medication.id.toInt(),
                                        innerIntent,
                                        0
                                    )
                                }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                alarmManager?.setExactAndAllowWhileIdle(
                                    AlarmManager.RTC_WAKEUP,
                                    medication.calculateNextDose().timeInMillis,
                                    alarmIntent
                                )
                            } else {
                                alarmManager?.set(
                                    AlarmManager.RTC_WAKEUP,
                                    medication.calculateNextDose().timeInMillis,
                                    alarmIntent
                                )
                            }

                        }
                    }
                    MedicationDB.getInstance(context).medicationDao()
                        .updateMedications(*medications.toTypedArray())
                }
                NOTIFY_ACTION -> {
                    //Handle alarm
                    val medication =
                        MedicationDB.getInstance(context).medicationDao()
                            .get(intent.getLongExtra(context.getString(R.string.med_id_key), -1))

                    medication.updateStartsToFuture()
                    alarmIntent =
                        Intent(context, AlarmReceiver::class.java).let { innerIntent ->
                            innerIntent.action = NOTIFY_ACTION
                            innerIntent.putExtra(context.getString(R.string.med_id_key), medication.id)
                            PendingIntent.getBroadcast(
                                context,
                                medication.id.toInt(),
                                innerIntent,
                                0
                            )
                        }

                    val notificationDose = medication.calculateNextDose()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager?.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            notificationDose.timeInMillis,
                            alarmIntent
                        )
                    } else {
                        alarmManager?.set(
                            AlarmManager.RTC_WAKEUP,
                            notificationDose.timeInMillis,
                            alarmIntent
                        )
                    }

                    if (!medication.closestDoseAlreadyTaken()) {
                        buildAndShowNotification(context, medication)
                    }
                }
                TOOK_MED_ACTION -> {
                    /*
                    TODO:
                     -Add feedback for user
                     -Check if med is as-needed
                     -Check for med already taken 
                     -Update views if app is open... or only allow if app is closed?... Hide while app is open?
                    */
                    val medication = MedicationDB.getInstance(context).medicationDao().get(intent.getLongExtra(context.getString(R.string.med_id_key), -1L))
                    val takenDose = DoseRecord(System.currentTimeMillis(), medication.calculateClosestDose().timeInMillis)
                    medication.addNewTakenDose(takenDose)
                    MedicationDB.getInstance(context).medicationDao().updateMedications(medication)
                }
            }
        }
    }
}
