package mihon.core.common.extensions

import kotlinx.serialization.json.JsonObject

// ANZ -->
val JsonObjectEmpty = JsonObject(emptyMap())

val JsonObjectEmptyBytes = byteArrayOf(0x7B, 0x7D)

val JsonObject.Companion.EMPTY: JsonObject
    inline get() = JsonObjectEmpty
// ANZ <--
