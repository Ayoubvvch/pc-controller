package com.remote.pccontroller

import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView

class ZoomTouchListener(private val imageView: ImageView) : View.OnTouchListener {

    private val matrix = Matrix()
    private val savedMatrix = Matrix()

    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()

    private var currentScale = 1f
    private val minScale = 1f
    private val maxScale = 5f

    private var isInitialized = false

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    private val scaleDetector = ScaleGestureDetector(
        imageView.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val newScale = currentScale * scaleFactor
                if (newScale in minScale..maxScale) {
                    currentScale = newScale
                    matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                    fixBounds()
                    imageView.imageMatrix = matrix
                }
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        imageView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale > 1.2f) {
                    resetZoom()
                } else {
                    val targetScale = 2.5f
                    val factor = targetScale / currentScale
                    currentScale = targetScale
                    matrix.postScale(factor, factor, e.x, e.y)
                    fixBounds()
                    imageView.imageMatrix = matrix
                }
                return true
            }
        }
    )

    fun resetZoom() {
        val drawable = imageView.drawable ?: return
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()

        if (viewWidth <= 0 || viewHeight <= 0 || drawableWidth <= 0 || drawableHeight <= 0) return

        matrix.reset()
        val scale = minOf(viewWidth / drawableWidth, viewHeight / drawableHeight)
        val dx = (viewWidth - drawableWidth * scale) / 2f
        val dy = (viewHeight - drawableHeight * scale) / 2f

        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)
        currentScale = 1f

        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.imageMatrix = matrix
        isInitialized = true
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!isInitialized && imageView.drawable != null && imageView.width > 0) {
            resetZoom()
        }

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                start.set(event.x, event.y)
                mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(0) - event.getX(1)
                val y = event.getY(0) - event.getY(1)
                val dist = Math.sqrt((x * x + y * y).toDouble()).toFloat()
                if (dist > 10f) {
                    savedMatrix.set(matrix)
                    mid.set((event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f)
                    mode = ZOOM
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && currentScale > 1.05f) {
                    matrix.set(savedMatrix)
                    val dx = event.x - start.x
                    val dy = event.y - start.y
                    matrix.postTranslate(dx, dy)
                    fixBounds()
                    imageView.imageMatrix = matrix
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                fixBounds()
                imageView.imageMatrix = matrix
            }
        }
        return true
    }

    private fun fixBounds() {
        val drawable = imageView.drawable ?: return
        val rect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        matrix.mapRect(rect)

        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()

        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() <= viewWidth) {
            deltaX = (viewWidth - rect.width()) / 2f - rect.left
        } else if (rect.left > 0) {
            deltaX = -rect.left
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right
        }

        if (rect.height() <= viewHeight) {
            deltaY = (viewHeight - rect.height()) / 2f - rect.top
        } else if (rect.top > 0) {
            deltaY = -rect.top
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom
        }

        matrix.postTranslate(deltaX, deltaY)
    }
}
