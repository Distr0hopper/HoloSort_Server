package views

import javafx.application.Application
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.control.Alert
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.Priority
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.util.Duration
import logging.GlobalLogger
import models.enums.HololensCueType
import networking.Converter
import networking.NetworkingChangeListener
import networking.NetworkingState
import networking.Server
import org.json.JSONArray
import org.json.JSONObject
import publisher.*
import tornadofx.*
import utils.ValueChangeDialog
import java.awt.Desktop
import java.awt.Robot
import java.net.URL
import java.util.*

class NetworkingServiceMonitor : View() {
    private var isItsOwnApp = app.parameters.unnamed.contains("isItsOwnApp")

    private var isStarted = SimpleBooleanProperty(false)
    private val monitor = Monitor()
    private var networkingState = SimpleObjectProperty(Publisher.getNetworkingState())
    private val isNetworkingActiveBinding = Bindings.equal(this.networkingState, NetworkingState.ACTIVE)

    private var localIps = SimpleStringProperty("N/A")

    private val monitorNotRunningDummySubscriber = LocalSubscriber("Monitor not Running")

    val alertPath: URL? = NetworkingServiceMonitor::class.java.classLoader.getResource("wavs/alerttone2.wav")
    val alerttone = Media(alertPath.toString())
    val mediaPlayer = MediaPlayer(alerttone)

    private var lockCursorTimer: Timer? = null

    private var isPollLoggingEnabled = false

    private val subscriberTable = tableview<Subscriber> {
        readonlyColumn("ID", Subscriber::key).pctWidth(40)
        readonlyColumn("Name", Subscriber::name).pctWidth(60)
        items.add(monitorNotRunningDummySubscriber)

        smartResize()

        multiSelect(true)

        contextmenu {
            enableWhen(isNetworkingActiveBinding.and(isStarted))
            item("Add Filter") {
                action {
                    if (selectionModel.isEmpty) return@action

                    val changeDialog = ValueChangeDialog("Add Patient Ids (comma separated)", { ids ->
                        selectionModel.selectedItems.forEach { s ->
                            s.patientFilterFromCommaSeparatedString(ids)
                            Publisher.updateSubscriber(s)
                        }
                    }, "")
                    changeDialog.addValidation { input ->
                        return@addValidation when (input) {
                            null -> ValidationMessage("No Input", ValidationSeverity.Error)
                            "" -> {
                                selectionModel.selectedItems.forEach {
                                    it.clearAcceptedPatients()
                                }
                                null
                            }
                            else -> {
                                try {
                                    input.split(",").forEach {
                                        Integer.parseInt(it)
                                    }
                                } catch (any: Exception) {
                                    return@addValidation ValidationMessage(
                                        "Ids are not numeric",
                                        ValidationSeverity.Error
                                    )
                                }
                                null
                            }
                        }
                    }
                    openInternalWindow(changeDialog)
                }
            }
            item("Change Name") {
                action {
                    if (selectionModel.isEmpty) return@action

                    val selectedItems = selectionModel.selectedItems
                    if (selectedItems.size > 1) {
                        alert(Alert.AlertType.INFORMATION, "Names can only be changed for one Client")
                        return@action
                    }
                    if (selectedItem !is RemoteSubscriber) {
                        alert(Alert.AlertType.INFORMATION, "Names can only be changed for remote Clients")
                        return@action
                    }

                    val changeDialog = ValueChangeDialog("Change Client Name", {
                        if (selectedItem?.name != it) {
                            selectedItem?.name = it
                            selectedItem?.addMetaInfo("name", it)
                            Publisher.updateSubscriber(selectedItem!!)
                        }
                    }, selectedItem?.name)
                    changeDialog.addValidation { input ->
                        return@addValidation when (input) {
                            selectedItem?.name -> null
                            null -> ValidationMessage("No Input", ValidationSeverity.Error)
                            "" -> ValidationMessage("Empty Input", ValidationSeverity.Error)
                            else -> {
                                Publisher.getSubscribers().forEach {
                                    if (it.value.name == input) {
                                        return@addValidation ValidationMessage(
                                            "Another Client has already that Name",
                                            ValidationSeverity.Error
                                        )
                                    }
                                }
                                null
                            }
                        }
                    }
                    openInternalWindow(changeDialog)
                }
            }
        }
    }

