package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

// --- Data Models & Architecture ---

enum class EmotionState(val label: String, val color: Color, val icon: ImageVector) {
    NEUTRAL("Neutral", Color.Gray, Icons.Default.Face),
    STRESSED("Stressed", Color(0xFFE57373), Icons.Default.Warning),
    CALM("Calm", Color(0xFF81C784), Icons.Default.CheckCircle)
}

data class ContextObject(
    val timeOfDay: String = "Unknown",
    val locationZone: String = "Home",
    val currentActivity: String = "Monitoring...",
    val nextEventInMinutes: Int = 120,
    val batteryLevel: Int = 0,
    val motionIntensity: Float = 0f,
    val voiceStressLevel: Float = 0.1f,
    val tapIntervalMs: Long = 1000,
    val foregroundAppCategory: String = "Productivity"
)

data class DecisionResult(
    val action: String,
    val message: String,
    val confidence: Float,
    val reason: String
)

data class Nudge(
    val id: Int,
    val message: String,
    val timestamp: LocalDateTime,
    val confidence: Float,
    var status: NudgeStatus = NudgeStatus.PENDING
)

enum class NudgeStatus { PENDING, ACCEPTED, DISMISSED }

data class AppState(
    val currentEmotion: EmotionState = EmotionState.NEUTRAL,
    val context: ContextObject = ContextObject(),
    val isCalmModeActive: Boolean = false,
    val nudges: List<Nudge> = emptyList(),
    val lastDecision: DecisionResult? = null
)

// --- Empathetic Intelligence Engine ---

class EmpatheticEngine {
    private val _state = mutableStateOf(AppState())
    val state: State<AppState> = _state

    private var lastTapTime: Long = 0

    fun recordTap() {
        val now = System.currentTimeMillis()
        val interval = if (lastTapTime == 0L) 1000L else now - lastTapTime
        lastTapTime = now
        updateContext { it.copy(tapIntervalMs = interval) }
    }

    fun updateContext(transform: (ContextObject) -> ContextObject) {
        val newContext = transform(_state.value.context)
        val inferredEmotion = inferEmotion(newContext)
        val decision = runDecisionEngine(inferredEmotion, newContext)
        
        applyState(inferredEmotion, newContext, decision)
    }

    private fun inferEmotion(ctx: ContextObject): EmotionState {
        // Logic: Stressed if erratic taps (< 350ms) OR high motion OR voice stress
        return when {
            ctx.tapIntervalMs < 350 || ctx.motionIntensity > 16f || ctx.voiceStressLevel > 0.7f -> EmotionState.STRESSED
            ctx.tapIntervalMs > 800 && ctx.motionIntensity < 5f && ctx.voiceStressLevel < 0.2f -> EmotionState.CALM
            else -> EmotionState.NEUTRAL
        }
    }

    private fun runDecisionEngine(emotion: EmotionState, ctx: ContextObject): DecisionResult {
        val hour = LocalDateTime.now().hour
        val isLateNight = hour in 0..5
        val isNearDeadline = ctx.nextEventInMinutes < 60
        val isSwitchingApps = ctx.foregroundAppCategory == "Switching"

        return when {
            emotion == EmotionState.STRESSED && (isLateNight || isNearDeadline || isSwitchingApps) -> {
                DecisionResult("CALM_MODE", "Pause. Focus on one topic for 10 minutes.", 0.92f, "high stress + temporal pressure")
            }
            emotion == EmotionState.STRESSED -> {
                DecisionResult("NUDGE", "Take a 2-minute reset. Breathe.", 0.88f, "stress detected from interaction rhythm")
            }
            emotion == EmotionState.NEUTRAL -> {
                DecisionResult("NUDGE", "Organizing your evening tasks. Ready?", 0.76f, "minimal suggestion")
            }
            emotion == EmotionState.CALM -> {
                DecisionResult("SILENT", "Good momentum. No interruption.", 0.82f, "encourage progress")
            }
            else -> DecisionResult("SILENT", "", 0.5f, "monitoring steady state")
        }
    }

    private fun applyState(emotion: EmotionState, context: ContextObject, decision: DecisionResult) {
        val currentState = _state.value
        val calmActive = if (decision.action == "CALM_MODE") true else currentState.isCalmModeActive

        val updatedNudges = if (decision.action == "NUDGE" && decision.confidence >= 0.75f &&
            currentState.nudges.none { it.message == decision.message && it.status == NudgeStatus.PENDING }) {
            currentState.nudges + Nudge(currentState.nudges.size + 1, decision.message, LocalDateTime.now(), decision.confidence)
        } else {
            currentState.nudges
        }

        _state.value = currentState.copy(
            currentEmotion = emotion,
            context = context,
            isCalmModeActive = calmActive,
            nudges = updatedNudges,
            lastDecision = decision
        )
    }

