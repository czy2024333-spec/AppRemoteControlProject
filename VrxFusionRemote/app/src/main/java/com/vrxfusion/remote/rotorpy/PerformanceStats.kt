package com.vrxfusion.remote.rotorpy

import kotlin.math.ceil

/** 单调时钟计时；记录全部有效运动请求的结果，不把失败/超时混入成功P95。 */
class PerformanceStats {
    private val buckets=LongArray(5002) // 1ms上取整，最后一桶表示>5000ms，内存不随运行时间增长。
    private var attempts=0L; private var successes=0L; private var failures=0L
    private var maxMs=0.0
    @Synchronized fun reset() { buckets.fill(0); attempts=0; successes=0; failures=0; maxMs=0.0 }
    @Synchronized fun record(ms: Double,success: Boolean) {
        require(ms.isFinite() && ms>=0)
        attempts++
        if (success) {
            successes++; maxMs=maxOf(maxMs,ms)
            buckets[ceil(ms).toInt().coerceIn(0,5001)]++
        } else failures++
    }
    @Synchronized fun snapshot(): AckStats {
        val target=ceil(successes*.95).toLong(); var count=0L; var p95: Double?=null
        if (successes>0) for (i in buckets.indices) {
            count+=buckets[i]
            if (count>=target) { p95=if (i==5001) maxMs else i.toDouble(); break }
        }
        return AckStats(attempts,successes,failures,p95,maxMs)
    }
}
data class AckStats(val attempts: Long,val successes: Long,val failures: Long,val p95Ms: Double?,val maxMs: Double)

/** 窗口频率包含缺帧时间；接收与Compose提交分别采样，重复步号不充数。 */
class StateRate {
    private val times=java.util.ArrayDeque<Long>()
    private var last=""
    @Synchronized fun reset() { times.clear(); last="" }
    @Synchronized fun record(identity: String,now: Long=monoMs()) {
        if (identity==last) return
        last=identity; times.addLast(now); prune(now)
        while (times.size>4096) times.removeFirst()
    }
    private fun prune(now: Long) { while (times.isNotEmpty() && now-times.first>5000) times.removeFirst() }
    @Synchronized fun hz(now: Long=monoMs()): Double {
        prune(now)
        if (times.size<2) return 0.0
        return (times.size-1)*1000.0/maxOf(1000L,now-times.first)
    }
}
