package com.gahs.wallpaper.bubblewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.util.TypedValue
import android.view.MotionEvent
import android.view.SurfaceHolder

import androidx.annotation.ColorInt

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

class BubbleWallService: WallpaperService() {
    override fun onCreateEngine(): Engine {
        return BubbleWallEngine()
    }

    private inner class BubbleWallEngine: Engine() {
        // Configurable
        private val bubblePadding = 50
        private val maxBubbleRadius = 250
        private val minBubbleRadius = 20
        private val overlapRetryCount = 50
        private val outlineSize = 30

        private val mReceiver: BroadcastReceiver = BubbleWallReceiver()
        private val mBubbles = ArrayList<Bubble>()
        private var mPressedBubble: Bubble? = null
        private var mSurfaceHeight = 0
        private var mSurfaceWidth = 0
        private var mCurrentGradientFactor = 0f
        private var mZoomLevel = 0f

        private inner class BubbleWallReceiver: BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                when (action) {
                    Intent.ACTION_USER_PRESENT -> {
                        // Restore zoom level
                        adjustBubbleCoordinates(mZoomLevel)
                        drawBubblesFactorOfMaxSmoothly(1f)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        // Reset zoom level on screen off
                        adjustBubbleCoordinates(0f)
                        drawBubblesFactorOfMax(1 / 3f)
                    }
                    Intent.ACTION_CONFIGURATION_CHANGED -> {
                        drawUiModeTransition()
                    }
                    Intent.ACTION_PACKAGE_CHANGED -> {
                        drawBubblesCurrentRadius()
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            val intentFilter = IntentFilter()
            intentFilter.addAction(Intent.ACTION_SCREEN_OFF)
            intentFilter.addAction(Intent.ACTION_USER_PRESENT)
            intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED)

            val pkgIntentFilter = IntentFilter()
            pkgIntentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED)
            pkgIntentFilter.addDataScheme("package")

            if (!isPreview) {
                registerReceiver(mReceiver, intentFilter)
                registerReceiver(mReceiver, pkgIntentFilter)
            }
        }

        override fun onDestroy() {
            if (!isPreview) unregisterReceiver(mReceiver)
        }

        override fun onSurfaceChanged(surfaceHolder: SurfaceHolder, format: Int, width: Int,
                                      height: Int) {
            mSurfaceHeight = height
            mSurfaceWidth = width

            regenAllBubbles()
            drawBubblesFactorOfMax(1f)
            drawBubblesFactorOfMax(1f)
        }

        override fun onTouchEvent(event: MotionEvent) {
            // Ignore unwanted touch events
            if (event.action != MotionEvent.ACTION_UP &&
                    event.action != MotionEvent.ACTION_DOWN) {
                return
            }

            if (event.action == MotionEvent.ACTION_DOWN) {
                mPressedBubble = getBubbleFromTouchEvent(event)
                if (mPressedBubble != null) {
                    drawBubbleTouch(true)
                }
            } else if (mPressedBubble != null) {
                drawBubbleTouch(false)
            }
        }

        override fun onZoomChanged(zoom: Float) {
            mZoomLevel = zoom
            adjustBubbleCoordinates(zoom)
            drawBubblesFactorOfMax((1 - zoom).coerceAtLeast(.2f))
        }

        private fun drawCanvasBackground(canvas: Canvas,
                                         brightness: Float = if (isNightMode) 0f else 1f,
                                         gradientFactor: Float = mCurrentGradientFactor) {
            // Draw grayscale color
            val r = (255 * brightness).roundToInt()
            val g = (255 * brightness).roundToInt()
            val b = (255 * brightness).roundToInt()
            canvas.drawARGB(255, r, g, b)

            // Draw gradient
            mCurrentGradientFactor = gradientFactor
            val darkColor = adjustColorAlpha(accentColor, if (isNightMode) .1f else .6f)
            val brightColor = adjustColorAlpha(accentColor, .3f)
            val height = mSurfaceHeight - mSurfaceHeight * (gradientFactor * .75f)
            val paint = Paint()
            paint.shader = LinearGradient(0f, mSurfaceHeight.toFloat(), 0f, height, darkColor,
                    brightColor, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, mSurfaceWidth.toFloat(), mSurfaceHeight.toFloat(), paint)
        }

