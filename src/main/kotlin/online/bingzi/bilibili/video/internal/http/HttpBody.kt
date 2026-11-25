package online.bingzi.bilibili.video.internal.http

import okhttp3.ResponseBody
import java.io.IOException

/** 对解压后的实际读取量设上限；未知长度及伪造Content-Length同样受限。 */
internal object HttpBody {
    const val MAX_BYTES = 1024L * 1024
    fun read(body: ResponseBody?): String {
        if (body == null) return ""
        if (body.contentLength() > MAX_BYTES) throw IOException("B站响应超出1MiB限制")
        val source = body.source()
        if (source.request(MAX_BYTES + 1)) throw IOException("B站响应超出1MiB限制")
        return source.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }
}
