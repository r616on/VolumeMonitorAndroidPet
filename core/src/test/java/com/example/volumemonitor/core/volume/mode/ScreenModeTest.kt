package com.example.volumemonitor.core.volume.mode

import android.content.Context
import android.util.Log
import com.example.volumemonitor.core.Constants
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.model.DeviceCommand
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.repository.SettingsRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenModeTest {

    private val mockContext: Context = mockk(relaxed = true)
    private val mockSettingsRepo: SettingsRepository = mockk(relaxed = true)
    private val mockCommandSender: CommandSender = mockk(relaxed = true)
    private val appEventFlow = MutableSharedFlow<AppEvent>(extraBufferCapacity = 16)
    private val testDispatcher = UnconfinedTestDispatcher()

    private fun createMode() = ScreenMode(
        context = mockContext,
        commandSender = mockCommandSender,
        settingsRepository = mockSettingsRepo,
        appEvents = appEventFlow,
        dispatcher = testDispatcher
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `start restores saved volume and emits ModeStateChanged`() = runTest(testDispatcher) {
        every { mockSettingsRepo.getScreenCurrentVolume() } returns 7

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        val state = mode.state.value
        assertEquals(7, state.currentVolume)
        assertEquals(Constants.SCREEN_MAX_POSITION, state.maxVolume)
        assertEquals("экран", state.displayLabel)
    }

    @Test
    fun `start clamps restored volume to SCREEN_MAX_POSITION`() = runTest(testDispatcher) {
        every { mockSettingsRepo.getScreenCurrentVolume() } returns 99

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        assertEquals(Constants.SCREEN_MAX_POSITION, mode.state.value.currentVolume)
    }

    @Test
    fun `ScreenVolumeChanged event sends volume to commandSender`() = runTest(testDispatcher) {
        every { mockSettingsRepo.getScreenCurrentVolume() } returns 0
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        appEventFlow.emit(AppEvent.ScreenVolumeChanged(14))
        advanceUntilIdle()

        verify(atLeast = 1) { mockCommandSender.send(any<DeviceCommand>()) }
        assertEquals(14, mode.state.value.currentVolume)
        assertEquals(Constants.SCREEN_MAX_POSITION, mode.state.value.maxVolume)
    }

    @Test
    fun `ScreenVolumeChanged with zero sends zero to port`() = runTest(testDispatcher) {
        every { mockSettingsRepo.getScreenCurrentVolume() } returns 10
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        appEventFlow.emit(AppEvent.ScreenVolumeChanged(0))
        advanceUntilIdle()

        verify { mockCommandSender.send(DeviceCommand.SetVolume(0)) }
        assertEquals(0, mode.state.value.currentVolume)
    }

    @Test
    fun `duplicate volume value does not trigger redundant send`() = runTest(testDispatcher) {
        every { mockSettingsRepo.getScreenCurrentVolume() } returns 5
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        appEventFlow.emit(AppEvent.ScreenVolumeChanged(5))
        advanceUntilIdle()

        assertEquals(5, mode.state.value.currentVolume)
        verify(exactly = 0) { mockCommandSender.send(any<DeviceCommand>()) }
    }

    @Test
    fun `out-of-range volume is clamped`() = runTest(testDispatcher) {
        every { mockSettingsRepo.getScreenCurrentVolume() } returns 0
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        appEventFlow.emit(AppEvent.ScreenVolumeChanged(50))
        advanceUntilIdle()

        assertEquals(Constants.SCREEN_MAX_POSITION, mode.state.value.currentVolume)
    }

    @Test
    fun `negative volume is clamped to zero`() = runTest(testDispatcher) {
        every { mockSettingsRepo.getScreenCurrentVolume() } returns 5
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        appEventFlow.emit(AppEvent.ScreenVolumeChanged(-1))
        advanceUntilIdle()

        assertEquals(0, mode.state.value.currentVolume)
    }

    @Test
    fun `onUsbConnected syncs volume and emits state`() = runTest(testDispatcher) {
        every { mockSettingsRepo.getScreenCurrentVolume() } returns 3
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        mode.onUsbConnected()
        advanceUntilIdle()

        verify(atLeast = 1) { mockCommandSender.send(any<DeviceCommand>()) }
        assertEquals(3, mode.state.value.currentVolume)
    }

    @Test
    fun `stop cancels modeScope`() {
        val mode = createMode()
        mode.start()

        mode.stop()

        val state = mode.state.value
        assertTrue(state.maxVolume == Constants.SCREEN_MAX_POSITION)
    }

    @Test
    fun `modeId is SCREEN`() {
        val mode = createMode()

        assertEquals(VolumeControlMode.SCREEN, mode.modeId)
        assertEquals("Управление с экрана", mode.displayName)
    }

    @Test
    fun `multiple volume changes update state sequentially`() = runTest(testDispatcher) {
        every { mockSettingsRepo.getScreenCurrentVolume() } returns 0
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        appEventFlow.emit(AppEvent.ScreenVolumeChanged(3))
        advanceUntilIdle()
        assertEquals(3, mode.state.value.currentVolume)

        appEventFlow.emit(AppEvent.ScreenVolumeChanged(7))
        advanceUntilIdle()
        assertEquals(7, mode.state.value.currentVolume)

        appEventFlow.emit(AppEvent.ScreenVolumeChanged(14))
        advanceUntilIdle()
        assertEquals(14, mode.state.value.currentVolume)

        verify(exactly = 3) { mockCommandSender.send(any<DeviceCommand>()) }
    }
}
