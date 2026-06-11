package com.example.volumemonitor.core.volume.memo

import android.util.Log
import com.example.volumemonitor.core.Constants
import com.example.volumemonitor.core.model.DeviceCommand
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.volume.mode.CommandSender
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VolumeMemoManagerTest {

    private val mockDelegate: CommandSender = mockk(relaxed = true)
    private val mockSettingsRepo: SettingsRepository = mockk(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher)

    private fun createManager(): VolumeMemoManager {
        return VolumeMemoManager(
            delegate = mockDelegate,
            settingsRepository = mockSettingsRepo,
            scope = testScope
        )
    }

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
    fun `single SetVolume sends SetVolumeMemo after debounce`() = runTest(testDispatcher) {
        every { mockDelegate.send(any<DeviceCommand>()) } just runs
        every { mockSettingsRepo.getLastMemoVolume() } returns -1
        every { mockSettingsRepo.saveLastMemoVolume(any()) } just runs

        val manager = createManager()
        manager.send(DeviceCommand.SetVolume(100))

        verify(exactly = 1) { mockDelegate.send(DeviceCommand.SetVolume(100)) }
        verify(exactly = 0) { mockDelegate.send(DeviceCommand.SetVolumeMemo(any())) }

        advanceTimeBy(Constants.MEMO_DEBOUNCE_MS)

        verify(exactly = 1) { mockDelegate.send(DeviceCommand.SetVolumeMemo(100)) }
        verify(exactly = 1) { mockSettingsRepo.saveLastMemoVolume(100) }
    }

    @Test
    fun `second SetVolume cancels first debounce timer`() = runTest(testDispatcher) {
        every { mockDelegate.send(any<DeviceCommand>()) } just runs
        every { mockSettingsRepo.getLastMemoVolume() } returns -1
        every { mockSettingsRepo.saveLastMemoVolume(any()) } just runs

        val manager = createManager()
        manager.send(DeviceCommand.SetVolume(100))
        advanceTimeBy(3_000)
        manager.send(DeviceCommand.SetVolume(200))

        verify(exactly = 0) { mockDelegate.send(DeviceCommand.SetVolumeMemo(100)) }

        advanceTimeBy(Constants.MEMO_DEBOUNCE_MS)

        verify(exactly = 0) { mockDelegate.send(DeviceCommand.SetVolumeMemo(100)) }
        verify(exactly = 1) { mockDelegate.send(DeviceCommand.SetVolumeMemo(200)) }
        verify(exactly = 1) { mockSettingsRepo.saveLastMemoVolume(200) }
    }

    @Test
    fun `SetVolumeMemo not sent when value equals lastMemoVolume`() = runTest(testDispatcher) {
        every { mockDelegate.send(any<DeviceCommand>()) } just runs
        every { mockSettingsRepo.getLastMemoVolume() } returns 100
        every { mockSettingsRepo.saveLastMemoVolume(any()) } just runs

        val manager = createManager()
        manager.send(DeviceCommand.SetVolume(100))
        advanceTimeBy(Constants.MEMO_DEBOUNCE_MS)

        verify(exactly = 1) { mockDelegate.send(DeviceCommand.SetVolume(100)) }
        verify(exactly = 0) { mockDelegate.send(DeviceCommand.SetVolumeMemo(any())) }
        verify(exactly = 0) { mockSettingsRepo.saveLastMemoVolume(any()) }
    }

    @Test
    fun `SetVolumeMemo sent when value changed from lastMemoVolume`() = runTest(testDispatcher) {
        every { mockDelegate.send(any<DeviceCommand>()) } just runs
        every { mockSettingsRepo.getLastMemoVolume() } returns 100
        every { mockSettingsRepo.saveLastMemoVolume(any()) } just runs

        val manager = createManager()
        manager.send(DeviceCommand.SetVolume(200))
        advanceTimeBy(Constants.MEMO_DEBOUNCE_MS)

        verify(exactly = 1) { mockDelegate.send(DeviceCommand.SetVolume(200)) }
        verify(exactly = 1) { mockDelegate.send(DeviceCommand.SetVolumeMemo(200)) }
        verify(exactly = 1) { mockSettingsRepo.saveLastMemoVolume(200) }
    }

    @Test
    fun `SetBassLevel does not trigger SetVolumeMemo`() = runTest(testDispatcher) {
        every { mockDelegate.send(any<DeviceCommand>()) } just runs
        every { mockSettingsRepo.getLastMemoVolume() } returns -1
        every { mockSettingsRepo.saveLastMemoVolume(any()) } just runs

        val manager = createManager()
        manager.send(DeviceCommand.SetBassLevel(50))
        advanceTimeBy(Constants.MEMO_DEBOUNCE_MS)

        verify(exactly = 1) { mockDelegate.send(DeviceCommand.SetBassLevel(50)) }
        verify(exactly = 0) { mockDelegate.send(DeviceCommand.SetVolumeMemo(any())) }
    }

    @Test
    fun `SetBassLevel does not cancel pending SetVolumeMemo`() = runTest(testDispatcher) {
        every { mockDelegate.send(any<DeviceCommand>()) } just runs
        every { mockSettingsRepo.getLastMemoVolume() } returns -1
        every { mockSettingsRepo.saveLastMemoVolume(any()) } just runs

        val manager = createManager()
        manager.send(DeviceCommand.SetVolume(100))
        advanceTimeBy(3_000)
        manager.send(DeviceCommand.SetBassLevel(50))
        advanceTimeBy(Constants.MEMO_DEBOUNCE_MS)

        verify(exactly = 1) { mockDelegate.send(DeviceCommand.SetVolumeMemo(100)) }
    }

    @Test
    fun `cancelPending prevents SetVolumeMemo`() = runTest(testDispatcher) {
        every { mockDelegate.send(any<DeviceCommand>()) } just runs
        every { mockSettingsRepo.getLastMemoVolume() } returns -1

        val manager = createManager()
        manager.send(DeviceCommand.SetVolume(100))
        advanceTimeBy(5_000)
        manager.cancelPending()
        advanceTimeBy(Constants.MEMO_DEBOUNCE_MS)

        verify(exactly = 0) { mockDelegate.send(DeviceCommand.SetVolumeMemo(any())) }
    }

    @Test
    fun `ChangePreset and GetPreset pass through without debounce`() = runTest(testDispatcher) {
        every { mockDelegate.send(any<DeviceCommand>()) } just runs
        every { mockSettingsRepo.getLastMemoVolume() } returns -1

        val manager = createManager()
        manager.send(DeviceCommand.ChangePreset)
        manager.send(DeviceCommand.GetPreset)
        advanceTimeBy(Constants.MEMO_DEBOUNCE_MS)

        verify(exactly = 1) { mockDelegate.send(DeviceCommand.ChangePreset) }
        verify(exactly = 1) { mockDelegate.send(DeviceCommand.GetPreset) }
        verify(exactly = 0) { mockDelegate.send(DeviceCommand.SetVolumeMemo(any())) }
    }
}
