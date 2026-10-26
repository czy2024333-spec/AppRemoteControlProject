package com.vrxfusion.remote.unified

/** 公开开发凭据快捷填写；只改变连接草稿，真实权限必须重新从后端确认。 */
data class ConnectionIdentity(val label: String,val token: String)
val connectionIdentities=listOf(
    ConnectionIdentity("操纵端1","demo-pilot-1-token-2026"),
    ConnectionIdentity("操纵端2","demo-pilot-2-token-2026"),
    ConnectionIdentity("操纵端3","demo-pilot-3-token-2026"),
    ConnectionIdentity("观察端","demo-observer-token-2026"))
