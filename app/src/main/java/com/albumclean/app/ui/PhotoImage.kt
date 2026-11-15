package com.albumclean.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.albumclean.app.data.Photo
import kotlinx.coroutines.*
import kotlin.math.min
import kotlin.math.sqrt

private class LoadedImage(val bitmap: Bitmap? = null, val error: Boolean = false)
private val imageCache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

internal fun decode(context: Context, uri: String, maxPixels: Int, maxEdge: Int): Bitmap {
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val factor = min(1.0, min(maxEdge.toDouble() / maxOf(w, h), sqrt(maxPixels.toDouble() / (w.toDouble() * h))))
        decoder.setTargetSize(maxOf(1, (w * factor).toInt()), maxOf(1, (h * factor).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

@Composable
fun PhotoImage(photo: Photo, modifier: Modifier = Modifier, thumbnail: Boolean = false) {
    val context = LocalContext.current
    var retry by remember(photo.key) { mutableIntStateOf(0) }
    val cacheKey = "${photo.key}:$thumbnail"
    val loaded by produceState<LoadedImage?>(null, cacheKey, retry) {
        value = withContext(Dispatchers.IO) {
            imageCache.get(cacheKey)?.let { return@withContext LoadedImage(it) }
            try {
                val bitmap = decode(context, photo.uri, if (thumbnail) 160_000 else 3_000_000, if (thumbnail) 400 else 2048)
                imageCache.put(cacheKey, bitmap)
                LoadedImage(bitmap)
            } catch (error: Exception) { LoadedImage(error = true) }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            loaded?.bitmap != null -> Image(loaded!!.bitmap!!.asImageBitmap(), "照片：${photo.name}", Modifier.fillMaxSize(), contentScale = if (thumbnail) ContentScale.Crop else ContentScale.Fit)
            loaded?.error == true -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { retry++ }.padding(12.dp)) {
                Text("暂时无法读取", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (!thumbnail) Text("轻触重试 · 或重新授权照片", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            else -> CircularProgressIndicator(Modifier.size(if (thumbnail) 20.dp else 28.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
fun ZoomPhoto(photo: Photo, modifier: Modifier = Modifier, onScale: (Float) -> Unit, onError: () -> Unit) {
    val currentOnScale by rememberUpdatedState(onScale)
    val currentOnError by rememberUpdatedState(onError)
    key(photo.key) {
        AndroidView(factory = { ZoomPhotoView(it, photo.uri, { scale -> currentOnScale(scale) }, { currentOnError() }) }, modifier = modifier)
    }
}

/** A dedicated viewer keeps two-finger gestures isolated from the sorting card. */
class ZoomPhotoView(context: Context, private val uri: String, private val onScale: (Float) -> Unit, private val onError: () -> Unit) : View(context) {
    private var scope: CoroutineScope? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var animator: ValueAnimator? = null
    private var fit = 1f
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            changeZoom((zoom * detector.scaleFactor).coerceIn(1f, 8f), detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            animator?.cancel()
            val start = zoom
            val target = if (zoom > 1.1f) 1f else 3f
            animator = ValueAnimator.ofFloat(start, target).apply {
                duration = 220
                addUpdateListener { changeZoom(it.animatedValue as Float, e.x, e.y) }
                start()
            }
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); return true }
    })

    init { contentDescription = "照片详情，双指缩放，放大后拖动查看，双击放大或复原"; isClickable = true }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val viewerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = viewerScope
        viewerScope.launch {
            try {
                bitmap = withContext(Dispatchers.IO) { decode(context, uri, 3_000_000, 2048) }
                recalculateFit(); invalidate()
                // Upgrade after the preview appears. Bound allocation for very large panoramas.
                val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
                val maxPixels = min(16_000_000, memoryClass * 1024 * 1024 / 12)
                val detailed = withContext(Dispatchers.IO) { decode(context, uri, maxPixels, 8192) }
                bitmap = detailed
                recalculateFit(); invalidate()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (bitmap == null) onError() }
        }
    }
    override fun onDetachedFromWindow() { animator?.cancel(); scope?.cancel(); bitmap = null; super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { recalculateFit(); clamp() }
    private fun recalculateFit() { bitmap?.let { fit = min(width.toFloat() / it.width, height.toFloat() / it.height) } }
    private fun clamp() {
        bitmap?.let {
            val maxX = maxOf(0f, (it.width * fit * zoom - width) / 2)
            val maxY = maxOf(0f, (it.height * fit * zoom - height) / 2)
            panX = panX.coerceIn(-maxX, maxX); panY = panY.coerceIn(-maxY, maxY)
        }
    }
    private fun changeZoom(target: Float, focusX: Float, focusY: Float) {
        val ratio = target / zoom
        panX = (panX - (focusX - width / 2)) * ratio + focusX - width / 2
        panY = (panY - (focusY - height / 2)) * ratio + focusY - height / 2
        zoom = target; clamp(); onScale(zoom); invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let {
            canvas.save()
            canvas.translate(width / 2f + panX, height / 2f + panY)
            canvas.scale(fit * zoom, fit * zoom)
            canvas.drawBitmap(it, -it.width / 2f, -it.height / 2f, paint)
            canvas.restore()
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        // Cancel a previous animation before GestureDetector may start a new double-tap.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) animator?.cancel()
        scaler.onTouchEvent(event); gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (!scaler.isInProgress && event.pointerCount == 1 && zoom > 1f) {
                    panX += event.x - lastX; panY += event.y - lastY; clamp(); invalidate()
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(index); lastY = event.getY(index)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (zoom <= 1.02f) { zoom = 1f; panX = 0f; panY = 0f; invalidate() }
        }
        return true
    }
}