    fun handleNudge(id: Int, status: NudgeStatus) {
        _state.value = _state.value.copy(
            nudges = _state.value.nudges.map { if (it.id == id) it.copy(status = status) else it }
        )
    }

    fun exitCalmMode() {
        _state.value = _state.value.copy(isCalmModeActive = false)
    }

    fun reset() {
        _state.value = AppState()
    }
}

// --- MainActivity & Sensor Integration ---

class MainActivity : ComponentActivity(), SensorEventListener {
    private val engine = EmpatheticEngine()
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastUpdate: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setContent {
            MyApplicationTheme {
                MainScreen(engine)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 500) return
        lastUpdate = now

        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val magnitude = sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2])
            
            val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val battery = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            
            engine.updateContext { it.copy(motionIntensity = magnitude, batteryLevel = battery, timeOfDay = if (LocalDateTime.now().hour < 12) "Morning" else "Afternoon") }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(engine: EmpatheticEngine) {
    val state by engine.state

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { engine.recordTap() })
            },
        topBar = {
            TopAppBar(
                title = { Text("Empathetic Assistant", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { engine.reset() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                SensorDashboard(state.context)
                Spacer(modifier = Modifier.height(16.dp))
                EmotionIndicator(state.currentEmotion, state.lastDecision)
                Spacer(modifier = Modifier.height(24.dp))
                NudgeList(state.nudges) { id, status -> engine.handleNudge(id, status) }
                Spacer(modifier = Modifier.weight(1f))
                SimulatorPanel(engine)
            }

            if (state.isCalmModeActive) {
                CalmModeOverlay { engine.exitCalmMode() }
            }
        }
    }
}

@Composable
fun SensorDashboard(ctx: ContextObject) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("ZONE 1: SENSORS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SensorItem("Motion", "%.1f".format(ctx.motionIntensity), Icons.AutoMirrored.Filled.DirectionsRun)
                SensorItem("Touch", "${ctx.tapIntervalMs}ms", Icons.Default.TouchApp)
                SensorItem("Battery", "${ctx.batteryLevel}%", Icons.Default.BatteryChargingFull)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SensorItem("Voice", if (ctx.voiceStressLevel > 0.5f) "Tense" else "Soft", Icons.Default.Mic)
                SensorItem("Zone", ctx.locationZone, Icons.Default.LocationOn)
                SensorItem("Event", "-${ctx.nextEventInMinutes}m", Icons.Default.CalendarToday)
            }
        }
    }
}

@Composable
fun SensorItem(label: String, value: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Gray)
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun EmotionIndicator(emotion: EmotionState, decision: DecisionResult?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(emotion.icon, contentDescription = null, tint = emotion.color, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text("Inferred State: ${emotion.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            decision?.let {
                Text("Reason: ${it.reason}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun NudgeList(nudges: List<Nudge>, onAction: (Int, NudgeStatus) -> Unit) {
    Text("Proactive Nudges", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(8.dp))
    if (nudges.isEmpty()) {
        Text("Listening for patterns...", color = Color.Gray)
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(nudges.reversed()) { nudge ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(nudge.message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        if (nudge.status == NudgeStatus.PENDING) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onAction(nudge.id, NudgeStatus.DISMISSED) }) { Text("Dismiss") }
                                Button(onClick = { onAction(nudge.id, NudgeStatus.ACCEPTED) }) { Text("Accept") }
                            }
                        } else {
                            Text(if (nudge.status == NudgeStatus.ACCEPTED) "Applied" else "Ignored", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SimulatorPanel(engine: EmpatheticEngine) {
    Surface(tonalElevation = 4.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("POC SIMULATOR (Software & Simulated Signals)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { engine.updateContext { it.copy(voiceStressLevel = 0.9f) } }, modifier = Modifier.weight(1f)) {
                    Text("Tense Voice", fontSize = 10.sp)
                }
                Button(onClick = { engine.updateContext { it.copy(nextEventInMinutes = 15) } }, modifier = Modifier.weight(1f)) {
                    Text("Deadline", fontSize = 10.sp)
                }
                Button(onClick = { engine.updateContext { it.copy(foregroundAppCategory = "Switching") } }, modifier = Modifier.weight(1f)) {
                    Text("App Swap", fontSize = 10.sp)
                }
            }
            Text("Tip: Tap the screen rapidly to simulate touch stress rhythm", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun CalmModeOverlay(onExit: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).padding(32.dp), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Spa, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Calm Mode Active", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Fragmented signals detected. We've simplified your UI and silenced alerts.", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onExit) { Text("I'm ready to resume") }
            }
        }
    }
}
