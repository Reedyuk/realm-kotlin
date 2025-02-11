package io.realm.internal.platform

@Suppress("MayBeConst") // Cannot make expect/actual const
actual val RUNTIME: String = "JVM"

actual fun threadId(): ULong {
    return Thread.currentThread().id.toULong()
}

actual fun <T> T.freeze(): T = this

actual val <T> T.isFrozen: Boolean
    get() = false

actual fun Any.ensureNeverFrozen() {}