    // reacts to changes in the networking state
    private val networkingChangeListener = object : NetworkingChangeListener {
        override fun onStateChange(old: NetworkingState, new: NetworkingState) {
            runLater {
                // update state
                networkingState.set(new)

                // stop monitoring if networking is not active
                if (new == NetworkingState.INACTIVE && isStarted.get()) stop()

                updateIPs()
            }
        }
    }

    // reacts to changes in subscribers (new, leaving, updates)
    private val subscriberChangeListener = object : SubscriberChangeListener {
        override fun onChange(subscriber: Subscriber) {
            subscriberTable.refresh()

            // the name will be set in ConnectionHandler::processMessage and this triggers onChange
            if (subscriber.name == "lens")
            {
                val handshakeMessage = JSONObject()
                handshakeMessage.put("handshake", true)
                Publisher.sendMessage(handshakeMessage, subscriber)
            }

            // we send back the updated meta info entries for 'confirmation'
            val metaInfoEntries = subscriber.getMetaInfoEntries()
            if (metaInfoEntries.isEmpty()) return
            Publisher.sendMessage(Converter.createUpdateSubscriberMessage(metaInfoEntries), subscriber)
        }

        override fun onSubscribe(subscriber: Subscriber) {
            if (!subscriberTable.items.contains(subscriber)) {
                runLater { subscriberTable.items.add(subscriber) }
            }
        }

        override fun onUnsubscribe(subscriber: Subscriber) {
            if (subscriberTable.items.contains(subscriber)) {
                runLater { subscriberTable.items.remove(subscriber) }
            }
        }

        override fun onMessage(subscriber: Subscriber?, jsonObject: JSONObject) {
            runLater {
                // do not add poll messages, if polling is disabled
                if (!isPollLoggingEnabled and (jsonObject.isHololensPoll()))
                {
                    return@runLater
                }

                messageList.items.add("Network: $jsonObject")
            }

            if (jsonObject.isHololensPoll())
            {
                val lens = subscriberTable.items.find{ it.name == "lens" } ?: return

                val timeSent = (jsonObject.get("content") as JSONObject).get("timeSent") as String
                val guid = (jsonObject.get("content") as JSONObject).get("id") as String

                val pollMsg = JSONObject()
                pollMsg.put("pollBack", "true")
                pollMsg.put("timeSent", timeSent)
                pollMsg.put("guid", guid)

                Publisher.sendMessage(pollMsg, lens)
                return
            }

            if (jsonObject.isDedicatedTo("lens")) {
                val lens = subscriberTable.items.find { it.name == "lens" } ?: return

                // content either has "rect" or "correctModuleEnabled"
                val contentObject = jsonObject.get("content") as JSONObject

                if (contentObject.has("relativeCenter")) {
                    val rect = contentObject.get("relativeCenter")
                    val interruptionLength = contentObject.get("interruptionLength")
                    val hololensCueType = contentObject.get("hololensCueType")

                    if (hololensCueType.toString() == HololensCueType.MANUAL.identifier) {
                        lockCursorTimer = lockCursor(null)
                    }

                    if (hololensCueType.toString() != HololensCueType.NONE.identifier) {
                        val holoLensMsg = JSONObject()
                        holoLensMsg.put("relativeCenter", rect)
                        holoLensMsg.put("interruptionLength", interruptionLength)
                        holoLensMsg.put("hololensCueType", hololensCueType)
                        Publisher.sendMessage(holoLensMsg, lens)
                    }
                }

                if (contentObject.has("correctModuleEnabled")) {
                    val hololensMsg = JSONObject()
                    hololensMsg.put("correctModuleEnabled", true)

                    Publisher.sendMessage(hololensMsg, lens)
                }

                if (contentObject.has("errorCountInterruption")) {
                    val hololensMsg = JSONObject()
                    hololensMsg.put("errorCountInterruption", contentObject.get("errorCountInterruption"))

                    Publisher.sendMessage(hololensMsg, lens)
                }
                if (contentObject.has("interruptionAccepted")) {
                    val hololensMsg = JSONObject()
                    hololensMsg.put("interruptionAccepted", contentObject.get("interruptionAccepted"))

                    Publisher.sendMessage(hololensMsg, lens)
                }

                return
            }

            if (jsonObject.isDedicatedTo("frontend"))
            {
                // relay the interruption to the frontend and at the same time to the web client
                // so the main task may be hidden
                val frontend = subscriberTable.items.find{ it.name == "frontend" } ?: return


                val content = (jsonObject.get("content") as JSONObject)
                val interruptionLength = content.get("interruptionLength") as Number

                val cont = JSONObject()
                cont.put("interruptionLength", interruptionLength)

                // Hololag1 sendet nur interruptionLength, Pill-Lag auch den Start-Delay
                if (content.has("interruptionStartDelay")) {
                    val interruptionStartDelay = content.get("interruptionStartDelay") as Number
                    cont.put("interruptionStartDelay", interruptionStartDelay)
                }

                if (content.has("interruptionWaiting")){
                    val interruptionWaiting = content.get("interruptionWaiting") as Boolean
                    cont.put("interruptionWaiting", interruptionWaiting)
                }

                val jsonMsg = JSONObject()
                jsonMsg.put("type", "frontend")
                jsonMsg.put("content", cont)

                Publisher.sendMessage(jsonMsg, frontend)
            }

            if (jsonObject.containsCSVData())
             {
                val csvData = jsonObject.getJSONArray("content") as JSONArray

                for (i in 0 until csvData.length()) {
                    convertToCSVFile(csvData.getJSONObject(i))
                    println("Data: " + csvData.getJSONObject(i))
                }

                return

                //These lines break the logging?
                //println("Close log files")
                //GlobalLogger.closeAllLogFiles()
            }

        }
    }

