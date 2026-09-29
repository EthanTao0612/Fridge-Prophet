package com.fridgeprophet.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** 上传前允许的最长边（像素）。 */
private const val UPLOAD_MAX_EDGE_PX = 1600

/** 重编码时的 JPEG 质量。85 是「看不出差别」和「体积明显变小」的平衡点。 */
private const val UPLOAD_JPEG_QUALITY = 85

/** 体积小于这个值、且尺寸也没超标，就原样上传，不做重编码。 */
private const val RECOMPRESS_THRESHOLD_BYTES = 800L * 1024

/* ------------------------------------------------------------------
 *  对外两个入口：相册选图 / 相机拍照
 *
 *  ⚠️ **两条路径必须都压。** 2026-09-29 加压缩时就踩过一次：
 *  只改了相册那条，相机（CameraX 直接写文件，不走 Uri）漏了 ——
 *  而相机恰恰是最常用的入口。加新的图片来源时，
 *  记得也把它接到 compressToCache 上。
 * ------------------------------------------------------------------ */

/**
 * 把相册返回的 `content://` Uri 变成缓存目录里的真实文件，**顺便压小**。
 *
 * ## 为什么要复制，不能直接用 Uri
 *
 * OkHttp 上传需要 File 或 InputStream，而 Uri 只是「一个引用」：
 * 它指向的是相册应用的数据，授权可能随时失效，也可能指向云端还没下载下来的照片。
 * 在后台线程直接读它失败率不低，而且失败信息很难看懂。
 * 先落到自己的缓存目录，之后怎么读都稳。
 *
 * ## 为什么按真实 MIME 决定扩展名
 *
 * 后端按 `content_type` 做白名单校验，只放行 jpeg/png/webp。
 * 如果一律存成 `.jpg` 再报 `image/jpeg`，一张 PNG 会以 jpeg 的名义上传，
 * 后端和 Supabase 都可能按错误类型处理；反过来把 PNG 报成 jpeg 会被 415 拒掉。
 * 所以扩展名和 MIME 必须**成对**地来自同一个 `getType()` 结果。
 *
 * @param prefix 文件名前缀，便于在缓存目录里区分来源（avatar / scan / post / ...）。
 * @return (文件, MIME)；读不到返回 null，调用方需要处理这种情况。
 */
fun uriToCacheFile(context: Context, uri: Uri, prefix: String): Pair<File, String>? {
    val resolver = context.contentResolver
    val sourceMime = resolver.getType(uri) ?: "image/jpeg"
    val open: () -> InputStream? = { runCatching { resolver.openInputStream(uri) }.getOrNull() }

    val (width, height) = readBounds(open)
    if (width <= 0 || height <= 0) {
        // 读不出尺寸，多半不是图片。原样复制，让后端的类型/大小校验去兜底
        return copyRaw(context, uri, prefix, sourceMime)
    }

    val sourceSize = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull() ?: -1L

    val tooLarge = max(width, height) > UPLOAD_MAX_EDGE_PX
    val tooHeavy = sourceSize > RECOMPRESS_THRESHOLD_BYTES
    if (!tooLarge && !tooHeavy) {
        return copyRaw(context, uri, prefix, sourceMime)
    }

    val compressed = compressToCache(context, prefix, open)
    // 压缩失败就退回原样复制 —— 压缩不该让整个上传功能不可用
    return compressed?.let { it to "image/jpeg" } ?: copyRaw(context, uri, prefix, sourceMime)
}

/**
 * 相机拍出来的原图同样要压。
 *
 * CameraX 是**直接往 File 里写**的，不走 Uri 那条路，
 * 所以单独开一个入口，共用同一套压缩逻辑。
 *
 * 压缩成功后会把原图删掉 —— 它就在缓存目录里，一张几 MB，留着白占空间。
 * 压缩失败则原样返回，不阻断上传。
 */
fun shrinkCapturedPhoto(context: Context, source: File, prefix: String = "scan"): File {
    val open: () -> InputStream? = { runCatching { FileInputStream(source) }.getOrNull() }

    val (width, height) = readBounds(open)
    if (width <= 0 || height <= 0) return source

    val tooLarge = max(width, height) > UPLOAD_MAX_EDGE_PX
    val tooHeavy = source.length() > RECOMPRESS_THRESHOLD_BYTES
    if (!tooLarge && !tooHeavy) return source

    val compressed = compressToCache(context, prefix, open) ?: return source
    source.delete()
    return compressed
}

/* ------------------------------------------------------------------
 *  内部实现
 * ------------------------------------------------------------------ */

