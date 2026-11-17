package app.leximo.ecuvue.core

internal fun String.hexBytes(): List<Int>? {
    if (isEmpty() || length % 2 != 0 || !all { it in "0123456789ABCDEFabcdef" }) return null
    return chunked(2).map { it.toInt(16) }
}
internal fun Int.hex2(): String = toString(16).padStart(2, '0').uppercase()

object ElmParser {
    fun supportedPids(response: String, service: Int, base: Int): Set<Int>? = supportedPidPayloads(payloads(response, service), base)
    fun supportedPidPayloads(payloads: List<List<Int>>, base: Int, frame: Int? = null): Set<Int>? {
        val result = mutableSetOf<Int>()
        var found = false
        val offset = if (frame == null) 1 else 2
        for (bytes in payloads) {
            if (bytes.size < offset + 4 || bytes[0] != base || (frame != null && bytes[1] != frame)) continue
            found = true
            val mask = (bytes[offset] shl 24) or (bytes[offset + 1] shl 16) or (bytes[offset + 2] shl 8) or bytes[offset + 3]
            for (bit in 0..31) if ((mask and (1 shl (31 - bit))) != 0) result += base + bit + 1
        }
        return if (found) result else null
    }

    /** ECU identity is retained for single-frame freeze-frame PIDs. */
    fun addressedPayloads(response: String, service: Int, protocol: Int): Map<String, List<Int>> {
        val result = mutableMapOf<String, List<Int>>()
        val duplicates = mutableSetOf<String>()
        for (raw in response.uppercase().split(Regex("[\r\n]+"))) {
            val line = raw.replace(Regex("\\s+"), "")
            val address: String
            val bytes: List<Int>
            if (protocol in 6..9) {
                val header = if (protocol == 7 || protocol == 9) 8 else 3
                if (line.length < header + 4 || !line.all { it in "0123456789ABCDEF" }) continue
                address = line.take(header)
                val framed = line.drop(header).hexBytes() ?: continue
                val length = framed.first()
                if (length !in 1..7 || framed.size < length + 1) continue
                bytes = framed.subList(1, length + 1)
            } else {
                val framed = line.hexBytes() ?: continue
                if (framed.size < 4) continue
                address = framed[2].hex2()
                bytes = framed.drop(3)
            }
            if (bytes.firstOrNull() != service) continue
            if (address in result) duplicates += address
            result[address] = bytes.drop(1)
        }
        duplicates.forEach(result::remove)
        return result
    }

    fun dtc(a: Int, b: Int): String? = if (a == 0 && b == 0) null
        else "${"PCBU"[a shr 6]}${(a shr 4) and 3}${(a and 15).toString(16)}${b.hex2()}".uppercase()

    fun payloads(response: String, service: Int): List<List<Int>> {
        val messages = mutableListOf<MutableList<Int>>()
        var current: MutableList<Int>? = null
        var expectedLine = 0
        for (raw in response.uppercase().split(Regex("[\r\n]+"))) {
            var line = raw.trim()
            val prefix = Regex("^([0-9A-F]+):\\s*").find(line)
            val number = prefix?.groupValues?.get(1)?.toInt(16)
            line = line.replace(Regex("^[0-9A-F]+:\\s*"), "").replace(" ", "")
            val bytes = line.hexBytes() ?: continue
            if (number != null && number > 0) {
                if (number != expectedLine) throw DiagnosticException("scanFailed", "Out-of-order ELM response")
                current?.addAll(bytes)
                expectedLine++
            } else if (bytes.first() == service) {
                current = bytes.drop(1).toMutableList()
                messages += current
                expectedLine = 1
            } else {
                current = null
                expectedLine = 0
            }
        }
        return messages
    }

    fun faults(response: String, service: Int, kind: FaultKind, counted: Boolean = false): List<Fault> {
        val codes = linkedSetOf<String>()
        for (payload in payloads(response, service)) {
            if (payload.isEmpty()) throw DiagnosticException("scanFailed")
            val bytes = if (counted) {
                val length = payload.first() * 2
                if (payload.size < length + 1) throw DiagnosticException("scanFailed", "Truncated CAN code list")
                payload.subList(1, length + 1)
            } else {
                if (payload.size % 2 != 0) throw DiagnosticException("scanFailed", "Truncated code list")
                payload
            }
            bytes.chunked(2).forEach { dtc(it[0], it[1])?.let(codes::add) }
        }
        return codes.map { Fault(it, kind) }
    }
    fun pid(response: String, pid: Int): List<Int>? = payloads(response, 0x41).firstOrNull { it.firstOrNull() == pid }?.drop(1)

    fun parseClearResponse(response: String, protocol: Int?): ClearResult {
        fun replies(service: Int): List<List<Int>> {
            val result = payloads(response, service).toMutableList()
            if (protocol != null) result += addressedPayloads(response, service, protocol).values
            if (protocol in 6..9) for (raw in response.uppercase().split(Regex("[\r\n]+"))) {
                val bytes = raw.replace(Regex("\\s+"), "").hexBytes() ?: continue
                val length = bytes.first()
                if (length in 1..7 && bytes.size >= length + 1 && bytes[1] == service) result += bytes.subList(2, length + 1)
            }
            return result
        }
        val acknowledged = replies(0x44).isNotEmpty()
        val negatives = replies(0x7F).filter { it.size >= 2 && it[0] == 4 }.map { it[1] }.toSet()
        val rejected = negatives.filter { it != 0x78 }
        val details = "Request: 04; protocol: ${protocol ?: "unknown"}; response: ${response.trim().take(512).ifEmpty { "<empty>" }}"
        if (acknowledged) return ClearResult(if (rejected.isEmpty()) ClearOutcome.ACKNOWLEDGED else ClearOutcome.PARTIAL,
            if (rejected.isEmpty()) "codesCleared" else "clearPartiallyAccepted", details)
        if (rejected.isNotEmpty()) {
            val key = when (rejected.first()) {
                0x22, in 0x81..0x8D, 0x92, 0x93 -> "clearConditionsNotCorrect"
                0x33, 0x34 -> "clearAccessDenied"
                0x11, 0x12, 0x31, 0x7E, 0x7F -> "clearNotSupported"
                0x21 -> "clearBusy"
                else -> "clearRejected"
            }
            return ClearResult(ClearOutcome.REJECTED, key, details)
        }
        return ClearResult(ClearOutcome.UNCONFIRMED, if (0x78 in negatives) "clearPending" else "clearUnconfirmed", details)
    }
}
