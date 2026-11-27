package uk.buzzlab

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import uk.buzzlab.MainActivity.Companion.AC
import uk.buzzlab.MainActivity.Companion.EDGE
import uk.buzzlab.MainActivity.Companion.FG
import uk.buzzlab.MainActivity.Companion.MU

/** What the charts draw: the average, or the strength that a share of the 2 s windows stayed under, where 0.99 is the strongest 1%. */
class ChartView(val name: String, val shareOfWindows: Double?, val spectrumHeading: String, val explanation: String)

private val CHART_VIEWS = listOf(
    ChartView("the average", null, "Average spectrum", "Every steady 2 s window, averaged."),
    ChartView("a typical moment", .5, "Spectrum of a typical moment", "The middle window: half were weaker and half were stronger."),
    ChartView("the strongest 10%", .9, "Spectrum of the strongest 10%", "What the strongest 1 in 10 windows reached."),
    ChartView("the strongest 5%", .95, "Spectrum of the strongest 5%", "What the strongest 1 in 20 windows reached."),
    ChartView("the strongest 1%", .99, "Spectrum of the strongest 1%", "What the strongest 1 in 100 windows reached. Shows bursts the average hides."),
)

private const val CHOSEN_CHART_VIEW = "chartView"

fun spectrumShownAs(chartView: ChartView, stats: Dsp, loMph: Int, hiMph: Int): DoubleArray? {
    val shareOfWindows = chartView.shareOfWindows ?: return stats.mean(loMph, hiMph)
    val bandLevels = stats.bandLevelsAt(shareOfWindows, loMph, hiMph) ?: return null
    return Dsp.spectrumFromBandLevels(bandLevels)
}

/** Returns the view the slider starts on, which is the one last chosen. Stats without band levels get a note and only the average. */
fun MainActivity.addChartViewSlider(stats: Dsp, loMph: Int, hiMph: Int, onChoose: (ChartView) -> Unit): ChartView {
    val average = CHART_VIEWS.first()
    val windowsWithLevels = stats.bandLevelWindows(loMph, hiMph)
    if (windowsWithLevels == 0) {
        note("Only the average can be shown: the typical and strongest views need the raw readings, which this phone no longer has.", 13)
        return average
    }
    val someWindowsLackLevels = windowsWithLevels < stats.count(loMph, hiMph)

    val chosenAtStart = CHART_VIEWS[ui.getInt(CHOSEN_CHART_VIEW, 0).coerceIn(CHART_VIEWS.indices)]
    val explanation = note("", 14)
    val chosenLabel = TextView(this).apply {
        textSize = 15f
        setTextColor(FG)
        typeface = Typeface.DEFAULT_BOLD
    }
    val slider = SeekBar(this).apply {
        max = CHART_VIEWS.lastIndex
        progress = CHART_VIEWS.indexOf(chosenAtStart)
        progressTintList = ColorStateList.valueOf(AC)
        thumbTintList = ColorStateList.valueOf(AC)
        tickMark = stopMark()
        contentDescription = "What the charts show, from the average to the strongest 1%"
    }
    pinUnderPage(chosenLabel, slider)

    fun describe(chartView: ChartView) {
        chosenLabel.text = "Showing: ${chartView.name}"
        explanation.text = "Showing ${chartView.name}. " + chartView.explanation +
            tooFewWindowsWarning(chartView, windowsWithLevels) +
            (if (someWindowsLackLevels && chartView != average) " Older drives without raw readings are left out." else "") +
            " Move the slider at the bottom to change it."
        if (Build.VERSION.SDK_INT >= 30) slider.stateDescription = chartView.name
    }
    describe(chosenAtStart)

    slider.setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, position: Int, fromUser: Boolean) {
                val chartView = CHART_VIEWS[position]
                describe(chartView)
                onChoose(chartView)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                ui.edit().putInt(CHOSEN_CHART_VIEW, seekBar.progress).apply()
            }
        },
    )
    return chosenAtStart
}

private fun MainActivity.stopMark() = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(MU)
    setSize(dp(6), dp(6))
}

/** Pinned so the slider stays in reach while the charts it changes scroll past. */
private fun MainActivity.pinUnderPage(label: TextView, slider: SeekBar) {
    val labelAboveSlider = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val lineAbove = View(this).apply { setBackgroundColor(EDGE) }
    labelAboveSlider.addView(lineAbove, LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(8) })
    labelAboveSlider.addView(label)
    labelAboveSlider.addView(slider, LinearLayout.LayoutParams(-1, dp(44)))
    act.removeAllViews()
    act.addView(labelAboveSlider, LinearLayout.LayoutParams(0, -2, 1f))
    act.visibility = View.VISIBLE
}

/** The strongest 1% needs a hundred windows to pick from; with fewer it is just the strongest one or two. */
private fun tooFewWindowsWarning(chartView: ChartView, windows: Int): String {
    val shareOfWindows = chartView.shareOfWindows ?: return ""
    val windowsInTheStrongestShare = windows * (1 - shareOfWindows)
    if (shareOfWindows <= .5 || windowsInTheStrongestShare >= 1) return ""
    return " There are only ${windows * 2} s of steady driving here, so this is close to the single strongest window."
}
