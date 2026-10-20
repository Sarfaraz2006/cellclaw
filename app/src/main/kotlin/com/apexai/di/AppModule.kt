package com.apexai.di

import android.content.Context
import androidx.room.Room
import com.apexai.memory.MemoryDb
import com.apexai.memory.MemoryFactDao
import com.apexai.memory.MessageDao
import com.apexai.provider.AnthropicProvider
import com.apexai.provider.CustomOpenAIProvider
import com.apexai.provider.GeminiProvider
import com.apexai.provider.OpenAIProvider
import com.apexai.provider.OpenRouterProvider
import com.apexai.tools.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMemoryDb(@ApplicationContext context: Context): MemoryDb {
        return Room.databaseBuilder(
            context,
            MemoryDb::class.java,
            "apexai_memory"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideMessageDao(db: MemoryDb): MessageDao = db.messageDao()

    @Provides
    fun provideMemoryFactDao(db: MemoryDb): MemoryFactDao = db.memoryFactDao()

    @Provides
    fun provideScheduledTaskDao(db: MemoryDb): com.apexai.scheduler.ScheduledTaskDao = db.scheduledTaskDao()

    @Provides
    @Singleton
    fun provideAnthropicProvider(): AnthropicProvider = AnthropicProvider()

    @Provides
    @Singleton
    fun provideOpenAIProvider(): OpenAIProvider = OpenAIProvider()

    @Provides
    @Singleton
    fun provideGeminiProvider(): GeminiProvider = GeminiProvider()

    @Provides
    @Singleton
    fun provideOpenRouterProvider(): OpenRouterProvider = OpenRouterProvider()

    @Provides
    @Singleton
    fun provideCustomOpenAIProvider(): CustomOpenAIProvider = CustomOpenAIProvider()

    @Provides
    @Singleton
    fun provideToolRegistry(
        smsRead: SmsReadTool,
        smsSend: SmsSendTool,
        phoneCall: PhoneCallTool,
        phoneLog: PhoneLogTool,
        contactsSearch: ContactsSearchTool,
        contactsAdd: ContactsAddTool,
        calendarQuery: CalendarQueryTool,
        calendarCreate: CalendarCreateTool,
        location: LocationTool,
        cameraSnap: CameraSnapTool,
        cameraRecord: CameraRecordTool,
        notification: NotificationTool,
        clipboardRead: ClipboardReadTool,
        clipboardWrite: ClipboardWriteTool,
        fileRead: FileReadTool,
        fileWrite: FileWriteTool,
        fileList: FileListTool,
        scriptExec: ScriptExecTool,
        sensor: SensorTool,
        settings: SettingsTool,
        browserOpen: BrowserOpenTool,
        browserSearch: BrowserSearchTool,
        appLaunch: AppLaunchTool,
        appAutomate: AppAutomateTool,
        screenRead: ScreenReadTool,
        emailSend: EmailSendTool,
        screenCapture: ScreenCaptureTool,
        visionAnalyze: VisionAnalyzeTool,
        notificationListen: NotificationListenTool,
        schedulerTool: SchedulerTool,
        messagingOpen: MessagingOpenTool,
        messagingRead: MessagingReadTool,
        messagingReply: MessagingReplyTool,
        heartbeatContext: HeartbeatContextTool,
        flashlightTool: FlashlightTool,
        volumeTool: VolumeTool,
        mediaControlTool: MediaControlTool,
        systemStatusTool: SystemStatusTool,
        navigationTool: NavigationTool,
        youtubeTool: YouTubeTool
    ): ToolRegistry {
        return ToolRegistry().apply {
            register(
                smsRead, smsSend,
                phoneCall, phoneLog,
                contactsSearch, contactsAdd,
                calendarQuery, calendarCreate,
                location,
                cameraSnap, cameraRecord,
                notification,
                clipboardRead, clipboardWrite,
                fileRead, fileWrite, fileList,
                scriptExec,
                sensor,
                settings,
                browserOpen, browserSearch,
                appLaunch, appAutomate, screenRead,
                emailSend,
                screenCapture, visionAnalyze,
                notificationListen,
                schedulerTool,
                messagingOpen, messagingRead, messagingReply,
                heartbeatContext,
                flashlightTool,
                volumeTool,
                mediaControlTool,
                systemStatusTool,
                navigationTool,
                youtubeTool
            )
        }
    }
}
