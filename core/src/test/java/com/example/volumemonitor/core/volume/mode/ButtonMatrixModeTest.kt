package com.example.volumemonitor.core.volume.mode

import android.content.Context
import android.util.Log
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
class ButtonMatrixModeTest {

    private val mockContext: Context = mockk(relaxed = true)
    private val mockSettingsRepo: SettingsRepository = mockk(relaxed = true)
    private val mockCommandSender: CommandSender = mockk(relaxed = true)
    private val appEventFlow = MutableSharedFlow<AppEvent>(extraBufferCapacity = 16)
    private val testDispatcher = UnconfinedTestDispatcher()

    private fun createMode() = ButtonMatrixMode(
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
    fun `start sets static ModeState with zero volume`() = runTest(testDispatcher) {
        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        val state = mode.state.value
        assertEquals(0, state.currentVolume)
        assertEquals(0, state.maxVolume)
        assertEquals("матрица", state.displayLabel)
    }

    @Test
    fun `MatrixButtonDown event sends ButtonDown command`() = runTest(testDispatcher) {
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        appEventFlow.emit(AppEvent.MatrixButtonDown(3))
        advanceUntilIdle()

        verify(exactly = 1) { mockCommandSender.send(DeviceCommand.ButtonDown(3)) }
    }

    @Test
    fun `MatrixButtonUp event sends ButtonUp command`() = runTest(testDispatcher) {
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        appEventFlow.emit(AppEvent.MatrixButtonUp(6))
        advanceUntilIdle()

        verify(exactly = 1) { mockCommandSender.send(DeviceCommand.ButtonUp(6)) }
    }

    @Test
    fun `multiple button events send corresponding commands`() = runTest(testDispatcher) {
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        appEventFlow.emit(AppEvent.MatrixButtonDown(1))
        advanceUntilIdle()
        appEventFlow.emit(AppEvent.MatrixButtonUp(1))
        advanceUntilIdle()
        appEventFlow.emit(AppEvent.MatrixButtonDown(5))
        advanceUntilIdle()
        appEventFlow.emit(AppEvent.MatrixButtonUp(5))
        advanceUntilIdle()

        verify(exactly = 1) { mockCommandSender.send(DeviceCommand.ButtonDown(1)) }
        verify(exactly = 1) { mockCommandSender.send(DeviceCommand.ButtonUp(1)) }
        verify(exactly = 1) { mockCommandSender.send(DeviceCommand.ButtonDown(5)) }
        verify(exactly = 1) { mockCommandSender.send(DeviceCommand.ButtonUp(5)) }
    }

    @Test
    fun `unrelated events are ignored`() = runTest(testDispatcher) {
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        // Эмитим события, не связанные с матрицей
        appEventFlow.emit(AppEvent.VolumeChanged(5, 85))
        advanceUntilIdle()
        appEventFlow.emit(AppEvent.ButtonPressed(com.example.volumemonitor.core.model.ButtonAction.VOLUME_UP))
        advanceUntilIdle()
        appEventFlow.emit(AppEvent.ScreenVolumeChanged(7))
        advanceUntilIdle()

        // commandSender не должен вызываться
        verify(exactly = 0) { mockCommandSender.send(any<DeviceCommand>()) }
    }

    @Test
    fun `modeId is BUTTON_MATRIX`() {
        val mode = createMode()

        assertEquals(VolumeControlMode.BUTTON_MATRIX, mode.modeId)
        assertEquals("Матрица кнопок", mode.displayName)
    }

    @Test
    fun `stop cancels modeScope`() {
        val mode = createMode()
        mode.start()

        mode.stop()

        val state = mode.state.value
        assertEquals(0, state.currentVolume)
        assertEquals(0, state.maxVolume)
    }

    @Test
    fun `MatrixButtonDown with button 1 sends correct command`() = runTest(testDispatcher) {
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        appEventFlow.emit(AppEvent.MatrixButtonDown(1))
        advanceUntilIdle()

        verify(exactly = 1) { mockCommandSender.send(DeviceCommand.ButtonDown(1)) }
    }

    @Test
    fun `MatrixButtonUp with button 4 sends correct command`() = runTest(testDispatcher) {
        every { mockCommandSender.send(any<DeviceCommand>()) } just runs

        val mode = createMode()
        mode.start()
        advanceUntilIdle()

        appEventFlow.emit(AppEvent.MatrixButtonUp(4))
        advanceUntilIdle()

        verify(exactly = 1) { mockCommandSender.send(DeviceCommand.ButtonUp(4)) }
    }
}
