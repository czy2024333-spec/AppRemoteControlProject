package com.vrxfusion.remote.video

import java.io.EOFException
import java.io.InputStream

/** 本期电脑组件采用带Content-Length的MJPEG；每帧和头部均有限制，避免无界缓存。 */
class MjpegReader(private val input: InputStream,private val boundary: String) {
    init { require(boundary.matches(Regex("[A-Za-z0-9_-]{1,70}"))) { "无效的视频分隔符" } }
    private fun line(): String {
        val bytes=ArrayList<Byte>()
        while (true) {
            val value=input.read()
            if (value<0) throw EOFException("视频流已结束")
            if (value==10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
            require(bytes.size<1024) { "视频头过长" }; bytes.add(value.toByte())
        }
    }
    fun nextFrame(): ByteArray {
        var marker=line(); var blanks=0
        while (marker.isEmpty() && blanks++<4) marker=line()
        require(marker=="--$boundary") { "视频分隔符不匹配" }
        var length: Int?=null; var jpeg=false; var count=0
        while (true) {
            val header=line(); if (header.isEmpty()) break
            require(++count<=16) { "视频头过多" }
            when(header.substringBefore(':').trim().lowercase()) {
                "content-length" -> length=header.substringAfter(':').trim().toIntOrNull()
                "content-type" -> jpeg=header.substringAfter(':').trim().equals("image/jpeg",true)
            }
        }
        require(jpeg && length!=null && length in 4..(2*1024*1024)) { "视频帧格式或长度不支持" }
        val bytes=ByteArray(length!!); var offset=0
        while (offset<bytes.size) {
            val read=input.read(bytes,offset,bytes.size-offset)
            if (read<0) throw EOFException("视频帧未接收完整")
            if (read==0) continue
            offset+=read
        }
        require(bytes[0]==0xFF.toByte() && bytes[1]==0xD8.toByte() &&
            bytes[bytes.size-2]==0xFF.toByte() && bytes.last()==0xD9.toByte()) { "不是有效JPEG帧" }
        return bytes
    }
    companion object {
        fun boundary(contentType: String): String {
            require(contentType.substringBefore(';').trim().equals("multipart/x-mixed-replace",true)) { "不是MJPEG视频流" }
            val value=contentType.split(';').drop(1).map { it.trim() }.firstOrNull { it.startsWith("boundary=",true) }
                ?.substringAfter('=')?.trim()?.trim('"') ?: error("视频流缺少分隔符")
            require(value.matches(Regex("[A-Za-z0-9_-]{1,70}"))) { "视频分隔符不支持" }; return value
        }
    }
}