        private fun drawBubbles(canvas: Canvas) {
            for (bubble in mBubbles) {
                // Bubble shadow
                val shadowX = bubble.currentX + bubble.currentRadius / 6
                val shadowY = bubble.currentY + bubble.currentRadius / 6
                val paint = Paint()
                paint.shader = RadialGradient(shadowX, shadowY, bubble.currentRadius, Color.BLACK,
                        Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawCircle(shadowX, shadowY, bubble.currentRadius, paint)

                // Bubble
                canvas.drawCircle(bubble.currentX, bubble.currentY, bubble.currentRadius, bubble.fillPaint)

                // Bubble outline
                canvas.drawCircle(bubble.currentX, bubble.currentY,
                        bubble.currentRadius - outlineSize / 2, bubble.outlinePaint)
            }
        }

        private fun drawBubblesCurrentRadius() {
            val surfaceHolder = surfaceHolder
            val canvas = lockHwCanvasIfPossible(surfaceHolder)
            drawCanvasBackground(canvas)
            drawBubbles(canvas)
            surfaceHolder.unlockCanvasAndPost(canvas)
        }

        private fun drawBubblesFactorOfMax(factor: Float) {
            val surfaceHolder = surfaceHolder
            val canvas = lockHwCanvasIfPossible(surfaceHolder)
            drawCanvasBackground(canvas, if (isNightMode) 0f else 1f, factor)
            for (bubble in mBubbles) {
                bubble.currentRadius = bubble.baseRadius * factor
            }
            drawBubbles(canvas)
            surfaceHolder.unlockCanvasAndPost(canvas)
        }

        fun drawUiModeTransition() {
            val surfaceHolder = surfaceHolder
            for (x in if (isNightMode) 20 downTo 0 else 0..20) {
                val canvas = lockHwCanvasIfPossible(surfaceHolder)
                val brightness = x / 20f
                drawCanvasBackground(canvas, brightness, mCurrentGradientFactor)
                drawBubbles(canvas)
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawBubbleTouch(expand: Boolean) {
            val surfaceHolder = surfaceHolder
            for (x in 0..4) {
                val canvas = lockHwCanvasIfPossible(surfaceHolder)
                drawCanvasBackground(canvas)
                mPressedBubble!!.currentRadius += if (expand) 1f else -1f
                drawBubbles(canvas)
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
            if (!expand) mPressedBubble = null
        }

        fun drawBubblesFactorOfMaxSmoothly(targetFactor: Float) {
            val targetRadii = FloatArray(mBubbles.size)
            val ranges = FloatArray(mBubbles.size)
            val addToRadius = FloatArray(mBubbles.size)
            for (bubble in mBubbles) {
                val index = mBubbles.indexOf(bubble)
                targetRadii[index] = bubble.baseRadius * targetFactor
                ranges[index] = targetRadii[index] - bubble.currentRadius
                addToRadius[index] = bubble.baseRadius * .05f
            }

            val isExpansion = ranges[0] > 0
            val surfaceHolder = surfaceHolder
            while (mBubbles[0].currentRadius != targetRadii[0]) {
                val canvas = lockHwCanvasIfPossible(surfaceHolder)
                val firstBubbleRange = abs(targetRadii[0] - mBubbles[0].currentRadius)

                // The gradient factor is tied to all Bubble drawing events.
                // If target gradient factor height is above (if we're expanding) or below (if
                // we aren't expanding) the current factor, wait to change the gradient factor
                // so it's smooth.
                val gradientFactor = if (isExpansion)
                    maxOf(1 - firstBubbleRange / ranges[0], mCurrentGradientFactor)
                else
                    minOf(1 - firstBubbleRange / ranges[0], mCurrentGradientFactor)
                drawCanvasBackground(canvas, if (isNightMode) 0f else 1f, gradientFactor)

                for (bubble in mBubbles) {
                    val index = mBubbles.indexOf(bubble)
                    val currentRange = targetRadii[index] - bubble.currentRadius
                    val speedModifier = getSpeedModifier(abs(ranges[index]), abs(currentRange))
                    bubble.currentRadius +=
                            addToRadius[index] * speedModifier * if (isExpansion) 1 else -1
                    bubble.currentRadius = if (isExpansion)
                        minOf(bubble.currentRadius, targetRadii[index])
                    else
                        maxOf(bubble.currentRadius, targetRadii[index])
                }
                drawBubbles(canvas)
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
        }

        private fun getBubbleInBounds(x: Int, y: Int): Bubble? {
            for (bubble in mBubbles) {
                if ((x - bubble.baseX.toDouble()).pow(2) + (y - bubble.baseY.toDouble()).pow(2) <
                        bubble.baseRadius.toDouble().pow(2)) {
                    return bubble
                }
            }
            return null
        }

        private fun getBubbleFromTouchEvent(event: MotionEvent): Bubble? {
            val x = event.x.toInt()
            val y = event.y.toInt()
            return getBubbleInBounds(x, y)
        }

        private fun adjustBubbleCoordinates(factor: Float) {
            for (bubble in mBubbles) {
                // Convert Bubble coordinates into coordinate plane compatible coordinates
                val halfWidth = mSurfaceWidth / 2
                val halfHeight = mSurfaceHeight / 2
                var distanceFromXInt = halfWidth - bubble.currentX
                if (distanceFromXInt < halfWidth) distanceFromXInt *= -1f
                var distanceFromYInt = halfHeight - bubble.currentY
                if (distanceFromYInt < halfHeight) distanceFromYInt *= -1f

                bubble.currentX = bubble.baseX - distanceFromXInt * factor
                bubble.currentY = bubble.baseY - distanceFromYInt * factor
            }
        }

        private fun regenAllBubbles() {
            mBubbles.clear()
            while (true) {
                val bubble = genRandomBubble()
                if (bubble != null) {
                    mBubbles.add(bubble)
                } else {
                    // If bubble is null, the max overlap retry count was
                    // exceeded, stop adding bubbles
                    break
                }
            }
        }

        private fun genRandomBubble(): Bubble? {
            val random = Random
            var radius = 0
            var x = 0
            var y = 0
            var overlapCount = 0

            /* Generate random radii and coordinates until we:
             *  A. Generate dimensions that don't overlap other Bubbles, or
             *  B. Exceed the retry count, in which case we return null
             */
            while (bubbleOverlaps(x, y, radius) || radius == 0) {
                radius = random.nextInt(minBubbleRadius, maxBubbleRadius)
                x = random.nextInt(radius + bubblePadding,mSurfaceWidth - radius - bubblePadding)
                y = random.nextInt(radius + bubblePadding,mSurfaceHeight - radius - bubblePadding)
                if (++overlapCount > overlapRetryCount) {
                    return null
                }
            }
            val colorPair = randomColorPairFromResource
            return Bubble(x, y, radius, getBubbleOutlinePaint(colorPair[0]),
                    getBubbleFillPaint(colorPair[1]))
        }

        private fun getSpeedModifier(range: Float, toGo: Float): Float {
            val adjustedRange = range / 2
            var speedModifier = toGo / adjustedRange
            if (speedModifier > 1) {
                // Start bringing the modifier back down when we reach half the range
                speedModifier = 2 - speedModifier
            }
            return maxOf(speedModifier,.001f)
        }

        private fun bubbleOverlaps(x: Int, y: Int, radius: Int): Boolean {
            for (bubble in mBubbles) {
                val distance = sqrt((x - bubble.baseX.toDouble()).pow(2)
                        + (y - bubble.baseY.toDouble()).pow(2))
                if (distance < radius + bubble.baseRadius + bubblePadding) {
                    return true
                }
            }
            return false
        }

        // Outline color index is even, fill color is after
        private val randomColorPairFromResource: IntArray
            get() {
                val random = Random
                val resourceColorArray = resources.getStringArray(R.array.wallpaper_bubble_colors)
                var outlineColorIndex = random.nextInt(resourceColorArray.size / 2) * 2
                return intArrayOf(Color.parseColor(resourceColorArray[outlineColorIndex]),
                        Color.parseColor(resourceColorArray[++outlineColorIndex]))
            }

        // Use holo blue as accent when on Android < Lollipop
        @get:ColorInt
        private val accentColor: Int
            get() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    // Use holo blue as accent when on Android < Lollipop
                    return Color.parseColor("#ff33b5e5")
                }
                val outValue = TypedValue()
                theme.resolveAttribute(android.R.attr.colorAccent, outValue, true)
                return outValue.data
            }

        fun getBubbleFillPaint(color: Int): Paint {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = color
            paint.style = Paint.Style.FILL
            return paint
        }

        fun getBubbleOutlinePaint(color: Int): Paint {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = color
            paint.strokeWidth = outlineSize.toFloat()
            paint.style = Paint.Style.STROKE
            return paint
        }

        @ColorInt
        private fun adjustColorAlpha(color: Int, factor: Float): Int {
            val alpha = (Color.alpha(color) * factor).roundToInt()
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            return Color.argb(alpha, red, green, blue)
        }
    }

    private class Bubble constructor(
            var baseX: Int,
            var baseY: Int,
            var baseRadius: Int,
            var outlinePaint: Paint,
            var fillPaint: Paint) {
        var currentX: Float = baseX.toFloat()
        var currentY: Float = baseY.toFloat()
        var currentRadius: Float = baseRadius.toFloat()
    }

    private val isNightMode: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    private fun lockHwCanvasIfPossible(surfaceHolder: SurfaceHolder): Canvas {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return surfaceHolder.lockCanvas()
        }
        return surfaceHolder.lockHardwareCanvas()
    }
}