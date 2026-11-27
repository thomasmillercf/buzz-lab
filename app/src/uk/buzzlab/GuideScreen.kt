package uk.buzzlab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import uk.buzzlab.MainActivity.Companion.FG
import uk.buzzlab.MainActivity.Companion.MU
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

private class ChartPattern(val title: String, val sketch: PatternSketch.Kind?, val explanation: String)

private val CHART_PATTERNS = listOf(
    ChartPattern(
        "A streak along the solid line",
        PatternSketch.Kind.ONCE_PER_WHEEL_TURN,
        "The solid line is once per turn of the wheels. A streak that climbs along it is most often a wheel out of balance, a tyre or rim " +
            "that isn't quite round, or a flat spot from standing. It tends to be worst in one band of speeds, often 50 to 70 mph: through " +
            "the steering wheel for a front wheel, through the seat for a rear one. Have the balance checked first.",
    ),
    ChartPattern(
        "A streak along the dashed line",
        PatternSketch.Kind.TWICE_PER_WHEEL_TURN,
        "The dashed line is twice per turn. A streak here points at a tyre that isn't evenly round or evenly stiff, such as an oval tyre " +
            "or a belt that has shifted, more than at balance. Swap the wheels front to back: if the feel moves with them, it's a tyre or wheel.",
    ),
    ChartPattern(
        "A steeper line above both",
        PatternSketch.Kind.MANY_PER_WHEEL_TURN,
        "Uneven tread wear, such as cupping or saw-toothed blocks, gives many small bumps each turn. It draws a line the same shape as " +
            "the white ones but higher up, often with a drone that rises with speed, and it can run off the top of the chart. Run a hand " +
            "round the tread to feel for it. Tired dampers and tracking that is out are the usual reasons it starts. A propshaft, the " +
            "gearbox and the motor or engine also turn faster than the wheels, so they draw lines up here too.",
    ),
    ChartPattern(
        "A level band",
        PatternSketch.Kind.LEVEL_BAND,
        "A band that stays at one frequency whatever the speed is a resonance: something that shakes at its own rate whenever it is " +
            "disturbed. It may be part of the car, such as a seat, the steering column, a panel or the exhaust, or it may be the phone " +
            "and its mount. Tape the phone down and compare. If the band goes, it was the phone.",
    ),
    ChartPattern(
        "A bright patch where a line crosses a band",
        PatternSketch.Kind.LINE_CROSSING_BAND,
        "Where a wheel line crosses a level band, the wheels are shaking that part at exactly the rate it rings at. That speed is usually " +
            "where it feels worst, and it is why a buzz can come in at one speed and fade above it. Curing either side helps: the wheel " +
            "that drives it, or the part that rings.",
    ),
    ChartPattern(
        "A glow low down that grows with speed",
        PatternSketch.Kind.LOW_GLOW,
        "Brightness spread along the bottom with no clear line is mostly the road coming up through the tyres and suspension. It changes " +
            "with the surface, so compare the same stretch of road. If it gathers around 10 to 15 Hz even on a smooth road, the dampers " +
            "are worth a look.",
    ),
    ChartPattern(
        "It comes and goes",
        null,
        "Move the slider from the average to the strongest 1%. If the picture only gets brighter, the buzz is there all the time, which " +
            "points at something that turns. If new bands appear, they come in bursts: bumps and joints in the road, or something loose " +
            "that rattles when it is hit. The spread chart shows the same thing as tall bars.",
    ),
    ChartPattern(
        "What the charts can't show",
        null,
        "Only steady driving is counted, so a judder you feel only while braking or pulling away isn't here. A judder that comes on with " +
            "the brake pedal usually means the discs. The charts show how the car shakes. They don't show why.",
    ),
)

fun MainActivity.chartGuide(back: () -> Unit) {
    page("Reading the charts", back)
    note(
        "Patterns that often go with common faults. They narrow down where to look. They aren't a diagnosis, so have a garage confirm " +
            "anything that matters.",
        15,
    )
    for (pattern in CHART_PATTERNS) {
        heading(pattern.title)
        if (pattern.sketch != null) list.addView(PatternSketch(this, pattern.sketch, "Sketch of ${pattern.title.replaceFirstChar { it.lowercase() }}"))
        text(pattern.explanation, 15)
    }
}

@SuppressLint("ViewConstructor")
private class PatternSketch(context: Context, private val kind: Kind, spokenDescription: String) : View(context) {
    enum class Kind { ONCE_PER_WHEEL_TURN, TWICE_PER_WHEEL_TURN, MANY_PER_WHEEL_TURN, LEVEL_BAND, LINE_CROSSING_BAND, LOW_GLOW }

