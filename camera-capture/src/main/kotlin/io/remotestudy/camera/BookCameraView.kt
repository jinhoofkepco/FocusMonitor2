package io.remotestudy.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.util.AttributeSet
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Preview, still capture, and low-rate luminance analysis for the fixed book setup.
 *
 * Thread contract:
 * - Public methods may be called from any thread.
 * - bind results, capture results, and frame observations are delivered on main.
 * - JPEG processing and ImageAnalysis run serially on one background executor.
 * - [close] is terminal and must be called when the owning UI is disposed. A view
 *   detach also closes it as a safety net.
 */
class BookCameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), AutoCloseable {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "book-camera-worker").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val closed = AtomicBoolean(false)
    private val pendingBinds = ConcurrentHashMap.newKeySet<PendingBind>()
    private val pendingCaptures = ConcurrentHashMap.newKeySet<PendingCapture>()

    @Volatile
    private var bookRegion: BookRegion = BookRegion.DEFAULT

    @Volatile
    private var frameObservationListener: ((FrameObservation) -> Unit)? = null

    @Volatile
    private var imageCapture: ImageCapture? = null

    @Volatile
    private var preview: Preview? = null

    @Volatile
    private var imageAnalysis: ImageAnalysis? = null

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var requestedDetailMode = DetailCaptureMode.STANDARD_12_MP

    @Volatile
    private var appliedDetailMode = DetailCaptureMode.STANDARD_12_MP

    private var boundLifecycleOwner: LifecycleOwner? = null

    private val frameAnalyzer = FrameObservationAnalyzer { observation ->
        mainExecutor.execute {
            if (!closed.get()) frameObservationListener?.invoke(observation)
        }
    }

    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        // The monitoring frame must be complete. FILL_CENTER silently cuts away
        // the sides of a 4:3 camera frame on tall phones such as the Galaxy S23.
        scaleType = PreviewView.ScaleType.FIT_CENTER
    }
    private val guideView = CalibrationGuideView(context) { region ->
        bookRegion = region
        frameAnalyzer.setBookRegion(region)
    }
    private val hint = TextView(context).apply {
        text = "노란 책 영역을 직접 맞춰 주세요"
        setTextColor(Color.WHITE)
        setBackgroundColor(0x99000000.toInt())
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(guideView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            hint,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = dp(72)
                marginStart = dp(16)
                // StudentActivity reserves the top-right corner for connection state.
                marginEnd = dp(132)
            },
        )
    }

    /**
     * Waits until PreviewView can provide a display rotation, then binds all three
     * 4:3 use cases without a screen-shaped ViewPort crop. The result callback runs exactly once on
     * main, including a close while layout/provider work is pending.
     */
    fun bind(lifecycleOwner: LifecycleOwner, onResult: (Result<Unit>) -> Unit = {}) {
        boundLifecycleOwner = lifecycleOwner
        val request = PendingBind(onResult)
        pendingBinds += request
        runOnMain {
            if (closed.get()) {
                completeBind(request, Result.failure(IllegalStateException("BookCameraView is closed")))
                return@runOnMain
            }
            awaitPreviewDisplay(request, lifecycleOwner)
        }
    }

    private fun awaitPreviewDisplay(request: PendingBind, lifecycleOwner: LifecycleOwner) {
        var layoutListener: View.OnLayoutChangeListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
        val cleanup = {
            layoutListener?.let(previewView::removeOnLayoutChangeListener)
            attachListener?.let(previewView::removeOnAttachStateChangeListener)
            layoutListener = null
            attachListener = null
        }
        val tryBind = fun() {
            if (request.completed.get() || closed.get()) return
            val display = previewView.display ?: return
            if (!request.previewReadyConsumed.compareAndSet(false, true)) return
            cleanup()
            request.cleanupOnMain = null
            bindUseCases(request, lifecycleOwner, display.rotation)
        }

        layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> tryBind() }
        attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.post { tryBind() }
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        request.cleanupOnMain = cleanup
        layoutListener?.let(previewView::addOnLayoutChangeListener)
        attachListener?.let(previewView::addOnAttachStateChangeListener)
        tryBind()
        previewView.post { tryBind() }
    }

    private fun bindUseCases(
        request: PendingBind,
        lifecycleOwner: LifecycleOwner,
        targetRotation: Int,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                if (request.completed.get()) return@addListener
                if (closed.get()) {
                    completeBind(
                        request,
                        Result.failure(IllegalStateException("BookCameraView is closed")),
                    )
                    return@addListener
                }

                var candidateProvider: ProcessCameraProvider? = null
                var candidateAnalysis: ImageAnalysis? = null
                runCatching {
                    val provider = providerFuture.get()
                    candidateProvider = provider
                    val candidatePreview = Preview.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(
                                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                                )
                                .build(),
                        )
                        .setTargetRotation(targetRotation)
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(detailResolutionSelector(requestedDetailMode))
                        .setTargetRotation(targetRotation)
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setTargetRotation(targetRotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    candidateAnalysis = analysis

                    imageAnalysis?.clearAnalyzer()
                    provider.unbindAll()
                    frameAnalyzer.reset()
                    analysis.setAnalyzer(cameraExecutor, frameAnalyzer)
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        candidatePreview,
                        capture,
                        analysis,
                    )
                    check(!closed.get()) { "BookCameraView was closed while binding" }
                    cameraProvider = provider
                    imageCapture = capture
                    preview = candidatePreview
                    imageAnalysis = analysis
                    val captureSize = capture.resolutionInfo?.resolution
                    appliedDetailMode = if (
                        requestedDetailMode == DetailCaptureMode.ULTRA_50_MP &&
                        captureSize != null && captureSize.width.toLong() * captureSize.height >= 40_000_000L
                    ) {
                        DetailCaptureMode.ULTRA_50_MP
                    } else {
                        DetailCaptureMode.STANDARD_12_MP
                    }
                    candidateProvider = null
                    candidateAnalysis = null
                }.onSuccess {
                    completeBind(request, Result.success(Unit))
                }.onFailure { failure ->
                    candidateAnalysis?.clearAnalyzer()
                    candidateProvider?.unbindAll()
                    imageAnalysis?.clearAnalyzer()
                    imageAnalysis = null
                    imageCapture = null
                    preview = null
                    completeBind(request, Result.failure(failure))
                }
            },
            mainExecutor,
        )
    }

    /** Frame callbacks are delivered on main and stop after [close]. */
    fun setFrameObservationListener(listener: ((FrameObservation) -> Unit)?) {
        frameObservationListener = listener
    }

    /** Thread-safe; the next frame that passes the 1 fps gate becomes baseline. */
    fun armPresenceBaseline() {
        frameAnalyzer.armPresenceBaseline()
    }

    /**
     * Captures into a temporary JPEG under app cache, serially derives both
     * assets, and deletes the original in all success/failure paths. The callback
     * is delivered exactly once on main.
     */
    fun captureAssets(
        outputDir: File,
        assetId: String,
        capturedAtEpochMs: Long,
        callback: (Result<CaptureAssets>) -> Unit,
    ) {
        runOnMain {
            val validationFailure = validateCaptureRequest(outputDir, assetId, capturedAtEpochMs)
            if (validationFailure != null) {
                callback(Result.failure(validationFailure))
                return@runOnMain
            }
            if (closed.get()) {
                callback(Result.failure(IllegalStateException("BookCameraView is closed")))
                return@runOnMain
            }
            val capture = imageCapture
            if (capture == null) {
                callback(Result.failure(IllegalStateException("Camera is not bound")))
                return@runOnMain
            }

            val original = runCatching {
                File.createTempFile("remote-study-original-", ".jpg", context.cacheDir)
            }.getOrElse { failure ->
                callback(Result.failure(failure))
                return@runOnMain
            }
            val request = PendingCapture(original, callback)
            pendingCaptures += request

            try {
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(original).build(),
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            val result = runCatching {
                                CaptureAssetProcessor.process(
                                    originalJpeg = original,
                                    outputDir = outputDir,
                                    assetId = assetId,
                                capturedAtEpochMs = capturedAtEpochMs,
                                bookRegion = bookRegion,
                                detailCaptureMode = appliedDetailMode,
                            )
                            }
                            completeCapture(request, result)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            original.delete()
                            completeCapture(request, Result.failure(exception))
                        }
                    },
                )
            } catch (failure: Throwable) {
                original.delete()
                completeCapture(request, Result.failure(failure))
            }
        }
    }

    fun setCalibrated(calibrated: Boolean) {
        runOnMain {
            guideView.calibrated = calibrated
            hint.text = if (calibrated) {
                "배치 완료 · 판정 구역 촬영 중"
            } else {
                "노란 책 영역을 직접 맞춰 주세요"
            }
        }
    }

    /** Enables direct drag/resize of the yellow book rectangle. */
    fun setBookRegionEditingEnabled(enabled: Boolean) {
        runOnMain { guideView.editingEnabled = enabled }
    }

    fun setGuideVisible(visible: Boolean) {
        runOnMain {
            guideView.visibility = if (visible) View.VISIBLE else View.GONE
            hint.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    fun setBookRegion(region: BookRegion) {
        runOnMain {
            bookRegion = region
            frameAnalyzer.setBookRegion(region)
            guideView.setBookRegion(region)
        }
    }

    fun currentBookRegion(): BookRegion = bookRegion

    /** Rebinds only camera use cases; the host session timer continues independently. */
    fun applyDetailCaptureMode(
        mode: DetailCaptureMode,
        callback: (Result<CameraProfileResult>) -> Unit,
    ) {
        runOnMain {
            if (closed.get()) {
                callback(Result.failure(IllegalStateException("BookCameraView is closed")))
                return@runOnMain
            }
            val owner = boundLifecycleOwner
            if (owner == null) {
                requestedDetailMode = mode
                callback(Result.failure(IllegalStateException("Camera is not bound")))
                return@runOnMain
            }
            val currentSize = imageCapture?.resolutionInfo?.resolution
            if (currentSize != null && requestedDetailMode == mode && appliedDetailMode == mode) {
                callback(
                    Result.success(
                        CameraProfileResult(
                            requestedMode = mode,
                            appliedMode = mode,
                            width = currentSize.width,
                            height = currentSize.height,
                            ultra50MpAvailable = mode == DetailCaptureMode.ULTRA_50_MP,
                        ),
                    ),
                )
                return@runOnMain
            }
            requestedDetailMode = mode
            bind(owner) { result ->
                result.fold(
                    onSuccess = {
                        val size = imageCapture?.resolutionInfo?.resolution
                        if (size == null) {
                            callback(Result.failure(IllegalStateException("Camera resolution is unavailable")))
                        } else {
                            callback(
                                Result.success(
                                    CameraProfileResult(
                                        requestedMode = mode,
                                        appliedMode = appliedDetailMode,
                                        width = size.width,
                                        height = size.height,
                                        ultra50MpAvailable = appliedDetailMode == DetailCaptureMode.ULTRA_50_MP,
                                    ),
                                ),
                            )
                        }
                    },
                    onFailure = { failure ->
                        if (mode != DetailCaptureMode.ULTRA_50_MP) {
                            callback(Result.failure(failure))
                            return@fold
                        }
                        requestedDetailMode = DetailCaptureMode.STANDARD_12_MP
                        bind(owner) { fallback ->
                            fallback.fold(
                                onSuccess = {
                                    val size = imageCapture?.resolutionInfo?.resolution
                                    if (size == null) {
                                        callback(Result.failure(failure))
                                    } else {
                                        callback(
                                            Result.success(
                                                CameraProfileResult(
                                                    requestedMode = mode,
                                                    appliedMode = DetailCaptureMode.STANDARD_12_MP,
                                                    width = size.width,
                                                    height = size.height,
                                                    ultra50MpAvailable = false,
                                                ),
                                            ),
                                        )
                                    }
                                },
                                onFailure = { callback(Result.failure(it)) },
                            )
                        }
                    },
                )
            }
        }
    }

    /** Keeps CameraX output upright when the host handles configuration changes itself. */
    fun updateTargetRotation(rotation: Int) {
        runOnMain {
            preview?.targetRotation = rotation
            imageCapture?.targetRotation = rotation
            imageAnalysis?.targetRotation = rotation
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        frameObservationListener = null
        frameAnalyzer.close()

        val bindingFailure = IllegalStateException("BookCameraView was closed during binding")
        pendingBinds.toList().forEach { request ->
            completeBind(request, Result.failure(bindingFailure))
        }
        val captureFailure = IllegalStateException("BookCameraView was closed during capture")
        pendingCaptures.toList().forEach { request ->
            request.originalFile.delete()
            completeCapture(request, Result.failure(captureFailure))
        }

        runOnMain {
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
            imageAnalysis = null
            imageCapture = null
            preview = null
            cameraProvider = null
        }
        cameraExecutor.shutdownNow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        close()
    }

    private fun completeBind(request: PendingBind, result: Result<Unit>) {
        if (!request.completed.compareAndSet(false, true)) return
        pendingBinds -= request
        runOnMain {
            request.cleanupOnMain?.invoke()
            request.cleanupOnMain = null
            request.callback(result)
        }
    }

    private fun completeCapture(request: PendingCapture, result: Result<CaptureAssets>) {
        if (!request.completed.compareAndSet(false, true)) {
            result.getOrNull()?.let { assets ->
                assets.thumbnailFile.delete()
                assets.bookRoiFile.delete()
            }
            return
        }
        pendingCaptures -= request
        mainExecutor.execute { request.callback(result) }
    }

    private fun validateCaptureRequest(
        outputDir: File,
        assetId: String,
        capturedAtEpochMs: Long,
    ): Throwable? = when {
        assetId.isBlank() -> IllegalArgumentException("assetId must not be blank")
        !assetId.matches(Regex("[A-Za-z0-9._-]{1,96}")) ->
            IllegalArgumentException("assetId contains unsupported characters")
        capturedAtEpochMs < 0 -> IllegalArgumentException("capturedAtEpochMs must not be negative")
        outputDir.exists() && !outputDir.isDirectory ->
            IllegalArgumentException("outputDir must be a directory")
        else -> null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainExecutor.execute(block)
    }

    private fun detailResolutionSelector(mode: DetailCaptureMode): ResolutionSelector {
        val builder = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        return if (mode == DetailCaptureMode.ULTRA_50_MP) {
            builder
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                .setResolutionFilter(
                    ResolutionFilter { sizes, _ ->
                        sizes.sortedByDescending { it.width.toLong() * it.height }
                    },
                )
                .build()
        } else {
            builder
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(4_000, 3_000),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class PendingBind(
        val callback: (Result<Unit>) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        val previewReadyConsumed = AtomicBoolean(false)

        @Volatile
        var cleanupOnMain: (() -> Unit)? = null
    }

    private class PendingCapture(
        val originalFile: File,
        val callback: (Result<CaptureAssets>) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
    }
}

private class CalibrationGuideView(
    context: Context,
    private val onBookRegionChanged: (BookRegion) -> Unit,
) : android.view.View(context) {
    var calibrated: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var editingEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var bookRegion = BookRegion.DEFAULT
    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /** Camera use cases are fixed at 4:3; this is their complete FIT_CENTER area. */
    private fun cameraContentRect(): RectF {
        if (width <= 0 || height <= 0) return RectF()
        val landscape = width > height
        val contentAspect = if (landscape) 4f / 3f else 3f / 4f
        val viewAspect = width.toFloat() / height
        return if (viewAspect > contentAspect) {
            val contentWidth = height * contentAspect
            val left = (width - contentWidth) / 2f
            RectF(left, 0f, left + contentWidth, height.toFloat())
        } else {
            val contentHeight = width / contentAspect
            val top = (height - contentHeight) / 2f
            RectF(0f, top, width.toFloat(), top + contentHeight)
        }
    }

    private fun regionRect(content: RectF): RectF {
        val normalized = bookRegion.normalized()
        return RectF(
            content.left + normalized.left * content.width(),
            content.top + normalized.top * content.height(),
            content.left + normalized.right * content.width(),
            content.top + normalized.bottom * content.height(),
        )
    }

    fun setBookRegion(region: BookRegion) {
        bookRegion = region
        invalidate()
    }

    private val bookPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(14f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val book = regionRect(cameraContentRect())
        bookPaint.color = if (calibrated) Color.rgb(87, 214, 141) else Color.rgb(255, 196, 61)
        labelPaint.color = bookPaint.color
        canvas.drawRoundRect(book, dp(12f), dp(12f), bookPaint)
        val bookLabel = when {
            editingEnabled -> "책 영역 · 안쪽 이동 / 모서리 크기 조절"
            calibrated -> "책 영역 ✓"
            else -> "책 영역"
        }
        canvas.drawText(bookLabel, book.left, (book.top - dp(10f)).coerceAtLeast(dp(20f)), labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editingEnabled || width == 0 || height == 0) return false
        val content = cameraContentRect()
        if (content.isEmpty) return false
        val x = event.x.coerceIn(content.left, content.right)
        val y = event.y.coerceIn(content.top, content.bottom)
        val rect = regionRect(content)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = hitTest(x, y, rect)
                if (dragMode == DragMode.NONE) return false
                lastTouchX = x
                lastTouchY = y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return false
                val dx = (x - lastTouchX) / content.width()
                val dy = (y - lastTouchY) / content.height()
                updateRegion(dx, dy)
                lastTouchX = x
                lastTouchY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = dragMode != DragMode.NONE
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return handled
            }
        }
        return false
    }

    private fun hitTest(x: Float, y: Float, rect: RectF): DragMode {
        val radius = dp(34f)
        if (distanceSquared(x, y, rect.left, rect.top) <= radius * radius) return DragMode.TOP_LEFT
        if (distanceSquared(x, y, rect.right, rect.top) <= radius * radius) return DragMode.TOP_RIGHT
        if (distanceSquared(x, y, rect.left, rect.bottom) <= radius * radius) return DragMode.BOTTOM_LEFT
        if (distanceSquared(x, y, rect.right, rect.bottom) <= radius * radius) return DragMode.BOTTOM_RIGHT
        return if (rect.contains(x, y)) DragMode.MOVE else DragMode.NONE
    }

    private fun updateRegion(dx: Float, dy: Float) {
        val minimum = 0.12f
        var left = bookRegion.left
        var top = bookRegion.top
        var right = bookRegion.right
        var bottom = bookRegion.bottom
        when (dragMode) {
            DragMode.MOVE -> {
                val safeDx = dx.coerceIn(-left, 1f - right)
                val safeDy = dy.coerceIn(-top, 1f - bottom)
                left += safeDx; right += safeDx; top += safeDy; bottom += safeDy
            }
            DragMode.TOP_LEFT -> { left = (left + dx).coerceIn(0f, right - minimum); top = (top + dy).coerceIn(0f, bottom - minimum) }
            DragMode.TOP_RIGHT -> { right = (right + dx).coerceIn(left + minimum, 1f); top = (top + dy).coerceIn(0f, bottom - minimum) }
            DragMode.BOTTOM_LEFT -> { left = (left + dx).coerceIn(0f, right - minimum); bottom = (bottom + dy).coerceIn(top + minimum, 1f) }
            DragMode.BOTTOM_RIGHT -> { right = (right + dx).coerceIn(left + minimum, 1f); bottom = (bottom + dy).coerceIn(top + minimum, 1f) }
            DragMode.NONE -> return
        }
        bookRegion = BookRegion(left, top, right, bottom)
        onBookRegionChanged(bookRegion)
        invalidate()
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private fun NormalizedRegion.toRectF(width: Int, height: Int) = RectF(
        left * width,
        top * height,
        right * width,
        bottom * height,
    )

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private enum class DragMode { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
}