    /**
    Checks, if a JSONObjects ["type"] is backend, if ["content"] is a JSONObject and ["target"] is the same as
     the specified identifier.
     */
    private fun JSONObject.isDedicatedTo(identifier: String):Boolean {
        if (this.get("type") == "backend")
        {
            val target = this.opt("target")
            val content = this.get("content")

            if (content is JSONObject && target is String && target == identifier)
            {
                return true
            }
        }

        return false
    }

    private fun JSONObject.containsCSVData():Boolean {

            if (this.get("type") == "backend") {

                if (this.get("content") is JSONArray)
                {
                    return true
                }
            }
            return false
        }

    private fun JSONObject.isHololensPoll():Boolean {

        if (this.get("type") == "backend")
        {
            val target = this.opt("target")
            val content = this.get("content")

            // CSV is of type JSONArray
            if (content !is JSONObject)
            {
                return false
            }

            val poll = content.opt("poll")

            if (poll is String && poll == "true" && target is String && target == "lens")
            {
                return true
            }
        }

        return false
    }

    private val messageList = listview<String> {

        items.add("No Messages")

        contextmenu {
            enableWhen(isNetworkingActiveBinding.and(isStarted))
            item("Send Message") {
                action {
                    if (selectionModel.isEmpty) return@action

                    if (subscriberTable.selectionModel.isEmpty) {
                        alert(Alert.AlertType.INFORMATION, "Please select one or more recipients")
                        return@action
                    }
                    subscriberTable.selectionModel.selectedItems.forEach {
                        Publisher.sendMessage(JSONObject(selectedItem), it)
                    }
                }
            }
        }
    }
    private val monitorListener = object : MonitorListener {

        override fun onMessage(message: String) {
            runLater {

                if (!isPollLoggingEnabled and message.contains("pollBack"))
                {
                    return@runLater
                }

                messageList.items.add("Network: $message")
            }
            val jsonMessage = JSONObject(message)

            // Wieso ist das hier im OnMessage und nicht im SubscriberChangeListener?
            // weil alle Nachrichten, die ans frontend gesendet werden via Publisher::publish auch an den Monitor gesendet werden

            // Message is sent to the frontend
            if (jsonMessage.get("type") == "frontend") {

                // nur wenn eine Interruption gesendet wird, wird der Ton gespielt
                if (isInterruption(jsonMessage)) {
                    mediaPlayer.play()
                    mediaPlayer.seek(Duration(0.0))

                    val content = jsonMessage.get("content") as Number
                    val interruptionLength = content.toLong()

                    // woher kommen die 300ms?
                    val constDelay = 300
                    val delayLength = constDelay + (interruptionLength * 1000)

                    lockCursor(delayLength)

                } else {
                    println(jsonMessage.toString())
                }
            }
        }
    }

