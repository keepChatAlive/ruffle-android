package rs.ruffle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.concurrent.TimeoutException
import kotlin.math.min
import kotlin.math.roundToInt
import org.hamcrest.CoreMatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val BASIC_SAMPLE_PACKAGE = "rs.ruffle"
private const val LAUNCH_TIMEOUT = 5000L
private const val SWF_WIDTH = 550.0
private const val SWF_HEIGHT = 400.0

@RunWith(AndroidJUnit4::class)
class InputEvents {
    private lateinit var device: UiDevice
    private lateinit var traceOutput: File
    private var lastTraceSize: Long = 0
    private lateinit var swfFile: File

    @Before
    fun startMainActivityFromHomeScreen() {
        // Initialize UiDevice instance
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Start from the home screen
        device.pressHome()

        // Wait for launcher
        val launcherPackage: String = device.launcherPackageName
        ViewMatchers.assertThat(launcherPackage, CoreMatchers.notNullValue())
        device.wait(
            Until.hasObject(By.pkg(launcherPackage).depth(0)),
            LAUNCH_TIMEOUT
        )

        // Launch the app
        val context = ApplicationProvider.getApplicationContext<Context>()
        traceOutput = File.createTempFile("trace", ".txt", context.cacheDir)
        swfFile = File.createTempFile("movie", ".swf", context.cacheDir)
        lastTraceSize = 0
        val resources = InstrumentationRegistry.getInstrumentation().context.resources
        val inStream = resources.openRawResource(
            rs.ruffle.test.R.raw.input_test
        )
        val bytes = inStream.readBytes()
        swfFile.writeBytes(bytes)
        val intent = context.packageManager.getLaunchIntentForPackage(
            BASIC_SAMPLE_PACKAGE
        )?.apply {
            component = ComponentName("rs.ruffle", "rs.ruffle.PlayerActivity")
            data = Uri.fromFile(swfFile)
            putExtra("traceOutput", traceOutput.absolutePath)
            // Clear out any previous instances
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)

        // Wait for the app to appear
        device.wait(
            Until.hasObject(By.pkg(BASIC_SAMPLE_PACKAGE).depth(0)),
            LAUNCH_TIMEOUT
        )
    }

    @Test
    fun clickEvents() {
        waitUntilNewLogAndIdle()

        val player = device.findObject(By.desc("Ruffle Player"))

        val red = screenToSwf(player.visibleBounds, Point(50, 50))
        val blue = screenToSwf(player.visibleBounds, Point(500, 350))
        device.click(red)
        device.click(blue)
        device.drag(red.x, red.y, blue.x, blue.y, 100)
        waitUntilNewLogAndIdle()

        val trace = traceOutput.readLines()
        ViewMatchers.assertThat(
            trace,
            CoreMatchers.equalTo(
                listOf(
                    "Test started!",
                    "red received mouseDown",
                    "red received mouseUp",
                    "red received click",
                    "blue received mouseDown",
                    "blue received mouseUp",
                    "blue received click",
                    "red received mouseDown",
                    "blue received mouseUp"
                )
            )
        )
    }

    @Test
    fun keyEvents() {
        waitUntilNewLogAndIdle()

        device.pressKeyCode(KeyEvent.KEYCODE_A)
        device.pressKeyCode(KeyEvent.KEYCODE_B)

        waitUntilNewLogAndIdle()

        val trace = traceOutput.readLines()
        ViewMatchers.assertThat(
            trace,
            CoreMatchers.equalTo(
                listOf(
                    "Test started!",
                    "keyDown: keyCode = 65, charCode = 97",
                    "keyUp: keyCode = 65, charCode = 97",
                    "keyDown: keyCode = 66, charCode = 98",
                    "keyUp: keyCode = 66, charCode = 98"
                )
            )
        )
    }

    private fun screenToSwf(playerBounds: Rect, point: Point): Point {
        val stretchX = playerBounds.width() / SWF_WIDTH
        val stretchY = playerBounds.height() / SWF_HEIGHT
        val scaleFactor = min(stretchX, stretchY)
        val swfScreenWidth = SWF_WIDTH * scaleFactor
        val swfScreenHeight = SWF_HEIGHT * scaleFactor
        val swfOffsetX = (playerBounds.width() - swfScreenWidth) / 2
        val swfOffsetY = (playerBounds.height() - swfScreenHeight) / 2
        return Point(
            (playerBounds.left + swfOffsetX + point.x * scaleFactor).roundToInt(),
            (playerBounds.top + swfOffsetY + point.y * scaleFactor).roundToInt()
        )
    }

    /**
     * Waits until the log file receives new trace output, and then waits for it to become idle with
     * no more output for a period of time.
     *
     * @param idleWindowMillis How long the log file must stay the same size for, before it's considered idle
     * @param timeoutMillis How long to keep waiting for, before throwing an error
     */
    private fun waitUntilNewLogAndIdle(idleWindowMillis: Long = 1000, timeoutMillis: Long = 10000) {
        val startTimeMillis = SystemClock.uptimeMillis()
        val timeoutAt = startTimeMillis + timeoutMillis
        waitUntilNewLog(timeoutAt - SystemClock.uptimeMillis())
        waitUntilLogIdles(idleWindowMillis, timeoutAt - SystemClock.uptimeMillis())
    }

    /**
     * Waits until new trace output has been written to the log file.
     *
     * @param timeoutMillis How long to keep waiting for, before throwing an error
     */
    private fun waitUntilNewLog(timeoutMillis: Long = 10000) {
        val startTimeMillis = SystemClock.uptimeMillis()
        val timeoutAt = startTimeMillis + timeoutMillis
        while (true) {
            val size = traceOutput.length()
            if (size > lastTraceSize) {
                lastTraceSize = size
                return
            }

            if (SystemClock.uptimeMillis() >= timeoutAt) {
                throw TimeoutException("No trace output was received within $timeoutMillis ms")
            }
            Thread.sleep(100)
        }
    }

    /**
     * Waits until the log file stops receiving any trace output.
     *
     * @param idleWindowMillis How long the log file must stay the same size for, before it's considered idle
     * @param timeoutMillis How long to keep waiting for, before throwing an error
     */
    private fun waitUntilLogIdles(idleWindowMillis: Long = 1000, timeoutMillis: Long = 10000) {
        val startTimeMillis = SystemClock.uptimeMillis()
        val timeoutAt = startTimeMillis + timeoutMillis

        lastTraceSize = traceOutput.length()
        Thread.sleep(idleWindowMillis)

        while (true) {
            val size = traceOutput.length()
            if (size == lastTraceSize) {
                return
            }
            lastTraceSize = size

            if (SystemClock.uptimeMillis() >= timeoutAt) {
                throw TimeoutException("No trace output was received within $timeoutMillis ms")
            }
            Thread.sleep(idleWindowMillis)
        }
    }
}

private fun UiDevice.click(point: Point) {
    this.click(point.x, point.y)
}