    private val density = resources.displayMetrics.density
    private val cellPaint = Paint()
    private val wheelLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FG
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MU
        textSize = 11 * density
    }

    init {
        contentDescription = spokenDescription
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(width, (width * .5).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val plotLeft = 30 * density
        val plotTop = 6 * density
        val plotHeight = height - plotTop - 22 * density
        val columnWidth = (width - plotLeft) / SPEED_COLUMNS
        val rowHeight = plotHeight / FREQUENCY_ROWS
        val yAt = { hz: Double -> plotTop + plotHeight - (ln(hz / LOWEST_HZ) / ln(HIGHEST_HZ / LOWEST_HZ) * plotHeight).toFloat() }

        for (column in 0 until SPEED_COLUMNS) {
            val mph = mphAtColumn(column + .5)
            for (row in 0 until FREQUENCY_ROWS) {
                val hz = LOWEST_HZ * (HIGHEST_HZ / LOWEST_HZ).pow((row + .5) / FREQUENCY_ROWS)
                cellPaint.color = Charts.col(strength(mph, hz), false)
                val cellBottom = plotTop + plotHeight - row * rowHeight
                val cellLeft = plotLeft + column * columnWidth
                canvas.drawRect(cellLeft + density, cellBottom - rowHeight - 1, cellLeft + columnWidth - density, cellBottom, cellPaint)
            }
        }

        for (turnsPerWheelTurn in 1..2) {
            wheelLinePaint.pathEffect = if (turnsPerWheelTurn == 1) null else DashPathEffect(floatArrayOf(7 * density, 5 * density), 0f)
            val line = Path()
            for (step in 0..40) {
                val column = SPEED_COLUMNS * step / 40.0
                val hz = turnsPerWheelTurn * wheelTurnsPerSecond(mphAtColumn(column))
                if (hz < LOWEST_HZ) continue
                val x = plotLeft + (column * columnWidth).toFloat()
                if (line.isEmpty) line.moveTo(x, yAt(hz)) else line.lineTo(x, yAt(hz))
            }
            canvas.drawPath(line, wheelLinePaint)
        }
        labelPaint.textAlign = Paint.Align.RIGHT
        for (hz in LABELLED_HZ) canvas.drawText("$hz", plotLeft - 4 * density, yAt(hz.toDouble()) + 4 * density, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        val labelBaseline = height - 6 * density
        canvas.drawText("slower", plotLeft + 26 * density, labelBaseline, labelPaint)
        canvas.drawText("mph (Hz up the side)", plotLeft + (width - plotLeft) / 2, labelBaseline, labelPaint)
        canvas.drawText("faster", width - 26 * density, labelBaseline, labelPaint)
    }

    private fun mphAtColumn(column: Double) = SLOWEST_MPH + (FASTEST_MPH - SLOWEST_MPH) * column / SPEED_COLUMNS

    private fun wheelTurnsPerSecond(mph: Double) = mph / Dsp.MPH / TYRE_CIRCUMFERENCE_M

    /** How bright the sketch is at a speed and frequency, on the real chart's scale of 0 to 0.35. */
    private fun strength(mph: Double, hz: Double): Double {
        val shareOfTopSpeed = mph / FASTEST_MPH
        val wheelHz = wheelTurnsPerSecond(mph)
        return when (kind) {
            Kind.ONCE_PER_WHEEL_TURN -> .34 * shareOfTopSpeed.pow(1.5) * ridge(hz, wheelHz)
            Kind.TWICE_PER_WHEEL_TURN -> .34 * shareOfTopSpeed.pow(1.5) * ridge(hz, 2 * wheelHz)
            Kind.MANY_PER_WHEEL_TURN -> .34 * shareOfTopSpeed * ridge(hz, 9 * wheelHz)
            Kind.LEVEL_BAND -> .32 * (.35 + .65 * shareOfTopSpeed) * ridge(hz, 32.0)
            Kind.LINE_CROSSING_BAND -> {
                val bandHz = 12.0
                val howCloseTheLineIsToTheBand = ridge(wheelHz, bandHz, widthInOctaves = .3)
                (.07 + .3 * howCloseTheLineIsToTheBand) * ridge(hz, bandHz) + .08 * shareOfTopSpeed * ridge(hz, wheelHz)
            }
            Kind.LOW_GLOW -> .26 * shareOfTopSpeed * ridge(hz, 4.5, widthInOctaves = 1.6)
        }
    }

    private fun ridge(hz: Double, centreHz: Double, widthInOctaves: Double = .22): Double {
        val octavesFromCentre = ln(hz / centreHz) / ln(2.0)
        return exp(-(octavesFromCentre / widthInOctaves).pow(2))
    }

    private companion object {
        const val SPEED_COLUMNS = 12
        const val FREQUENCY_ROWS = 48
        const val SLOWEST_MPH = 10.0
        const val FASTEST_MPH = 70.0
        const val LOWEST_HZ = 2.0
        const val HIGHEST_HZ = 230.0
        val LABELLED_HZ = intArrayOf(2, 10, 30, 100)

        /** A typical family car tyre, about 2 m round. */
        const val TYRE_CIRCUMFERENCE_M = 2.0
    }
}