    /**
     * There are 3 types of messages that are sent to the frontend:
     * 1) Interruptions | {type: frontend, content = 15}
     * 2) calibration | {type: frontend, datatype: calibration / calibrationStart, content: bool/string}
     * 3) ResetInterruptionTaskIndex | {type: frontend, content = {resetInterruptionTaskIndex: true}}
     * Only 1) is an interruption
     */
    private fun isInterruption(jsonObj: JSONObject): Boolean {
        return (jsonObj.has("content") && jsonObj["content"] is Number && !jsonObj.has("dataType"))
    }

    /**
     * If duration is null, the timer will run forever.
     */
    fun lockCursor(duration: Long?) : Timer
    {
        val timer = Timer()

        val x = 960
        val y = 600
        val r = Robot()

        timer.schedule(object : TimerTask() {
            override fun run() {

                timer.scheduleAtFixedRate(
                    object : TimerTask() {
                        override fun run(){
                            r.mouseMove(x, y)
                            // TODO: comment back in (or disable when in debug?)
                        }
                    },
                    0, 1)
            }
        }, 300) //woher kommen die 300ms?
        //Set the schedule function

        if (duration != null)
        {

            timer.schedule(object : TimerTask() {
                override fun run() {
                    mediaPlayer.play()
                    mediaPlayer.seek(Duration(0.0))
                    timer.cancel()
                }
            }, duration)

        }

        return timer
    }

    fun convertToCSVFile(csvData: JSONObject) {
        val participantNumber = csvData.optString("participantNumber", " ")
        val block = csvData.optString("block", " ")
        val device = csvData.optString("device", " ")
        val interruptionTrial = csvData.optString("interruptionTrial", " ")
        val trials = csvData.optString("trial", " ")
        val clickInModule = csvData.optString("time", " ")
        val clickInModuleInt = csvData.optString("timeInt", " ")
        val resumptionError = csvData.optString("timeResumptionError", " ") // is actually a number, not a time!
        val patientID = csvData.optString("patientID", " ")
        val module = csvData.optString("module", " ")
        val wrongModule = csvData.optString("errorsModule", " ")
        val wrongInput = csvData.optString("errorsInput", " ")
        val emptyModule = csvData.optString("errorsEmptyModule", " ")
        val interruptionLength = csvData.optString("interruptionLength", " ")
        val clickOK = csvData.optString("clickOnOK", " ")
        val clickOKInt = csvData.optString("clickOnOKInt", " ")
        val startTimeInterruption = csvData.optString("startTimeIT", " ")
        val startTimeInterruptionInt = csvData.optString("startTimeInteger", " ")
        val endTimeInterruption = csvData.optString("endTimeIT", " ")
        val endTimeInterruptionInt = csvData.optString("endTimeInteger", " ")
        val errorCountInterruption = csvData.optString("errorCountInterruption", " ")
        val hololensCueType = csvData.optString("hololensCueType", " ")
        val cueSetDuration = csvData.optString("cueSetDuration", " ")
        val firstFocus = csvData.optString("firstFocus", " ")
        val firstFocusInt = csvData.optString("firstFocusInt", " ")

        println("StartTime:$startTimeInterruption")
        println("EndTime:$endTimeInterruption")

        // Note: The columns needs to be added beforehand, see: ExperimentRunner::createLogger
        val logEntry = GlobalLogger.exp().newLogEntry()
        logEntry.setValue("Participant Number", participantNumber)
        logEntry.setValue("Block", block)
        logEntry.setValue("Device", device)
        logEntry.setValue("Interruption Trial", interruptionTrial)
        logEntry.setValue("Trial", trials)
        logEntry.setValue("First Click In Module", clickInModule)
        logEntry.setValue("INTEGER: First Click In Module", clickInModuleInt)
        logEntry.setValue("Wrong Click In Module After Interruption", resumptionError)
        logEntry.setValue("Patient ID", patientID)
        logEntry.setValue("Module", module)
        logEntry.setValue("Error Wrong Module", wrongModule)
        logEntry.setValue("Error Input", wrongInput)
        logEntry.setValue("Error Empty Module", emptyModule)
        logEntry.setValue("Interruption Length", interruptionLength)
        logEntry.setValue("Click on OK", clickOK)
        logEntry.setValue("INTEGER: Click on OK", clickOKInt)
        logEntry.setValue("Start Time Interruption", startTimeInterruption)
        logEntry.setValue("INTEGER: Start Time Interruption", startTimeInterruptionInt)
        logEntry.setValue("End Time Interruption", endTimeInterruption)
        logEntry.setValue("INTEGER: End Time Interruption", endTimeInterruptionInt)
        logEntry.setValue("Error Count Interruption", errorCountInterruption)
        logEntry.setValue("Hololens Cue Type", hololensCueType)
        logEntry.setValue("Cue Set Duration", cueSetDuration)
        logEntry.setValue("First Focus", firstFocus)
        logEntry.setValue("First Focus Int", firstFocusInt)

        GlobalLogger.exp().log(logEntry)
    }

