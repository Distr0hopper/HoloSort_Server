package cerg.mnv.view

import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.text.Editable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import cerg.mnv.R
import cerg.mnv.model.InputChangedListener
import networking.ClientListener
import org.json.JSONObject
import android.widget.Toast
import android.view.View.OnFocusChangeListener
import android.view.WindowManager
import org.json.JSONArray


class ConfigActivity : AbstractServiceView() {
    private var statusText: TextView? = null
    private var deviceId: EditText? = null
    private var calibrationButton: Button? = null
    private var arithmeticButton: Button? = null

    private val defaultClientListener = object : ClientListener {
        override fun onClientMessage(message: String) {
            val jsonObject = JSONObject(message)
            if (!this@ConfigActivity.isNetworkingActive()) {
                this@ConfigActivity.updateStatusText(jsonObject.getString("content"))
            }
        }

        override fun onServerDisconnect() {
            this@ConfigActivity.updateStatusText(getString(R.string.disconnected))
            this@ConfigActivity.stopAllSoundActivities()
        }

        override fun onServerMessage(message: String) {
            val ret = this@ConfigActivity.processServerMessage(message)
            if (ret is Boolean) {
                //val rememberedDeviceId = this@ConfigActivity.getPreference(PREFERENCES_DEVICE_ID) ?: return
                runOnUiThread { this@ConfigActivity.deviceId?.setText("frontend") }
            }
            val json: JSONObject?
            json = JSONObject(message)
            if (json.get("type") == "expDataHMD" && message.isNotEmpty()) {
                //val calibration = json.getBoolean("calibration")
                val training = json.getBoolean("training")
                this@ConfigActivity.setPreference("Training", training.toString())
                val interruptionTask = json.getString("interruptionTask") as String
                this@ConfigActivity.setPreference("interruptionTask", interruptionTask)
                println(json);
//                println(calibration)
//                if (calibration) {
//                    this@ConfigActivity.startActivity(Intent(this@ConfigActivity, CalibrationActivity::class.java))
//                } else {
                    if (interruptionTask == "alarm") {
                        this@ConfigActivity.startActivity(Intent(this@ConfigActivity, AlarmActivity::class.java))
                    } else if (interruptionTask == "arithmetic") {
                        // Start the arithmetic task
                        this@ConfigActivity.startActivity(Intent(this@ConfigActivity, ArithmeticActivity::class.java))
                    }
            //    }
            }
        }

        override fun onServerConnect() {
            this@ConfigActivity.updateStatusText(getString(R.string.connected))
            this@ConfigActivity.rememberConnectionPreferences()
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.initPreferences()
        this.addClientListener(this.defaultClientListener)

        setContentView(R.layout.config)

        this.statusText = this.findViewById(R.id.statusText) as TextView
        val serverAddressInput = findViewById(R.id.serverAddress) as EditText
        val rememberedServerId = this.getPreference(PREFERENCES_SERVER_OR_IP) ?: ""

        if (rememberedServerId !== "") {
            serverAddressInput.setText(rememberedServerId)
        } else {
            serverAddressInput.setText("10.107.156.10")
        }
        serverAddressInput.addTextChangedListener(object : InputChangedListener() {
            override fun afterTextChanged(s: Editable?) {
                if (!this@ConfigActivity.setServerOrIp(s.toString()))
                    serverAddressInput.error = getString(R.string.invalid_server_or_ip)
            }
        })
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        this.deviceId = findViewById(R.id.deviceId) as EditText
        //val rememberedDeviceId = this.getPreference(PREFERENCES_DEVICE_ID) ?: ""


        this.deviceId?.setText("frontend")
        this.deviceId?.addTextChangedListener(object : InputChangedListener() {
            override fun afterTextChanged(s: Editable?) {
                this@ConfigActivity.setDeviceId(s.toString())
            }
        })

        this.calibrationButton = findViewById(R.id.calibrationScreenButton) as Button
        this.arithmeticButton = findViewById(R.id.arithmeticScreenButton) as Button

        findViewById(R.id.updateButton).setOnClickListener { this.restartNetworkingService() }
        findViewById(R.id.arithmeticScreenButton).setOnClickListener {
            this.startActivity(Intent(Intent(this, ArithmeticActivity::class.java)))
        }


        this.calibrationButton?.onFocusChangeListener = OnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) {
                calibrationButton?.setBackgroundColor(resources.getColor(R.color.grey))
            } else {
                calibrationButton?.setBackgroundColor(resources.getColor(R.color.darkgrey))
            }
        }
        this.arithmeticButton?.onFocusChangeListener = OnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) {
                arithmeticButton?.setBackgroundColor(resources.getColor(R.color.grey))
            } else {
                arithmeticButton?.setBackgroundColor(resources.getColor(R.color.darkgrey))
            }
        }

        this@ConfigActivity.setPreference("Training", "true")

    }

    private fun updateStatusText(text: String?, delay: Long = 1000L) {
        if (text.isNullOrBlank()) return

        try {
            if (delay > 0) Thread.sleep(delay)
            runOnUiThread { this@ConfigActivity.statusText?.text = text }
        } catch (exception: Exception) {
        }
    }

    override fun onStart() {
        super.onStart()

        this.addClientListener(this.defaultClientListener)
        this.bindAndStartNetworkinService()

        if (this.isNetworkingActive()) {
            this@ConfigActivity.updateStatusText(getString(R.string.connected))
            this.checkForSoundDownloads()
        }
    }

    override fun onStop() {
        super.onStop()

        this.stopCheckingForSoundDownloads()

        this.removeClientListener(this.defaultClientListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        this.unbindNetworkinService()
    }

}
