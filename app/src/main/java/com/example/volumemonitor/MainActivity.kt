package com.example.volumemonitor

import android.Manifest
import android.app.PendingIntent
import android.os.Build
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.volumemonitor.core.VolumeMonitorService
import com.example.volumemonitor.ui.LogFragment
import com.example.volumemonitor.ui.MainFragment
import com.example.volumemonitor.ui.UsbSettingsFragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"

    // ── View ──
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    // ── Сервис (публичный для доступа из фрагментов) ──
    var volumeService: VolumeMonitorService? = null
        private set

    private var isBound = false

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "Сервис подключен")
            val binder = service as VolumeMonitorService.LocalBinder
            volumeService = binder.getService()
            isBound = true

            val intent = Intent(this@MainActivity, MainActivity::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(
                this@MainActivity, 0, intent, flags
            )
            volumeService?.setNotificationPendingIntent(pendingIntent)

            Toast.makeText(this@MainActivity, "Сервис запущен", Toast.LENGTH_SHORT).show()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "Сервис отключен")
            isBound = false
            volumeService = null
        }
    }

    // ── Жизненный цикл ──

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "=== MainActivity onCreate ===")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        val adapter = TabsAdapter(this)
        viewPager.adapter = adapter
        viewPager.isUserInputEnabled = false  // отключаем свайп — переход только по клику на таб
        viewPager.offscreenPageLimit = 2  // держим все 3 фрагмента в памяти

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Главная"
                1 -> "Лог"
                2 -> "USB"
                else -> ""
            }
        }.attach()

        startAndBindService()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "MainActivity onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    // ── Сервис ──

    private fun startAndBindService() {
        val serviceIntent = Intent(this, VolumeMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        Log.d(TAG, "Сервис запущен и привязан")
    }

    // ── Адаптер табов ──

    private class TabsAdapter(activity: MainActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> MainFragment()
            1 -> LogFragment()
            2 -> UsbSettingsFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}