    override val root = vbox(20) {
        this.paddingAll = 10
        this.isFillWidth = true
        this.minWidth = 800.0

        hbox(50) {
            isFillWidth = true
            hbox(5) {
                alignment = Pos.CENTER
                label("Networking Monitor") {
                    font = Font.font(18.0)
                    textFill = Color.DARKOLIVEGREEN
                }
            }
            hbox(0) {
                alignment = Pos.CENTER
                label("Networking State: ") {
                    font = Font.font(14.0)
                }
                label(networkingState) {
                    font = Font.font(14.0)
                    textFill = Color.MAROON
                }
                label(localIps) {
                    visibleWhen { localIps.isNotEmpty }
                    paddingLeft = 20
                    font = Font.font(14.0)
                    textFill = Color.DARKKHAKI
                    tooltip("Click to copy IP(s)")
                    tooltip?.font = Font.font(15.0)
                    cursor = Cursor.HAND
                    setOnMouseClicked {
                        val clipboard = Clipboard.getSystemClipboard()
                        val content = ClipboardContent()

                        var ips = utils.getLocalIp4Addresses().toString()
                        System.out.println(ips)
                        ips = ips.replace("[\\[\\]]".toRegex(), "")

                        if (ips.isEmpty()) return@setOnMouseClicked

                        content.putString(ips)
                        clipboard.setContent(content)
                    }
                }
            }
        }

        hbox(50) {
            isFillWidth = true
            alignment = Pos.CENTER
            val startNetworkingButton = button("Start Networking") {
                enableWhen(Bindings.equal(networkingState, NetworkingState.INACTIVE))
                action { runAsync { Publisher.startNetworking() } }
            }
            button("Stop Networking") {
                enableWhen(isNetworkingActiveBinding)
                action {
                    parent.requestFocus()
                    runAsync {
                        Publisher.stopNetworking()
                    } ui {
                        startNetworkingButton.requestFocus()
                    }
                }
            }

            val startButton = button("Start Monitor") {
                enableWhen(isNetworkingActiveBinding.and(!isStarted))
                action { start() }
            }
            runLater { startButton.requestFocus() }
            button("Stop Monitor") {
                enableWhen(isNetworkingActiveBinding.and(isStarted))
                action {
                    stop()
                    startButton.requestFocus()
                }
            }
            val startWebsocket = button("Start Websocket") {
                enableWhen(isNetworkingActiveBinding.and(isStarted))
                action {

                    // Resolves the path to the patientDocumentation (web client) and launches it
                    // These files need to be copied to the build directory -> use GradleTask copyWebsocketClient
                    var location = javaClass.protectionDomain.codeSource.location.toExternalForm()
                    location = location.substring(0, location.lastIndexOf("/"));
                    location = "$location/websocket_client/patientDocumentation.html"
                    val uri = java.net.URI(location)

                    Desktop.getDesktop().browse(uri)

                }
            }

            checkbox("Log Polling Messages")
            {
                this.isSelected = isPollLoggingEnabled

                action {
                    // TODO: Alternatively filter all messages? https://code.makery.ch/blog/javafx-8-tableview-sorting-filtering/
                    isPollLoggingEnabled = this.isSelected
                }
            }

            runLater { startWebsocket.requestFocus() }
        }

        hbox(5) {
            isFillWidth = true
            vgrow = Priority.ALWAYS
            this.add(subscriberTable)
            messageList.hgrow = Priority.ALWAYS
            this.add(messageList)
        }

        hbox(5) {
            isFillWidth = true
            val msg = textfield()
            msg.hgrow = Priority.ALWAYS
            button("Send Message") {
                enableWhen(isNetworkingActiveBinding)
                action {
                    Publisher.publish(JSONObject(utils.createInfoJson(msg.text)))
                    msg.clear()
                }
            }
        }
    }

