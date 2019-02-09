package com.slash.batterychargelimit.activities

import android.content.*
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.util.Log
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import com.slash.batterychargelimit.Constants.AUTO_RESET_STATS
import com.slash.batterychargelimit.Constants.DISABLE_CHARGE_NOW
import com.slash.batterychargelimit.Constants.ENABLE
import com.slash.batterychargelimit.Constants.LIMIT
import com.slash.batterychargelimit.Constants.MIN
import com.slash.batterychargelimit.Constants.NOTIFICATION_SOUND
import com.slash.batterychargelimit.Constants.SETTINGS
import com.slash.batterychargelimit.Constants.SETTINGS_VERSION
import com.slash.batterychargelimit.ForegroundService
import com.slash.batterychargelimit.R
import com.slash.batterychargelimit.Utils
import com.slash.batterychargelimit.fragments.AboutFragment
import com.slash.batterychargelimit.receivers.EnableWidgetIntentReceiver
import com.slash.batterychargelimit.settings.CtrlFileHelper
import com.slash.batterychargelimit.settings.SettingsFragment
import eu.chainfire.libsuperuser.Shell


class MainActivity : AppCompatActivity() {
    private val minPicker by lazy(LazyThreadSafetyMode.NONE) {findViewById(R.id.min_picker) as NumberPicker}
    private val minText by lazy(LazyThreadSafetyMode.NONE) {findViewById(R.id.min_text) as TextView}
    private val maxPicker by lazy(LazyThreadSafetyMode.NONE) {findViewById(R.id.max_picker) as NumberPicker}
    private val maxText by lazy(LazyThreadSafetyMode.NONE) {findViewById(R.id.max_text) as TextView}
    private val settings by lazy(LazyThreadSafetyMode.NONE) {getSharedPreferences(SETTINGS, 0)}
    private val statusText by lazy(LazyThreadSafetyMode.NONE) {findViewById(R.id.status) as TextView}
    private val batteryInfo by lazy(LazyThreadSafetyMode.NONE) {findViewById(R.id.battery_info) as TextView}
    private val enableSwitch by lazy(LazyThreadSafetyMode.NONE) {findViewById(R.id.enable_switch) as Switch}
    private val disableChargeSwitch by lazy(LazyThreadSafetyMode.NONE) {findViewById(R.id.disable_charge_switch) as Switch}
    private var initComplete = false
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Utils.setTheme(this)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        // Exit immediately if no root support
        if (!Shell.SU.available()) {
            Toast.makeText(this, R.string.root_denied, Toast.LENGTH_SHORT)
            AlertDialog.Builder(this@MainActivity)
                    .setMessage(R.string.root_denied)
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok) { _, _ -> finish() }.create().show()
            return
        }

        val prefs = Utils.getPrefs(baseContext)
        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                SettingsFragment.KEY_TEMP_FAHRENHEIT -> updateBatteryInfo(baseContext.registerReceiver(null,
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        if (!prefs.contains(SettingsFragment.KEY_CONTROL_FILE)) {
            CtrlFileHelper.validateFiles(this, Runnable {
                var found = false
                for (cf in Utils.getCtrlFiles(this@MainActivity)) {
                    if (cf.isValid) {
                        Utils.setCtrlFile(this@MainActivity, cf)
                        found = true
                        break
                    }
                }
                if (!found) {
                    AlertDialog.Builder(this@MainActivity)
                            .setMessage(R.string.device_not_supported)
                            .setCancelable(false)
                            .setPositiveButton(R.string.ok) { _, _ -> finish() }.create().show()
                }
            })
        }
        if (!prefs.getBoolean(getString(R.string.previously_started), false)) {
            // whitelist App for Doze Mode
            Utils.suShell.addCommand("dumpsys deviceidle whitelist +com.slash.batterychargelimit",
                    0) { _, _, _ ->
                Utils.getPrefs(baseContext)
                        .edit().putBoolean(getString(R.string.previously_started), true).apply()
            }
        }

        val settingsVersion = prefs.getInt(SETTINGS_VERSION, 0)
        var versionCode = 0
        try {
            versionCode = packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            Log.wtf(TAG, e)
        }

        if (settingsVersion < versionCode) {
            when (settingsVersion) {
                0, 1, 2, 3, 4 -> {
                    if (settings.contains("limit_reached")) {
                        settings.edit().remove("limit_reached").apply()
                    }
                    if (settings.contains("recharge_threshold")) {
                        val limit = settings.getInt(LIMIT, 80)
                        val diff = settings.getInt("recharge_threshold", limit - 2)
                        settings.edit().putInt(MIN, limit - diff).remove("recharge_threshold").apply()
                    }
                }
                5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 -> if (settings.contains("recharge_threshold")) {
                    val limit = settings.getInt(LIMIT, 80)
                    val diff = settings.getInt("recharge_threshold", limit - 2)
                    settings.edit().putInt(MIN, limit - diff).remove("recharge_threshold").apply()
                }
            }// settings upgrade for future version(s)
            // update the settings version
            prefs.edit().putInt(SETTINGS_VERSION, versionCode).apply()
        }

        val is_enabled = settings.getBoolean(ENABLE, false)

        if (is_enabled && Utils.isPhonePluggedIn(this)) {
            this.startService(Intent(this, ForegroundService::class.java))
        }

        val resetBatteryStats_Button = findViewById(R.id.reset_battery_stats) as Button
        val autoResetSwitch = findViewById(R.id.auto_stats_reset) as CheckBox
        val notificationSound = findViewById(R.id.notification_sound) as CheckBox

        autoResetSwitch.isChecked = settings.getBoolean(AUTO_RESET_STATS, false)
        notificationSound.isChecked = settings.getBoolean(NOTIFICATION_SOUND, false)
        maxPicker.minValue = 40
        maxPicker.maxValue = 100
        minPicker.minValue = 0

        enableSwitch.setOnCheckedChangeListener(switchListener)
        disableChargeSwitch.setOnCheckedChangeListener(switchListener)
        maxPicker.setOnValueChangedListener { _, _, max ->
            Utils.setLimit(max, settings)
            maxText.text = getString(R.string.limit, max)
            val min = settings.getInt(MIN, max - 2)
            minPicker.maxValue = max
            minPicker.value = min
            updateMinText(min)
            if (!ForegroundService.isRunning) {
                Utils.startService(this)
            }
        }

        minPicker.setOnValueChangedListener { _, _, min ->
            settings.edit().putInt(MIN, min).apply()
            updateMinText(min)
        }
        resetBatteryStats_Button.setOnClickListener { Utils.resetBatteryStats(this@MainActivity) }
        autoResetSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.edit().putBoolean(AUTO_RESET_STATS, isChecked).apply() }
        notificationSound.setOnCheckedChangeListener { _, isChecked ->
            settings.edit().putBoolean(NOTIFICATION_SOUND, isChecked).apply() }

        val statusCTRLData = findViewById(R.id.status_ctrl_data) as TextView
        statusCTRLData.text = Utils.getCtrlFileData(this) + ", " +
                Utils.getCtrlEnabledData(this) + ", " +
                Utils.getCtrlDisabledData(this)
        //The onCreate() process was not stopped via return, UI elements should be available
        initComplete = true
    }

    //OnCheckedChangeListener for Switch elements
    private val switchListener = object : CompoundButton.OnCheckedChangeListener {
        internal val context: Context = this@MainActivity
        override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
            when(buttonView.id) {
                R.id.enable_switch -> {
                    settings.edit().putBoolean(ENABLE, isChecked).apply()
                    if (isChecked) {
                        Utils.startService(context)
                        disableChargeSwitch.isClickable = false
                        disableChargeSwitch.setTextColor(getColorFromAttr(R.attr.secondaryText, this@MainActivity))
                    } else {
                        Utils.stopService(context)
                        disableChargeSwitch.isClickable = true
                        disableChargeSwitch.setTextColor(getColorFromAttr(R.attr.primaryText, this@MainActivity))
                    }
                    EnableWidgetIntentReceiver.updateWidget(context, isChecked)
                }
                R.id.disable_charge_switch -> {
                    if (isChecked) {
                        Utils.changeState(this@MainActivity, Utils.CHARGE_OFF)
                        enableSwitch.isClickable = false
                        enableSwitch.setTextColor(getColorFromAttr(R.attr.secondaryText, this@MainActivity))
                        settings.edit().putBoolean(DISABLE_CHARGE_NOW, true).apply()
                    } else {
                        Utils.changeState(this@MainActivity, Utils.CHARGE_ON)
                        enableSwitch.isClickable = true
                        enableSwitch.setTextColor(getColorFromAttr(R.attr.primaryText, this@MainActivity))
                        settings.edit().putBoolean(DISABLE_CHARGE_NOW, false).apply()
                    }
                }
            }
        }
    }

    fun getColorFromAttr(attr: Int, context: Context): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        val color = typedValue.data
        return color
    }

    //to update battery status on UI
    private val charging = object : BroadcastReceiver() {
        private var previousStatus = BatteryManager.BATTERY_STATUS_UNKNOWN

        override fun onReceive(context: Context, intent: Intent) {
            val currentStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            if (currentStatus != previousStatus) {
                previousStatus = currentStatus
                when (currentStatus) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> {
                        statusText.setText(R.string.charging)
                        statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.darkGreen))
                    }
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> {
                        statusText.setText(R.string.discharging)
                        statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.orange))
                    }
                    BatteryManager.BATTERY_STATUS_FULL -> {
                        statusText.setText(R.string.full)
                        statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.darkGreen))
                    }
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> {
                        statusText.setText(R.string.not_charging)
                        statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.orange))
                    }
                    else -> {
                        statusText.setText(R.string.unknown)
                        statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.red))
                    }
                }
            }
            updateBatteryInfo(intent)
        }
    }

    private fun updateBatteryInfo(intent: Intent) {
        batteryInfo.text = " (" + Utils.getBatteryInfo(this, intent,
                Utils.getPrefs(this)
                        .getBoolean(SettingsFragment.KEY_TEMP_FAHRENHEIT, false)) + ")"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.about -> if (!AboutFragment.aboutVisible()) {
                supportActionBar!!.title = getString(R.string.about)
                fragmentManager.popBackStack()
                fragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, AboutFragment())
                        .addToBackStack(null).commit()
            }
            R.id.action_settings -> if (!SettingsFragment.settingsVisible()) {
                supportActionBar!!.title = getString(R.string.action_settings)
                CtrlFileHelper.validateFiles(this, Runnable {
                    fragmentManager.popBackStack()
                    fragmentManager.beginTransaction()
                            .replace(R.id.fragment_container, SettingsFragment())
                            .addToBackStack(null).commit()
                })
            }
        }
        return true
    }

    override fun onBackPressed() {
        super.onBackPressed()
        supportActionBar!!.title = getString(R.string.app_name)
    }

    public override fun onStop() {
        if (initComplete) {
            unregisterReceiver(charging)
        }
        super.onStop()
    }

    public override fun onStart() {
        super.onStart()
        if (initComplete) {
            registerReceiver(charging, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            // the limits could have been changed by an Intent, so update the UI here
            updateUi()
        }
    }

    override fun onDestroy() {
        Utils.getPrefs(baseContext)
                .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        // technically not necessary, but it prevents inlining of this required field
        // see end of https://developer.android.com/guide/topics/ui/settings.html#Listening
        preferenceChangeListener = null
        super.onDestroy()
    }

    private fun updateMinText(min: Int) {
        when (min) {
            0 -> minText.setText(R.string.no_recharge)
            else -> minText.text = getString(R.string.recharge_below, min)
        }
    }

    private fun updateUi() {
        enableSwitch.isChecked = settings.getBoolean(ENABLE, false)
        disableChargeSwitch.isChecked = settings.getBoolean(DISABLE_CHARGE_NOW, false)
        val max = settings.getInt(LIMIT, 80)
        val min = settings.getInt(MIN, max - 2)
        maxPicker.value = max
        maxText.text = getString(R.string.limit, max)
        minPicker.maxValue = max
        minPicker.value = min
        updateMinText(min)
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }
}
