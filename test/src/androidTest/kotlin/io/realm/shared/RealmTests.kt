/*
 * Copyright 2021 Realm Inc.
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
package io.realm.shared

import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.VersionId
import io.realm.isManaged
import io.realm.util.PlatformUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import test.link.Child
import test.link.Parent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds

@OptIn(ExperimentalTime::class)
class RealmTests {

    companion object {
        // Initial version of any new typed Realm (due to schema being written)
        private val INITIAL_VERSION = VersionId(2)
    }

    private lateinit var tmpDir: String
    private lateinit var realm: Realm

    private val configuration: RealmConfiguration by lazy {
        RealmConfiguration(
            path = "$tmpDir/default.realm",
            schema = setOf(Parent::class, Child::class)
        )
    }

    @BeforeTest
    fun setup() {
        tmpDir = PlatformUtils.createTempDir()
        val configuration = configuration
        realm = Realm.open(configuration)
    }

    @AfterTest
    fun tearDown() {
        if (!realm.isClosed()) {
            realm.close()
        }
        PlatformUtils.deleteTempDir(tmpDir)
    }


    @Test
    fun initialVersion() {
        assertEquals(INITIAL_VERSION, realm.version)
    }

    @Test
    fun versionIncreaseOnWrite() {
        assertEquals(INITIAL_VERSION, realm.version)
        realm.writeBlocking { /* Do Nothing */ }
        assertEquals(VersionId(3), realm.version)
    }

    @Test
    fun versionDoesNotChangeWhenCancellingWrite() {
        assertEquals(INITIAL_VERSION, realm.version)
        realm.writeBlocking { cancelWrite() }
        assertEquals(INITIAL_VERSION, realm.version)
    }

    @Test
    fun versionThrowsIfRealmIsClosed() {
        realm.close()
        assertFailsWith<IllegalStateException> { realm.version }
    }

    @Test
    fun versionInsideWriteIsLatest() {
        assertEquals(INITIAL_VERSION, realm.version)
        realm.writeBlocking {
            assertEquals(INITIAL_VERSION, realm.version)
            cancelWrite()
        }
        assertEquals(INITIAL_VERSION, realm.version)
    }

    @Test
    fun numberOfActiveVersions() {
        assertEquals(2, realm.getNumberOfActiveVersions())
        realm.writeBlocking {
            assertEquals(2, realm.getNumberOfActiveVersions())
        }
        assertEquals(2, realm.getNumberOfActiveVersions())
    }

    @Test
    @Ignore // FIXME This fails on MacOS only. Are versions cleaned up more aggressively there?
    fun throwsIfMaxNumberOfActiveVersionsAreExceeded() {
        realm.close()
        val config = RealmConfiguration.Builder(
            path = "$tmpDir/exceed-versions.realm",
            schema = setOf(Parent::class, Child::class)
        ).maxNumberOfActiveVersions(1).build()
        realm = Realm.open(config)
        // Pin the version, so when starting a new transaction on the first Realm,
        // we don't release older versions.
        val otherRealm = Realm.open(config)

        try {
            // FIXME Should be IllegalStateException
            assertFailsWith<RuntimeException> { realm.writeBlocking { } }
        } finally {
            otherRealm.close()
        }
    }

    @Suppress("invisible_member")
    @Test
    fun write() = runBlocking {
        val name = "Realm"
        val child: Child = realm.write {
            this.copyToRealm(Child()).apply { this.name = name }
        }
        assertEquals(name, child.name)
        val objects = realm.objects<Child>()
        val childFromResult = objects[0]
        assertEquals(name, childFromResult.name)
    }

    @Suppress("invisible_member")
    @Test
    fun exceptionInWriteWillRollback() = runBlocking {
        class CustomException : Exception()

        assertFailsWith<CustomException> {
            realm.write {
                val name = "Realm"
                this.copyToRealm(Child()).apply { this.name = name }
                throw CustomException()
            }
        }
        assertEquals(0, realm.objects<Child>().size)
    }

    @Test
    fun writeBlocking() {
        val managedChild = realm.writeBlocking { copyToRealm(Child().apply { name = "John" }) }
        assertTrue(managedChild.isManaged())
        assertEquals("John", managedChild.name)
    }

    @Suppress("invisible_member")
    @Test
    fun writeBlockingAfterWrite() = runBlocking {
        val name = "Realm"
        val child: Child = realm.write {
            this.copyToRealm(Child()).apply { this.name = name }
        }
        assertEquals(name, child.name)
        assertEquals(1, realm.objects<Child>().size)

        realm.writeBlocking {
            this.copyToRealm(Child()).apply { this.name = name }
        }
        Unit
    }

    @Suppress("invisible_member")
    @Test
    fun exceptionInWriteBlockingWillRollback() {
        class CustomException : Exception()
        assertFailsWith<CustomException> {
            realm.writeBlocking {
                val name = "Realm"
                this.copyToRealm(Child()).apply { this.name = name }
                throw CustomException()
            }
        }
        assertEquals(0, realm.objects<Child>().size)
    }

    @Test
    @Suppress("invisible_member")
    fun simultaneousWritesAreSerialized() = runBlocking {
        val jobs: List<Job> = IntRange(0, 9).map {
            launch {
                realm.write {
                    copyToRealm(Parent())
                }
            }
        }
        jobs.map { it.join() }

        // Ensure that all writes are actually committed
        realm.close()
        assertTrue(realm.isClosed())
        realm = Realm.open(configuration)
        assertEquals(10, realm.objects(Parent::class).size)
    }

    @Test
    @Suppress("invisible_member")
    fun writeBlockingInsideWriteThrows() {
        runBlocking {
            realm.write {
                assertFailsWith<IllegalStateException> {
                    realm.writeBlocking { }
                }
            }
        }
    }

    @Test
    fun writeBlockIngInsideWriteBlockingThrows() {
        realm.writeBlocking {
            assertFailsWith<IllegalStateException> {
                realm.writeBlocking { }
            }
        }
    }

    @Test
    @Suppress("invisible_member")
    fun close() = runBlocking {
        realm.write {
            copyToRealm(Parent())
        }
        realm.close()
        assertTrue(realm.isClosed())

        realm = Realm.open(configuration)
        assertEquals(1, realm.objects(Parent::class).size)
    }

    @Test
    @Suppress("invisible_member")
    fun closeCausesOngoingWriteToThrow() = runBlocking {
        val mutex = Mutex(true)
        val write = async {
            assertFailsWith<IllegalStateException> {
                realm.write {
                    mutex.unlock()
                    PlatformUtils.sleep(1000.milliseconds)
                }
            }
        }
        mutex.lock()
        realm.close()
        assert(write.await() is IllegalStateException)
    }

    @Test
    @Suppress("invisible_member")
    fun writeAfterCloseThrows() = runBlocking {
        realm.close()
        assertTrue(realm.isClosed())
        assertFailsWith<IllegalStateException> {
            realm.write {
                copyToRealm(Child())
            }
        }
        Unit
    }



    @Test
    @Suppress("invisible_member")
    fun closingRealmInsideWriteThrows() {
        runBlocking {
            realm.write {
                assertFailsWith<IllegalStateException> {
                    realm.close()
                }
            }
        }
        assertFalse(realm.isClosed())
    }

    @Test
    fun closingRealmInsideWriteBlockingThrows() {
        realm.writeBlocking {
            assertFailsWith<IllegalStateException> {
                realm.close()
            }
        }
        assertFalse(realm.isClosed())
    }

    @Test
    @Suppress("invisible_member")
    fun coroutineCancelCausesRollback() = runBlocking {
        val mutex = Mutex(true)
        val job = async {
            realm.write {
                copyToRealm(Parent())
                mutex.unlock()
                PlatformUtils.sleep(1000.milliseconds)
            }
        }
        mutex.lock()
        job.cancelAndJoin()

        // Ensure that write is not committed
        realm.close()
        assertTrue(realm.isClosed())
        realm = Realm.open(configuration)
        assertEquals(0, realm.objects(Parent::class).size)
    }

    @Test
    @Suppress("invisible_member")
    fun writeAfterCoroutineCancel() = runBlocking {
        val mutex = Mutex(true)
        val job = async {
            realm.write {
                copyToRealm(Parent())
                mutex.unlock()
                PlatformUtils.sleep(1000.milliseconds)
            }
        }

        mutex.lock()
        job.cancelAndJoin()

        // Verify that we can do other writes after cancel
        realm.write {
            copyToRealm(Parent())
        }

        // Ensure that only one write is actually committed
        realm.close()
        assertTrue(realm.isClosed())
        realm = Realm.open(configuration)
        assertEquals(1, realm.objects(Parent::class).size)
    }

}