    init {
        Publisher.addNetworkingChangeListener(this.networkingChangeListener)
        this.updateIPs()
    }

    private fun updateIPs() {
        if (this.networkingState.get() != NetworkingState.ACTIVE) {
            this.localIps.value = ""
            return
        }

        this.localIps.value = "IP: N/A"
        val ipsForLocalMachine = utils.getLocalIp4Addresses()
        if (ipsForLocalMachine.isNotEmpty()) {
            var count = 0
            val tmp = StringBuilder("IP").append(if (ipsForLocalMachine.size > 1) "(s)" else "").append(": ")
            ipsForLocalMachine.forEach {
                if (count != 0) tmp.append(", ")
                tmp.append(it)
                count++
            }
            tmp.append(" | PORT: ${Server.DEFAULT_PORT}")
            this.localIps.value = tmp.toString()
        }
    }

    private fun start() {
        mediaPlayer.play()
        mediaPlayer.seek(Duration(0.0))
        this.monitor.addMonitorListener(this.monitorListener)
        Publisher.subscribe(this.monitor)
        this.subscriberTable.items.clear()
        this.messageList.items.clear()
        this.subscriberTable.items.addAll(
            Publisher.getSubscribers().values.sortedWith(
                Comparator { sub1, sub2 -> sub1.name.compareTo(sub2.name) })
        )
        Publisher.addSubscribeListener(this.subscriberChangeListener)
        this.isStarted.set(true)
    }

    private fun stop() {

        sendEndMessageToHololens()

        this.monitor.removeMonitorListener()
        Publisher.unsubscribe(this.monitor)
        Publisher.removeSubscribeListener(this.subscriberChangeListener)
        this.subscriberTable.items.clear()
        this.subscriberTable.items.add(this.monitorNotRunningDummySubscriber)
        this.messageList.items.clear()
        this.messageList.items.add("No Messages")
        this.isStarted.set(false)
    }

    private fun sendEndMessageToHololens()
    {
        val lens = subscriberTable.items.find{ it.name == "lens" } ?: return

        val jsonObj = JSONObject()
        jsonObj.put("type", "backend")
        jsonObj.put("ServerStatus", "Closed")
        jsonObj.put("target", "lens")

        Publisher.sendMessage(jsonObj, lens)
    }

    override fun onUndock() {
        // we react differently depending on where we are running
        if (!this.isItsOwnApp) return

        this.stop()
        super.onUndock()
        runAsync {
            try {
                Publisher.stopPublisher()
                app.stop()
            } catch (dontCarAnyMore: Exception) {
            }
        }
    }
}

class NetworkingMonitorApp : App(NetworkingServiceMonitor::class)

fun main() {
    Application.launch(NetworkingMonitorApp::class.java, "isItsOwnApp")
}