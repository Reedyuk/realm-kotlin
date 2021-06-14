/*
 * Copyright 2020 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.realm

import io.realm.internal.RealmReference
import io.realm.internal.SuspendableWriter
import io.realm.internal.runBlocking
import io.realm.interop.NativePointer
import io.realm.interop.RealmInterop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// TODO API-PUBLIC Document platform specific internals (RealmInitilizer, etc.)
class Realm private constructor(configuration: RealmConfiguration, dbPointer: NativePointer) :
    BaseRealm(configuration, dbPointer) {

    private val writer: SuspendableWriter = SuspendableWriter(this)
    private val realmPointerMutex = Mutex()

    /**
     * FIXME Update docs when design is settled
     * Returns an ID identifying any Realm data at the point in time this method is called.
     *
     * This is done by pairing a reference to the public Realm instance alongside the current `dbPointer`.
     * For live Realms, the `dbPointer` point to a underlying live SharedRealm that might mutate. For frozen
     * Realms the `dbPointer` points to a frozen SharedRealm that is guaranteed to remain the same, even if
     * the public Realm advance to a later version.
     *
     * The public Realm instance part of the ID can also mutate, so care must be taken if any methods on that
     * Realm is used.
     */
    var updateableRealm: kotlinx.atomicfu.AtomicRef<RealmReference> =
        kotlinx.atomicfu.atomic<RealmReference>(RealmReference(this, dbPointer))
    override var realm: RealmReference
        get() {
            return updateableRealm.value
        }
        set(value) {
            updateableRealm!!.value = value
        }

    companion object {
        /**
         * Default name for Realm files unless overridden by [RealmConfiguration.Builder.name].
         */
        public const val DEFAULT_FILE_NAME = "default.realm"

        /**
         * Default tag used by log entries
         */
        public const val DEFAULT_LOG_TAG = "REALM"

        fun open(realmConfiguration: RealmConfiguration): Realm {
            // TODO API-INTERNAL
            //  IN Android use lazy property delegation init to load the shared library use the
            //  function call (lazy init to do any preprocessing before starting Realm eg: log level etc)
            //  or implement an init method which is a No-OP in iOS but in Android it load the shared library
            val realm =
                Realm(realmConfiguration, RealmInterop.realm_open(realmConfiguration.nativeConfig))
            realm.log.info("Opened Realm: ${realmConfiguration.path}")
            return realm
        }
    }

    /**
     * Open a Realm instance. This instance grants access to an underlying Realm file defined by
     * the provided [RealmConfiguration].
     *
     * FIXME Figure out how to describe the constructor better
     * FIXME Once the implementation of this class moves to the frozen architecture
     *  this constructor should be the primary way to open Realms (as you only need
     *  to do it once pr. app).
     */
    public constructor(configuration: RealmConfiguration) :
            this(configuration, RealmInterop.realm_open(configuration.nativeConfig))

    /**
     * Modify the underlying Realm file in a suspendable transaction on the default Realm
     * dispatcher.
     *
     * NOTE: Objects and results retrieved before a write are no longer valid. This restriction
     * will be lifted when the frozen architecture is fully in place.
     *
     * The write transaction always represent the latest version of data in the Realm file, even if
     * the calling Realm not yet represent this.
     *
     * Write transactions automatically commit any changes made when the closure returns unless
     * [MutableRealm.cancelWrite] was called.
     *
     * @param block function that should be run within the context of a write transaction.
     * @return any value returned from the provided write block. If this is a RealmObject it is
     * frozen before returning it.
     * @see [RealmConfiguration.writeDispatcher]
     */
    // TODO Would we be able to offer a per write error handler by adding a CoroutineExceptinoHandler
    internal suspend fun <R> write(block: MutableRealm.() -> R): R {
        @Suppress("TooGenericExceptionCaught") // FIXME https://github.com/realm/realm-kotlin/issues/70
        try {
            val (nativePointer, versionId, result) = this.writer.write(block)
            // Update the user facing Realm before returning the result.
            // That way, querying the Realm right after the `write` completes will return
            // the written data. Otherwise, we would have to wait for the Notifier thread
            // to detect it and update the user Realm.
            updateRealmPointer(nativePointer, versionId)
            // FIXME What if the result is of a different version than the realm (some other
            //  write transaction finished before)
            return result
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * Modify the underlying Realm file while blocking the calling thread until the transaction is
     * done. Write transactions automatically commit any changes made when the closure returns
     * unless [MutableRealm.cancelWrite] was called.
     *
     * The write transaction always represent the latest version of data in the Realm file, even if the calling
     * Realm not yet represent this.
     *
     * @param block function that should be run within the context of a write transaction.
     * @return any value returned from the provided write block.
     *
     * @throws IllegalStateException if invoked inside an existing transaction.
     */
    fun <R> writeBlocking(block: MutableRealm.() -> R): R {
        writer.checkInTransaction("Cannot initiate transaction when already in a write transaction")
        return runBlocking {
            write(block)
        }
    }

    private suspend fun updateRealmPointer(newRealm: NativePointer, newVersion: VersionId) {
        realmPointerMutex.withLock {
            log.debug("$version -> $newVersion")
            if (newVersion >= version) {
                // FIXME Currently we need to thaw the realm to be a live realm to be able to
                //  continue doing writeBlocking transactions.
                // FIXME Update Thawing to allow writes a no longer needed as writes are now all
                //  done through the SuspendableWriter, but I image that current tests are expecting
                //  objects to be live!?
                val dbPointer = RealmInterop.realm_thaw(newRealm)
                // FIXME The thawed realm need a read transaction to be able to retieve version, etc.
                RealmInterop.realm_begin_read(dbPointer)
                realm = RealmReference(this, dbPointer)
            }
        }
    }

    /**
     * Close this Realm and all underlying resources. Accessing any methods or Realm Objects after this
     * method has been called will then an [IllegalStateException].
     */
    public override fun close() {
        writer.checkInTransaction("Cannot close in a transaction block")
        super.close()
        // TODO There is currently nothing that tears down the dispatcher
    }
}