/** 只读图片头拿尺寸。**不能直接解码整张图** —— 5000 万像素的位图是 200MB，会 OOM。 */
private fun readBounds(open: () -> InputStream?): Pair<Int, Int> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { open()?.use { BitmapFactory.decodeStream(it, null, bounds) } }
    return bounds.outWidth to bounds.outHeight
}

/** 原样复制。尺寸和体积都合格时走这条路 —— 保住 PNG 透明通道和原始画质。 */
private fun copyRaw(context: Context, uri: Uri, prefix: String, mime: String): Pair<File, String>? {
    val ext = when {
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        else -> "jpg"
    }
    val file = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.$ext")

    return try {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        stream.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file to mime
    } catch (e: IOException) {
        // 复制失败时把半截文件删掉，否则会在缓存目录里留下一个 0 字节的垃圾
        file.delete()
        null
    }
}

/**
 * 降采样解码 → 精确缩放 → 补 EXIF 旋转 → 重编码成 JPEG。
 *
 * 任何一步失败都返回 null，由调用方决定退回什么 ——
 * **压缩失败不该让整个上传功能不可用**。
 */
private fun compressToCache(context: Context, prefix: String, open: () -> InputStream?): File? {
    val (width, height) = readBounds(open)

    val decoded = runCatching {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(width, height, UPLOAD_MAX_EDGE_PX)
        }
        open()?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull() ?: return null

    // 降采样只保证「不小于」目标边长，可能还是偏大，再精确缩一次
    val scaled = runCatching { scaleToMaxEdge(decoded, UPLOAD_MAX_EDGE_PX) }.getOrDefault(decoded)

    // 补上 EXIF 旋转 —— 不做这一步，竖着拍的照片会变成横的
    val upright = runCatching { applyExifRotation(open, scaled) }.getOrDefault(scaled)

    val file = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.jpg")
    val ok = runCatching {
        file.outputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, out)
        }
    }.getOrDefault(false)

    // 及时回收。一张 4000×3000 的位图是 48MB，连续拍照不回收很容易 OOM
    listOf(decoded, scaled, upright).distinct().forEach { if (!it.isRecycled) it.recycle() }

    if (!ok || !file.exists() || file.length() == 0L) {
        file.delete()
        return null
    }
    return file
}

/**
 * 算出最接近且不小于 1 的 2 的幂次降采样倍数。
 *
 * 只接受 2 的幂是因为 BitmapFactory 对非 2 的幂会向下取到最近的 2 的幂，
 * 传 3 和传 2 效果一样，不如自己算清楚。
 * 条件是 `longest / 2 >= maxEdge` 而不是 `longest > maxEdge`，
 * 保证降采样后**不会小于**目标尺寸（宁可多留一点像素，也不要糊）。
 */
private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    val longest = max(width, height)
    while (longest / (sample * 2) >= maxEdge) {
        sample *= 2
    }
    return sample
}

/** 按最长边缩到 maxEdge 以内。本来就够小就原样返回，不做无谓的重建。 */
private fun scaleToMaxEdge(source: Bitmap, maxEdge: Int): Bitmap {
    val longest = max(source.width, source.height)
    if (longest <= maxEdge) return source

    val ratio = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        source,
        (source.width * ratio).roundToInt().coerceAtLeast(1),
        (source.height * ratio).roundToInt().coerceAtLeast(1),
        true,
    )
}

/**
 * 读 EXIF 的 Orientation，需要时把图转正。
 *
 * `BitmapFactory` **不读 EXIF 的 Orientation 标签**。原样复制字节时 EXIF 跟着走，
 * 所以没这个问题；一旦解码再重新编码，旋转信息就丢了 ——
 * 竖着拍的照片会变成横的，AI 看到的是一台躺倒的冰箱。
 *
 * 用系统自带的 `android.media.ExifInterface`，**故意不加 `androidx.exifinterface` 依赖** ——
 * 这个项目的依赖版本矩阵很脆（加 Coil 3 触发过 Gradle 依赖图序列化异常），
 * 能不加依赖就不加。
 *
 * 读不到或值异常时按「不旋转」处理：宁可不动，也不要转错。
 */
private fun applyExifRotation(open: () -> InputStream?, source: Bitmap): Bitmap {
    val orientation = runCatching {
        open()?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        // 剩下两种（transpose / transverse）是翻转叠加旋转，极其罕见，
        // 按不旋转处理 —— 转错了比不转更糟
        else -> return source
    }

    return runCatching {
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }.getOrDefault(source)
}
