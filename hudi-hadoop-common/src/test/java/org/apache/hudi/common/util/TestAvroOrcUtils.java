/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.common.util;

import org.apache.hudi.common.testutils.HoodieCommonTestHarness;

import org.apache.avro.Schema;
import org.apache.orc.TypeDescription;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;

import static org.apache.hudi.common.testutils.HoodieTestDataGenerator.AVRO_SCHEMA;
import static org.apache.hudi.common.testutils.HoodieTestDataGenerator.TRIP_EXAMPLE_SCHEMA;
import static org.apache.hudi.common.testutils.HoodieTestDataGenerator.TRIP_SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests {@link AvroOrcUtils}.
 */
public class TestAvroOrcUtils extends HoodieCommonTestHarness {
  public static final TypeDescription ORC_SCHEMA = AvroOrcUtils.createOrcSchema(new Schema.Parser().parse(TRIP_EXAMPLE_SCHEMA));
  public static final TypeDescription ORC_TRIP_SCHEMA = AvroOrcUtils.createOrcSchema(new Schema.Parser().parse(TRIP_SCHEMA));

  public static List<Arguments> testCreateOrcSchemaArgs() {
    // the ORC schema is constructed in the order as AVRO_SCHEMA:
    // TRIP_SCHEMA_PREFIX, EXTRA_TYPE_SCHEMA, MAP_TYPE_SCHEMA, FARE_NESTED_SCHEMA, TIP_NESTED_SCHEMA, TRIP_SCHEMA_SUFFIX
    // The following types are tested:
    // DATE, DECIMAL, LONG, INT, BYTES, ARRAY, RECORD, MAP, STRING, FLOAT, DOUBLE, ENUM
    TypeDescription orcSchema = TypeDescription.fromString("struct<"
        + "timestamp:bigint,_row_key:string,partition_path:string,"
        + "trip_type:string,rider:string,driver:string,begin_lat:double,"
        + "begin_lon:double,end_lat:double,end_lon:double,"
        + "distance_in_meters:int,seconds_since_epoch:bigint,weight:float,nation:binary,"
        + "current_date:date,current_ts:bigint,height:decimal(10,6),"
        + "city_to_state:map<string,string>,"
        + "fare:struct<amount:double,currency:string>,"
        + "tip_history:array<struct<amount:double,currency:string>>,"
        + "_hoodie_is_deleted:boolean>");

    // Tests the types FIXED, UNION
    String structField = "{\"type\":\"record\", \"name\":\"fare\",\"fields\": "
        + "[{\"name\": \"amount\",\"type\": \"double\"},{\"name\": \"currency\", \"type\": \"string\"}]}";
    Schema avroSchemaWithMoreTypes = new Schema.Parser().parse(
        "{\"type\": \"record\"," + "\"name\": \"triprec\"," + "\"fields\": [ "
            + "{\"name\" : \"age\", \"type\":{\"type\": \"fixed\", \"size\": 16, \"name\": \"fixedField\" }},"
            + "{\"name\" : \"height\", \"type\": [\"int\", \"null\"] },"
            + "{\"name\" : \"id\", \"type\": [\"int\", \"string\"] },"
            + "{\"name\" : \"fare\", \"type\": [" + structField + ", \"null\"] }]}");
    TypeDescription orcSchemaWithMoreTypes = TypeDescription.fromString(
        "struct<age:binary,height:int,id:uniontype<int,string>,fare:struct<amount:double,currency:string>>");

    return Arrays.asList(
        Arguments.of(AVRO_SCHEMA, orcSchema),
        Arguments.of(avroSchemaWithMoreTypes, orcSchemaWithMoreTypes)
    );
  }

  @ParameterizedTest
  @MethodSource("testCreateOrcSchemaArgs")
  public void testCreateOrcSchema(Schema avroSchema, TypeDescription orcSchema) {
    TypeDescription convertedSchema = AvroOrcUtils.createOrcSchema(avroSchema);
    assertEquals(orcSchema, convertedSchema);
  }
}
