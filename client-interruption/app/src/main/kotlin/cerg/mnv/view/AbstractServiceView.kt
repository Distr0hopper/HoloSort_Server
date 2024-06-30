package cerg.mnv.view

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import android.util.Patterns
import android.view.MotionEvent
import android.widget.Toast
import cerg.mnv.model.Context
import cerg.mnv.model.MultiPatientData
import cerg.mnv.service.NetworkingService
import cerg.mnv.utils.SoundDownload
import cerg.mnv.utils.SoundDownloads
import mpmgame.*
import networking.ClientListener
import org.json.JSONException
import org.json.JSONObject
import publisher.JsonMessage
import publisher.MESSAGE_STATUS
import publisher.MESSAGE_TYPE
import transfer.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import cerg.mnv.R
import cerg.mnv.utils.SoundLibrary
import logging.GlobalLogger
import logging.LogFormat
import utils.createJsonForGivenType

const val PREFERENCES_SERVER_OR_IP = "ServerOrIp"
const val PREFERENCES_DEVICE_ID = "DeviceId"

abstract class AbstractServiceView : Activity() {
    companion object {
        private var networkingService: NetworkingService? = null
        private val clientListeners = Collections.newSetFromMap(ConcurrentHashMap<ClientListener, Boolean>())
    }

    private var preferences: SharedPreferences? = null

    init {
        GlobalLogger.app(logFormat = LogFormat.ANDROID)
    }

    fun initPreferences() {
        this.preferences = this.getSharedPreferences("NetworkingServicePreferences", android.content.Context.MODE_PRIVATE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            networkingService = (service as NetworkingService.NetworkingServiceBinder).getService()
            this@AbstractServiceView.startNetworkingService()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            networkingService = null

            this@AbstractServiceView.stopAllSoundActivities()

            // try and restart
            this@AbstractServiceView.bindAndStartNetworkinService()
        }
    }

    private var touchX = 0f

    fun restartNetworkingService() {
        networkingService?.restartService()
    }

    private fun startNetworkingService() {
        val rememberedServerOrIp = this.getPreference(PREFERENCES_SERVER_OR_IP)
        if (rememberedServerOrIp != null) networkingService?.server = rememberedServerOrIp
        //val rememberedDeviceId = this.getPreference(PREFERENCES_DEVICE_ID)
        //if (rememberedDeviceId != null) networkingService?.name = rememberedDeviceId
        networkingService?.name  = "frontend"


        clientListeners.forEach { networkingService?.addClientListener(it) }

        networkingService?.startService(Intent(this.applicationContext, NetworkingService::class.java))
    }

    fun sendMpmGameMessageRequest(mpmGameMessageType: MpmGameMessageTypes) {
        networkingService?.sendMpmGameMessageRequest(mpmGameMessageType)
    }

    fun sendBackEndMessage(string: String) {
        val jsonMsg = createJsonForGivenType(MESSAGE_TYPE.MNV_BACK_END_HANDLER, string)
        networkingService?.sendMessage(JSONObject(jsonMsg))
    }

    fun sendBackEndMessage(jsonObject: JSONObject) {
        val wrapper = JSONObject(createJsonForGivenType(MESSAGE_TYPE.MNV_BACK_END_HANDLER, ""))
        wrapper.put("content", jsonObject)
        networkingService?.sendMessage(wrapper)
    }

    /**
     * Don't forget to expand the if-statements in NetworkingServiceMonitor.kt => onMessage()
     */
    fun sendBackEndMessage(jsonObject: JSONObject, target: String) {
        val wrapper = JSONObject(createJsonForGivenType(MESSAGE_TYPE.MNV_BACK_END_HANDLER, ""))
        wrapper.put("content", jsonObject)
        wrapper.put("target", target)
        networkingService?.sendMessage(wrapper)
    }

    private fun stopNetworkingService() {
        AbstractServiceView.clientListeners.clear()
        try {
            networkingService?.stopService(Intent(this.applicationContext, NetworkingService::class.java))
        } catch (ignored: Exception) {
        }
    }

    fun unbindNetworkinService() {
        this.stopNetworkingService()
        try {
            this.unbindService(this.serviceConnection)
        } catch (ignored: Exception) {
        }
        networkingService = null
    }

    fun bindAndStartNetworkinService() {
        if (networkingService == null) {
            if (!this.bindService(
                            Intent(this.applicationContext, NetworkingService::class.java),
                            this.serviceConnection, android.content.Context.BIND_AUTO_CREATE)) {
                Log.e("AbstractServiceView", "Error: The requested service doesn't " +
                        "exist, or this client isn't allowed access to it.")
            }
        }
        this.startNetworkingService()
    }

