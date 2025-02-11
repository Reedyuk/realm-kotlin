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
import io.realm.entities.Sample
import io.realm.entities.schema.SchemaVariations
import io.realm.schema.ListPropertyType
import io.realm.schema.RealmPropertyType
import io.realm.schema.RealmStorageType
import io.realm.schema.ValuePropertyType
import io.realm.test.platform.PlatformUtils
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private val SCHEMA_VARIATION_CLASS_NAME = SchemaVariations::class.simpleName!!

/**
 * Test of public schema API.
 *
 * This test suite doesn't exhaust all modeling features, but should have full coverage of the
 * schema API code paths.
 */
public class RealmSchemaTests {

    private lateinit var tmpDir: String
    private lateinit var realm: Realm

    @BeforeTest
    fun setup() {
        tmpDir = PlatformUtils.createTempDir()
        val configuration =
            RealmConfiguration.Builder(schema = setOf(SchemaVariations::class, Sample::class))
                .path("$tmpDir/default.realm").build()
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
    fun realmClass() {
        val schema = realm.schema()

        assertEquals(2, schema.classes.size)

        val schemaVariationsDescriptor = schema[SCHEMA_VARIATION_CLASS_NAME]
            ?: fail("Couldn't find class")
        assertEquals(SCHEMA_VARIATION_CLASS_NAME, schemaVariationsDescriptor.name)
        assertEquals("string", schemaVariationsDescriptor.primaryKey?.name)

        val sampleName = "Sample"
        val sampleDescriptor = schema[sampleName] ?: fail("Couldn't find class")
        assertEquals(sampleName, sampleDescriptor.name)
        assertNull(sampleDescriptor.primaryKey)
    }

    @Test
    fun realmClass_notFound() {
        val schema = realm.schema()
        assertNull(schema["non-existing_class"])
    }

    @Test
    fun realmProperty() {
        val schema = realm.schema()

        val schemaVariationsDescriptor = schema[SCHEMA_VARIATION_CLASS_NAME]
            ?: fail("Couldn't find class")

        schemaVariationsDescriptor["string"]!!.run {
            assertEquals("string", name)
            type.run {
                assertIs<ValuePropertyType>(this)
                assertEquals(RealmStorageType.STRING, storageType)
                assertFalse(isNullable)
                assertTrue(isPrimaryKey)
                assertFalse(isIndexed)
            }
            assertFalse(isNullable)
        }
        schemaVariationsDescriptor["nullableString"]!!.run {
            assertEquals("nullableString", name)
            type.run {
                assertIs<ValuePropertyType>(this)
                assertEquals(RealmStorageType.STRING, storageType)
                assertTrue(isNullable)
                assertFalse(isPrimaryKey)
                assertTrue(isIndexed)
            }
            assertTrue(isNullable)
        }
        schemaVariationsDescriptor["stringList"]!!.run {
            assertEquals("stringList", name)
            type.run {
                assertIs<ListPropertyType>(this)
                assertEquals(RealmStorageType.STRING, storageType)
                assertFalse(this.isNullable)
            }
            assertFalse(isNullable)
        }
        schemaVariationsDescriptor["nullableStringList"]!!.run {
            assertEquals("nullableStringList", name)
            type.run {
                assertIs<ListPropertyType>(this)
                assertEquals(RealmStorageType.STRING, storageType)
                assertTrue(this.isNullable)
            }
            assertFalse(isNullable)
        }
    }

    @Test
    fun realmProperty_notFound() {
        val schema = realm.schema()
        val schemaVariationDescriptor = schema[SCHEMA_VARIATION_CLASS_NAME]!!
        assertNull(schemaVariationDescriptor["non-existing-property"])
    }

    // This test is just showing how we could use the public Schema API to perform exhaustive tests.
    // It overlaps with the TypeDescriptor infrastructure, so we should probably just update the
    // type descriptor infrastructure to use this information or make the various TypeDescriptor
    // properties available through the public schema API, ex.
    //
    // class ValuePropertyType {
    //     companion object {
    //         val supportedStorageTypes: Set<RealmStorageTypes> = setOf( ....)
    //     }
    // }
    @Test
    @Suppress("NestedBlockDepth")
    fun schema_optionCoverag() {
        // Property options
        @Suppress("invisible_member")
        val propertyTypeMap =
            RealmPropertyType.subTypes.map { it to RealmStorageType.values().toMutableSet() }.toMap()
                .toMutableMap()

        val schema = realm.schema()

        // Verify properties of SchemaVariations
        val classDescriptor = schema["SchemaVariations"] ?: fail("Couldn't find class")
        assertEquals("SchemaVariations", classDescriptor.name)
        for (property in classDescriptor.properties) {
            property.type.run {
                propertyTypeMap.getValue(this::class).remove(this.storageType)
            }
        }

        assertTrue(
            propertyTypeMap.none
            { (_, v) -> v.isNotEmpty() },
            "Field types not exhausted: $propertyTypeMap"
        )
    }
}
