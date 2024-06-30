package models

import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import tornadofx.*
import utils.HMDLagUserPrefsManager
import javax.json.JsonObject

class ExperimentConfiguration : JsonModel {

    val participantNumProperty = SimpleIntegerProperty(0)
    var participantNumber by participantNumProperty

    val trialProperty = SimpleObjectProperty<Trial>()
    var trial by trialProperty

    val outputDirectoryProperty = SimpleStringProperty()
    var outputDirectory by outputDirectoryProperty

    val deviceProperty = SimpleStringProperty()
    var device by deviceProperty

    val trainingIncludedProperty = SimpleBooleanProperty(true)
    var trainingIncluded by trainingIncludedProperty

    val filePathProperty = SimpleStringProperty(null)
    var filePath: String? by filePathProperty

    val interruptionTaskProperty = SimpleStringProperty(null)
    var interruptionTask: String? by interruptionTaskProperty


    val handednessProperty = SimpleStringProperty(null)
    var handedness: String? by handednessProperty

    val expTypeProperty = SimpleStringProperty(null)
    var expType: String? by expTypeProperty

    override fun updateModel(json: JsonObject) {
        with(json) {
            participantNumber = int("ParticipantNumber")!!
            device = string("Device")
            outputDirectory = string("OutputDirectory")
            interruptionTask = string("InterruptionTask")
            trainingIncluded = boolean("TrainingIncluded")!!
            handedness = string("Handedness")
            expType = string("ExpType")
        }
    }

    override fun toJSON(json: JsonBuilder) {
        with(json) {
            add("ParticipantNumber", participantNumber)
            add("TrialsConfiguration", trial)
            add("OutputDirectory", outputDirectory)
            add("InterruptionTask", interruptionTask)
            add("Device", device)
            add("TrainingIncluded", trainingIncluded)
            add("Handedness", handedness)
            add("ExpType", expType)
        }
    }

    fun clear() {
        participantNumber = 0
        trial = null
        outputDirectory = HMDLagUserPrefsManager.loadPrefs(
            HMDLagUserPrefsManager.OUTPUT_DIRECTORY_KEY,
            System.getProperty("user.home")
        )
        interruptionTask = null
        device = null
        trainingIncluded = true
        handedness = null
        expType = null
    }

    override fun toString(): String = toJSON().toString()
}