    fun isNetworkingActive(): Boolean {
        return networkingService?.isNetworkActive() == true
    }


    override fun onTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                this@AbstractServiceView.touchX = event.x
                return true
            }
            MotionEvent.ACTION_UP -> {
                val deltaX = this@AbstractServiceView.touchX - event.x

                if (Math.abs(deltaX) < 100) return false


                return true
            }
            else -> return false
        }
    }

    fun setServerOrIp(serverOrIp: String): Boolean {
        if (serverOrIp.isBlank()) return true
        if (!Patterns.DOMAIN_NAME.matcher(serverOrIp).matches() && serverOrIp != "localhost") return false
        networkingService?.server = serverOrIp
        return true
    }

    fun setDeviceId(deviceId: String) {
        if (deviceId.isBlank()) return
        networkingService?.name = deviceId
    }

    fun setPreference(pref: String, value: String) {
        val editor = this.preferences?.edit() ?: return
        editor.putString(pref, value)
        editor.apply()
    }

    fun getPreference(pref: String): String? {
        return this.preferences?.getString(pref, null)
    }

    /**
     * "Processes" or rather parses 2 types of server messages at the moment:
     * 1. mpm game messages
     * 2. subscriber/client info (meta-info) updates
     * The return type is either a proper data object to be processed or an error object
     */
    fun processServerMessage(msg: String): Any? {
        if (msg.isEmpty()) return MpmGameMessageError.EMPTY_JSON

        val json: JSONObject?
        try {
            json = JSONObject(msg)
        } catch (malformedJson: JSONException) {
            return MpmGameMessageError.MALFORMED_JSON
        }

        val message = JsonMessage(json, MESSAGE_STATUS.VALID)


        var messageContent: JSONObject?
        try {
            messageContent = message.getMessageContent() ?: return MpmGameMessageError.INCOMPLETE_MESSAGE
        } catch (ex: Exception) {
            return null
        }


        when (message.getType()) {
            MESSAGE_TYPE.MPMGAME -> {
                val mpmMessage = MpmGameMessageConverter.convertMpmGameContentToDataObject(messageContent)
                when (mpmMessage) {
                    is MultiPatient -> {
                        Context.patientsInitialized = true
                        MultiPatientData.fromMpmGameMultiPatient(mpmMessage)
                        this.showToast(R.string.received_patient_init, Toast.LENGTH_SHORT)
                    }
                    is VitalSignChange -> {
                        if (Context.patientsInitialized) {
                            val p = MultiPatientData.getPatient(mpmMessage.patientId) ?: return mpmMessage
                            p.getVitalSign(mpmMessage.vitalSignId)?.value = mpmMessage.value.toInt()
                        } else if (System.currentTimeMillis() - Context.lastPatientsInitializationRequest > 500) {
                            Context.lastPatientsInitializationRequest = System.currentTimeMillis()
                            this@AbstractServiceView.sendMpmGameMessageRequest(MpmGameMessageTypes.PATIENT_VALUES_INIT)
                        }
                    }
                    is SoundInitializationRequest -> {
                        Context.soundsInitialized = true
                        Context.latestSoundInitalizationRequest = mpmMessage

                        if (mpmMessage.getNumberOfSoundsToInitialize() > 0) {
                            if (SoundDownloads.makeAvailableSpaceInTmpDirectory(mpmMessage)) {
                                mpmMessage.getSoundsToInialize().forEach {
                                    SoundDownloads.addSoundDownload(SoundDownload(it))
                                }
                                this.showToast(R.string.received_sound_conf, Toast.LENGTH_SHORT)
                            } else this.showToast(R.string.insufficient_download_space, Toast.LENGTH_LONG)
                        }
                        if (this.checkIfLatestSoundInitializationRequestHasBeenDownloaded(true)) {
                            if (SoundLibrary.areAllPlayersReady()) this.showToast(R.string.sounds_ready, Toast.LENGTH_SHORT)
                        } else this@AbstractServiceView.checkForSoundDownloads()
                    }
                    is Sound -> {
                        if (!Context.soundsInitialized &&
                                System.currentTimeMillis() - Context.lastSoundsInitializationRequest > 500) {
                            Context.lastSoundsInitializationRequest = System.currentTimeMillis()
                            this@AbstractServiceView.sendMpmGameMessageRequest(MpmGameMessageTypes.SOUNDS_INIT)
                        } else if (Context.soundsInitialized) SoundLibrary.getPlayer(mpmMessage.name)?.start()
                    }
                    is SimulationStateChange -> when (mpmMessage.state) {
                        SimulationState.INITIAL -> MultiPatientData.isStarted = true
                        SimulationState.STARTED -> MultiPatientData.isStarted = true
                        SimulationState.ENDED -> this.dealWithScenarioEnd()
                        SimulationState.FROZEN -> MultiPatientData.isFreezed = true
                        SimulationState.RUNNING -> MultiPatientData.isFreezed = false
                    }

                }
                return mpmMessage
            }
            MESSAGE_TYPE.SUBSCRIBER -> {
                val metaInfo = mutableMapOf<String, String>()
                messageContent.keys().forEach {
                    val value = messageContent.optString(it, null)
                    if (value != null) metaInfo[it] = value
                }
                val ret = networkingService?.syncMetaInfo(metaInfo)
                this.rememberConnectionPreferences()

                if (ret == true) Context.patientsInitialized = false

                return ret
            }
            MESSAGE_TYPE.TRANSFER -> {
                val ret = BinaryTransferMessageConverter.convertTransferMessageContentToDataObject(messageContent)
                        ?: return null
                this.handleBinaryTransferMessage(ret)

                if (ret is BinaryTransferResponse) SoundLibrary.addPlayer(SoundDownloads.findSoundDownloadByUuid(ret.uuid))

                if (this.checkIfLatestSoundInitializationRequestHasBeenDownloaded(true)) {
                    if (SoundLibrary.areAllPlayersReady()) this.showToast(R.string.sounds_ready, Toast.LENGTH_SHORT)
                }

                return ret
            }
            MESSAGE_TYPE.MNV_FRONT_END_HANDLER -> {
                val ret = message.getMessageContent()

                return ret
            }
            else -> return MpmGameMessageError.NOT_MPM_GAME
        }
    }

    fun rememberConnectionPreferences() {
        val deviceId = networkingService?.name
        if (deviceId != null) this.setPreference(PREFERENCES_DEVICE_ID, deviceId)
        val serverOrIp = networkingService?.server
        if (serverOrIp != null) this.setPreference(PREFERENCES_SERVER_OR_IP, serverOrIp)
    }

    fun addClientListener(listener: ClientListener) {
        AbstractServiceView.clientListeners.add(listener)
    }

    fun removeClientListener(listener: ClientListener) {
        AbstractServiceView.clientListeners.remove(listener)
        networkingService?.removeClientListener(listener)
    }

    private fun handleBinaryTransferMessage(message: Any) {
        if (message is BinaryTransferResponse) {
            val potentialDownload = SoundDownloads.findSoundDownloadByUuid(message.uuid)
            if (potentialDownload != null) {
                if (potentialDownload.hasBeenCancelled()) {
                    // send client abort message so that server doesn't keep sending
                    networkingService?.sendBinaryTransferMessage(
                            BinaryTransferTypes.ABORT,
                            potentialDownload.getBinaryTransferAbort().toJSONObject())
                } else if (!potentialDownload.hasFinished()) {
                    // store package data
                    potentialDownload.processBinaryTransferResponse(message)
                }
            } else {
                // we abort since we do not have a download registered for that uuid
                // this should actually not happen
                networkingService?.sendBinaryTransferMessage(
                        BinaryTransferTypes.ABORT,
                        BinaryTransferAbort(message.uuid, message.file, "client abort").toJSONObject())
            }
        } else if (message is BinaryTransferAbort) {
            val potentialDownload = SoundDownloads.getSoundDownload(message.file) ?: return
            potentialDownload.abortSoundDownload()
        }


    }

    fun checkForSoundDownloads() {
        if (networkingService != null && networkingService?.isNetworkActive() == true) {
            SoundDownloads.startCheckingForDownloads(networkingService!!::sendBinaryTransferMessage)
        }
    }

    fun stopCheckingForSoundDownloads() {
        SoundDownloads.stopCheckingForDownloads()
    }

    fun checkIfLatestSoundInitializationRequestHasBeenDownloaded(showToast: Boolean = false): Boolean {
        val soundsToInitialize = Context.latestSoundInitalizationRequest?.getSoundsToInialize() ?: return true
        soundsToInitialize.forEach {
            val s = SoundDownloads.getSoundDownload(it.name) ?: return false
            if (!s.hasFinished()) return false
        }

        if (showToast) this.showToast(R.string.downloaded_sound_conf, Toast.LENGTH_SHORT)
        return true
    }

    fun showToast(resId: Int, duration: Int) {
        runOnUiThread {
            Toast.makeText(this@AbstractServiceView, resId, duration).show()
        }
    }

    fun stopAllSoundActivities() {
        SoundLibrary.stopAllPlayers()
        this.stopCheckingForSoundDownloads()
        SoundDownloads.stopSoundDownloads(null)
    }

    fun dealWithScenarioEnd() {
        Context.patientsInitialized = false
        MultiPatientData.isStarted = false
        SoundLibrary.stopAllPlayers()
    }
}