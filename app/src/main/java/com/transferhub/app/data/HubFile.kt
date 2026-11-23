package com.transferhub.app.data

enum class FileSource { RECEIVED, SHARED }

/**
 * 传送站中的一个文件。
 * - RECEIVED：其他设备上传进来的，保存在“下载/TransferHub”
 * - SHARED：本机分享出去的，供其他设备下载
 */
data class HubFile(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val uri: String,
    val source: FileSource,
    val time: Long,
    /** 文件是否是传送站私有目录里的副本（移除时一并删除） */
    val ownedCopy: Boolean = false,
)
