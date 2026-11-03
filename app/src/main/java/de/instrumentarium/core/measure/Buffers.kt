package de.instrumentarium.core.measure

/** Growable list of doubles without boxing. */
class DoubleList(initial: Int = 1024) {
    private var data = DoubleArray(initial)
    var size = 0; private set

    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
    fun toArray(): DoubleArray = data.copyOf(size)
}

/** Fixed-capacity ring buffer of (time, value) samples for live plotting. */
class SignalBuffer(val capacity: Int = 8192) {
    private val t = DoubleArray(capacity)
    private val v = DoubleArray(capacity)
    private var head = 0
    var size = 0; private set

    fun add(time: Double, value: Double) {
        t[head] = time; v[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun clear() { head = 0; size = 0 }

    private fun idx(i: Int) = (head - size + i + capacity) % capacity
    fun time(i: Int) = t[idx(i)]
    fun value(i: Int) = v[idx(i)]
    fun lastValue(): Double? = if (size == 0) null else value(size - 1)
    fun lastTime(): Double? = if (size == 0) null else time(size - 1)

    /** Calls [fn] for each sample with time >= [from], oldest first. */
    inline fun forEachSince(from: Double, fn: (Double, Double) -> Unit) {
        var lo = 0
        var hi = size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (time(mid) < from) lo = mid + 1 else hi = mid }
        for (i in maxOf(0, lo - 1) until size) fn(time(i), value(i))
    }
}
