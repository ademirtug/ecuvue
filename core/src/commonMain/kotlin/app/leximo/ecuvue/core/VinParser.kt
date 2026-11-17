package app.leximo.ecuvue.core

object VinParser {
    fun valid(vin: String): Boolean = Regex("^[A-HJ-NPR-Z0-9]{17}$").matches(vin) && vin.toSet().size > 1
    private class Frames(val length: Int, val data: MutableList<Int>, var next: Int = 1)
    fun parse(response: String, protocol: Int): String? {
        val payloads = mutableListOf<List<Int>>()
        val can = protocol in 6..9
        val frames = mutableMapOf<String, Frames>()
        val legacy = mutableMapOf<String, MutableMap<Int, List<Int>>>()
        val invalid = mutableSetOf<String>()
        for (raw in response.uppercase().split(Regex("[\r\n]+"))) {
            var line = raw.replace(Regex("\\s+"), "")
            val header = if (can) { if (protocol == 7 || protocol == 9) 8 else 3 } else 6
            if (line.length < header + 4 || !line.all { it in "0123456789ABCDEF" }) continue
            val address = line.take(header)
            line = line.drop(header)
            if (can && line.length % 2 != 0 && line.startsWith('8')) line = line.drop(1)
            val bytes = line.hexBytes() ?: continue
            if (can) {
                val type = bytes.first() shr 4
                if (type == 1 && bytes.size == 8) {
                    if (address in frames) invalid += address
                    frames[address] = Frames(((bytes[0] and 15) shl 8) or bytes[1], bytes.drop(2).toMutableList())
                } else if (type == 2) {
                    val frame = frames[address]
                    if (frame == null || bytes.size != 8 || (bytes[0] and 15) != frame.next || frame.data.size >= frame.length) invalid += address
                    else { frame.data.addAll(bytes.drop(1)); frame.next = (frame.next + 1) and 15 }
                }
            } else if (bytes.size >= 7 && bytes[0] == 0x49 && bytes[1] == 2) {
                val records = legacy.getOrPut(address) { mutableMapOf() }
                if (bytes[2] in records) invalid += address
                records[bytes[2]] = bytes.subList(3, 7)
            }
        }
        frames.forEach { (address, frame) ->
            if (address !in invalid && frame.length == 20 && frame.data.size >= frame.length) payloads += frame.data.take(frame.length)
        }
        legacy.forEach { (address, records) ->
            if (address !in invalid && records.size == 5 && (1..5).all { it in records }) {
                val data = (1..5).flatMap { records.getValue(it) }
                if (data.take(3).all { it == 0 }) payloads += listOf(0x49, 2, 1) + data.drop(3)
            }
        }
        var numbered: MutableList<Int>? = null
        var expected = 0
        var numberedInvalid = false
        for (raw in response.uppercase().split(Regex("[\r\n]+"))) {
            val match = Regex("^\\s*([0-9A-F]+):\\s*([0-9A-F ]+)\\s*$").matchEntire(raw)
            if (match != null) {
                val sequence = match.groupValues[1].toInt(16)
                if (sequence == 0) {
                    if (!numberedInvalid) numbered?.let { payloads += it.toList() }
                    numbered = mutableListOf(); expected = 0; numberedInvalid = false
                }
                val bytes = match.groupValues[2].replace(" ", "").hexBytes()
                if (numbered == null || sequence != expected || bytes == null) numberedInvalid = true
                else { numbered.addAll(bytes); expected++ }
            } else {
                val hex = raw.replace(Regex("\\s+"), "")
                if (hex.length == 40 && hex.startsWith("490201")) hex.hexBytes()?.let { payloads += it }
            }
        }
        if (!numberedInvalid) numbered?.let { payloads += it.toList() }
        return payloads.filter { it.size == 20 && it.take(3) == listOf(0x49, 2, 1) }
            .map { it.drop(3).map(Int::toChar).joinToString("") }.filter(::valid).toSet().singleOrNull()
    }
}
