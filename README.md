# Server and Client for HoloSort experiment
This is the server and client for the [HoloSort](https://gitlab2.informatik.uni-wuerzburg.de/s407742/holosort) experiment.
The server is a Java application that runs on a desktop PC.
The client is an Android application that runs on a tablet.
The server and client communicate via a network connection.
The server sends math exercises to the client, and the client sends the user's answers back to the server.
The server ochestrates the experiment between the tablet and the HoloLens.
![img_3.png](sources%2Fimg_3.png)

### For ['Holosort-Strategies'](https://gitlab2.informatik.uni-wuerzburg.de/s407742/holosort-strategies):
![Interruption_handling.png](sources%2FInterruption_handling.png)
## Requirements

- IntelliJ
    - Kotlin Plugin 

- Java SDK
    - Java 11+

## For programming

- Open the directory "HoloSort_Server" IntelliJ
- Setup Gradle:
  - Open folder 'client-interruption' > right click on file build.gradle > Link Gradle project
  - Open folder 'client-manual' > right click on file build.gradle > Link Gradle project
  - Open folder 'server' > right click on file build.gradle > Link Gradle project
  - Reload all Gradle projects in the Gradle menu (top left corner)
  
_Ignore the warnings, if the Gradle Sync shows green arrows, everything is fine._

- Build Server:
  - To build the executables for the server (jar files) use the Gradle-menu "server" > "Tasks" > "others" > "menu"
    - (**this needs to be done after every change in the server code!!**)
  - Make sure java is installed on the system and the path is set correctly
  - Go to the folder: /HoloSort_Server/server/build/libs
  - Open file "Pill-Exp-Server.jar"  (e.g. by opening folder in terminal and typing `java -jar Pill-Exp-Server.jar`)

- Run Client: 
  - Select "client-interruption.app" as run configuration 
    - ![img.png](sources%2Fimg.png)
  - If not able to run, select three-dots > edit. Make sure run configuration is set to "client-interruption.app.main" 
    - ![img_2.png](sources%2Fimg_2.png)
    - ![img_1.png](sources%2Fimg_1.png)
   
- Programming Server calls:
  - AbstractServiceView.kt as parent class to send messages to Backend (methods to call - processing of the message is done in the NetworkingServiceMonitor)
  - **NetworkingServiceMonitor.kt** is orchastrating the outgoing and incoming calls. The **onMessage()** method has to be expanded for every new call!

**For more information, see the [User Manual](sources/Einführung_Programmcode.pdf) and for installation troubleshooting see [Installation and Troubleshooting Guide](sources/Installation-troubleshooting-guide.pdf)** 
## Relevant files

The executable file for the Desktop PC:
- /HoloSort_Server/server/build/libs/Pill-Exp-Server.jar

The build description:
- /HoloSort_Server/client-interruption/build.gradle
- /HoloSort_Server/client-manual/build.gradle
- /HoloSort_Server/server/build.gradle

## Code Structure

Entry point:
  - HoloSort_Server/client-interruption/app/src/main/kotlin/cerg.mnv/view/ConfigActivity.kt

Showing math exercises:
  - HoloSort_Server/client-interruption/app/src/main/kotlin/cerg/mnv/view/ArithmeticActivity.kt


[UML class diagram](kotlin-tornadofx-gradlemnv/docs/HMDLag-UML.drawio.pdf) //
[Test procedure](kotlin-tornadofx-gradlemnv/docs/HMDLag_Interaction.drawio.pdf)

### Not clickable link in Installation-troubleshooting-guide.pdf
https://stackoverflow.com/questions/61434182/installed-build-tools-revision-28-0-3-is-corrupted-how-to-fix-this-error
