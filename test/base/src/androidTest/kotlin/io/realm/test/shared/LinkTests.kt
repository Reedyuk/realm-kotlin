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
package io.realm.test.shared

import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.entities.link.Child
import io.realm.entities.link.Parent
import io.realm.objects
import io.realm.test.platform.PlatformUtils
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinkTests {

    lateinit var tmpDir: String
    lateinit var realm: Realm

    @BeforeTest
    fun setup() {
        tmpDir = PlatformUtils.createTempDir()
        val configuration = RealmConfiguration.Builder(schema = setOf(Parent::class, Child::class))
            .path("$tmpDir/default.realm").build()
        realm = Realm.open(configuration)
    }

    @AfterTest
    fun tearDown() {
        PlatformUtils.deleteTempDir(tmpDir)
    }

    @Test
    fun basics() {
        val name = "Realm"
        val parent = realm.writeBlocking {
            val parent = copyToRealm(Parent())
            val child = copyToRealm(Child())
            child.name = name

            assertNull(parent.child)
            parent.child = child
            assertNotNull(parent.child)
            parent
        }

        assertEquals(1, realm.objects(Parent::class).size)

        val child1 = realm.objects(Parent::class).first().child
        assertEquals(name, child1?.name)

        realm.writeBlocking {
            val parent: Parent = objects<Parent>().first()
            assertNotNull(parent.child)
            parent.child = null
            assertNull(parent.child)
        }

        assertNull(realm.objects(Parent::class)[0].child)
    }
}
