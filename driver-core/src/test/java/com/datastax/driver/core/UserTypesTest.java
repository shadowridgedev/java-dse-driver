/*
 *      Copyright (C) 2012-2016 DataStax Inc.
 *
 *      This software can be used solely with DataStax Enterprise. Please consult the license at
 *      http://www.datastax.com/terms/datastax-dse-driver-license-terms
 */
package com.datastax.driver.core;

import com.datastax.driver.core.utils.CassandraVersion;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Callable;

import static com.datastax.driver.core.ConditionChecker.check;
import static com.datastax.driver.core.Metadata.quote;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;

@CassandraVersion("2.1.0")
public class UserTypesTest extends CCMTestsSupport {

    private final static List<DataType> DATA_TYPE_PRIMITIVES = new ArrayList<DataType>(TestUtils.allPrimitiveTypes(TestUtils.getDesiredProtocolVersion()));

    static {
        DATA_TYPE_PRIMITIVES.remove(DataType.counter());
    }

    private final static List<DataType.Name> DATA_TYPE_NON_PRIMITIVE_NAMES =
            new ArrayList<DataType.Name>(EnumSet.of(DataType.Name.LIST, DataType.Name.SET, DataType.Name.MAP, DataType.Name.TUPLE));

    private final Callable<Boolean> userTableExists = new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
            return cluster().getMetadata().getKeyspace(keyspace).getTable("user") != null;
        }
    };

    @Override
    public void onTestContextInitialized() {
        String type1 = "CREATE TYPE phone (alias text, number text)";
        String type2 = "CREATE TYPE \"\"\"User Address\"\"\" (street text, \"ZIP\"\"\" int, phones set<frozen<phone>>)";
        String table = "CREATE TABLE user (id int PRIMARY KEY, addr frozen<\"\"\"User Address\"\"\">)";
        execute(type1, type2, table);
        // Ci tests fail with "unconfigured columnfamily user"
        check().that(userTableExists).before(5, MINUTES).becomesTrue();
    }

    /**
     * Basic write read test to ensure UDTs are stored and retrieved correctly.
     *
     * @throws Exception
     */
    @Test(groups = "short")
    public void simpleWriteReadTest() throws Exception {
        int userId = 0;
        PreparedStatement ins = session().prepare("INSERT INTO user(id, addr) VALUES (?, ?)");
        PreparedStatement sel = session().prepare("SELECT * FROM user WHERE id=?");

        UserType addrDef = cluster().getMetadata().getKeyspace(keyspace).getUserType(quote("\"User Address\""));
        UserType phoneDef = cluster().getMetadata().getKeyspace(keyspace).getUserType("phone");

        UDTValue phone1 = phoneDef.newValue().setString("alias", "home").setString("number", "0123548790");
        UDTValue phone2 = phoneDef.newValue().setString("alias", "work").setString("number", "0698265251");

        UDTValue addr = addrDef.newValue().setString("street", "1600 Pennsylvania Ave NW").setInt(quote("ZIP\""), 20500).setSet("phones", ImmutableSet.of(phone1, phone2));

        session().execute(ins.bind(userId, addr));

        Row r = session().execute(sel.bind(userId)).one();

        assertEquals(r.getInt("id"), 0);
        assertEquals(r.getUDTValue("addr"), addr);
    }

    /**
     * Run simpleWriteReadTest with unprepared requests.
     *
     * @throws Exception
     */
    @Test(groups = "short")
    public void simpleUnpreparedWriteReadTest() throws Exception {
        int userId = 1;
        session().execute("USE " + keyspace);
        UserType addrDef = cluster().getMetadata().getKeyspace(keyspace).getUserType(quote("\"User Address\""));
        UserType phoneDef = cluster().getMetadata().getKeyspace(keyspace).getUserType("phone");

        UDTValue phone1 = phoneDef.newValue().setString("alias", "home").setString("number", "0123548790");
        UDTValue phone2 = phoneDef.newValue().setString("alias", "work").setString("number", "0698265251");

        UDTValue addr = addrDef.newValue().setString("street", "1600 Pennsylvania Ave NW").setInt(quote("ZIP\""), 20500).setSet("phones", ImmutableSet.of(phone1, phone2));

        session().execute("INSERT INTO user(id, addr) VALUES (?, ?)", userId, addr);

        Row r = session().execute("SELECT * FROM user WHERE id=?", userId).one();

        assertEquals(r.getInt("id"), 1);
        assertEquals(r.getUDTValue("addr"), addr);
    }

    /**
     * Test for ensuring udts are defined in a particular keyspace.
     *
     * @throws Exception
     */
    @Test(groups = "short")
    public void nonExistingTypesTest() throws Exception {
        UserType addrDef = cluster().getMetadata().getKeyspace(keyspace).getUserType("address1");
        UserType phoneDef = cluster().getMetadata().getKeyspace(keyspace).getUserType("phone1");
        assertEquals(addrDef, null);
        assertEquals(phoneDef, null);

        addrDef = cluster().getMetadata().getKeyspace(keyspace).getUserType(quote("\"User Address\""));
        phoneDef = cluster().getMetadata().getKeyspace(keyspace).getUserType("phone");
        assertNotEquals(addrDef, null);
        assertNotEquals(phoneDef, null);

        // create keyspace
        String nonExistingKeyspace = keyspace + "_nonEx";
        session().execute("CREATE KEYSPACE " + nonExistingKeyspace + " " +
                "WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor': '1'}");
        session().execute("USE " + nonExistingKeyspace);

        addrDef = cluster().getMetadata().getKeyspace(nonExistingKeyspace).getUserType(quote("\"User Address\""));
        phoneDef = cluster().getMetadata().getKeyspace(nonExistingKeyspace).getUserType("phone");
        assertEquals(addrDef, null);
        assertEquals(phoneDef, null);

        session().execute("USE " + keyspace);

        addrDef = cluster().getMetadata().getKeyspace(keyspace).getUserType(quote("\"User Address\""));
        phoneDef = cluster().getMetadata().getKeyspace(keyspace).getUserType("phone");
        assertNotEquals(addrDef, null);
        assertNotEquals(phoneDef, null);
    }

    /**
     * Test for ensuring extra-lengthy udts are handled correctly.
     * Original code found in python-driver:integration.standard.test_udts.py:test_udt_sizes
     *
     * @throws Exception
     */
    @Test(groups = "short")
    public void udtSizesTest() throws Exception {
        int MAX_TEST_LENGTH = 1024;
        // create keyspace
        session().execute("CREATE KEYSPACE test_udt_sizes " +
                "WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor': '1'}");
        session().execute("USE test_udt_sizes");

        // create the seed udt
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_TEST_LENGTH; ++i) {
            sb.append(String.format("v_%s int", i));

            if (i + 1 < MAX_TEST_LENGTH)
                sb.append(",");
        }
        session().execute(String.format("CREATE TYPE lengthy_udt (%s)", sb.toString()));

        // create a table with multiple sizes of udts
        session().execute("CREATE TABLE mytable (k int PRIMARY KEY, v frozen<lengthy_udt>)");

        // hold onto the UserType for future use
        UserType udtDef = cluster().getMetadata().getKeyspace("test_udt_sizes").getUserType("lengthy_udt");

        // verify inserts and reads
        for (int i : Arrays.asList(0, 1, 2, 3, MAX_TEST_LENGTH)) {
            // create udt
            UDTValue createdUDT = udtDef.newValue();
            for (int j = 0; j < i; ++j) {
                createdUDT.setInt(j, j);
            }

            // write udt
            session().execute("INSERT INTO mytable (k, v) VALUES (0, ?)", createdUDT);

            // verify udt was written and read correctly
            UDTValue r = session().execute("SELECT v FROM mytable WHERE k=0")
                    .one().getUDTValue("v");
            assertEquals(r.toString(), createdUDT.toString());
        }
    }

    /**
     * Test for inserting various types of DATA_TYPE_PRIMITIVES into UDT's.
     * Original code found in python-driver:integration.standard.test_udts.py:test_primitive_datatypes
     *
     * @throws Exception
     */
    @Test(groups = "short")
    public void testPrimitiveDatatypes() throws Exception {
        // create keyspace
        session().execute("CREATE KEYSPACE testPrimitiveDatatypes " +
                "WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor': '1'}");
        session().execute("USE testPrimitiveDatatypes");

        // create UDT
        List<String> alpha_type_list = new ArrayList<String>();
        int startIndex = (int) 'a';
        for (int i = 0; i < DATA_TYPE_PRIMITIVES.size(); i++) {
            alpha_type_list.add(String.format("%s %s", Character.toString((char) (startIndex + i)),
                    DATA_TYPE_PRIMITIVES.get(i).getName()));
        }

        session().execute(String.format("CREATE TYPE alldatatypes (%s)", Joiner.on(',').join(alpha_type_list)));
        session().execute("CREATE TABLE mytable (a int PRIMARY KEY, b frozen<alldatatypes>)");

        // insert UDT data
        UserType alldatatypesDef = cluster().getMetadata().getKeyspace("testPrimitiveDatatypes").getUserType("alldatatypes");
        UDTValue alldatatypes = alldatatypesDef.newValue();

        for (int i = 0; i < DATA_TYPE_PRIMITIVES.size(); i++) {
            DataType dataType = DATA_TYPE_PRIMITIVES.get(i);
            String index = Character.toString((char) (startIndex + i));
            Object sampleData = PrimitiveTypeSamples.ALL.get(dataType);

            switch (dataType.getName()) {
                case ASCII:
                    alldatatypes.setString(index, (String) sampleData);
                    break;
                case BIGINT:
                    alldatatypes.setLong(index, ((Long) sampleData).longValue());
                    break;
                case BLOB:
                    alldatatypes.setBytes(index, (ByteBuffer) sampleData);
                    break;
                case BOOLEAN:
                    alldatatypes.setBool(index, ((Boolean) sampleData).booleanValue());
                    break;
                case DECIMAL:
                    alldatatypes.setDecimal(index, (BigDecimal) sampleData);
                    break;
                case DOUBLE:
                    alldatatypes.setDouble(index, ((Double) sampleData).doubleValue());
                    break;
                case DURATION:
                    alldatatypes.set(index, Duration.from(sampleData.toString()), Duration.class);
                case FLOAT:
                    alldatatypes.setFloat(index, ((Float) sampleData).floatValue());
                    break;
                case INET:
                    alldatatypes.setInet(index, (InetAddress) sampleData);
                    break;
                case TINYINT:
                    alldatatypes.setByte(index, (Byte) sampleData);
                    break;
                case SMALLINT:
                    alldatatypes.setShort(index, (Short) sampleData);
                    break;
                case INT:
                    alldatatypes.setInt(index, ((Integer) sampleData).intValue());
                    break;
                case TEXT:
                    alldatatypes.setString(index, (String) sampleData);
                    break;
                case TIMESTAMP:
                    alldatatypes.setTimestamp(index, ((Date) sampleData));
                    break;
                case DATE:
                    alldatatypes.setDate(index, ((LocalDate) sampleData));
                    break;
                case TIME:
                    alldatatypes.setTime(index, ((Long) sampleData));
                    break;
                case TIMEUUID:
                    alldatatypes.setUUID(index, (UUID) sampleData);
                    break;
                case UUID:
                    alldatatypes.setUUID(index, (UUID) sampleData);
                    break;
                case VARCHAR:
                    alldatatypes.setString(index, (String) sampleData);
                    break;
                case VARINT:
                    alldatatypes.setVarint(index, (BigInteger) sampleData);
                    break;
            }
        }

        PreparedStatement ins = session().prepare("INSERT INTO mytable (a, b) VALUES (?, ?)");
        session().execute(ins.bind(0, alldatatypes));

        // retrieve and verify data
        ResultSet rs = session().execute("SELECT * FROM mytable");
        List<Row> rows = rs.all();
        assertEquals(1, rows.size());

        Row row = rows.get(0);

        assertEquals(row.getInt("a"), 0);
        assertEquals(row.getUDTValue("b"), alldatatypes);
    }

    /**
     * Test for inserting various types of DATA_TYPE_NON_PRIMITIVE into UDT's
     * Original code found in python-driver:integration.standard.test_udts.py:test_nonprimitive_datatypes
     *
     * @throws Exception
     */
    @Test(groups = "short")
    public void testNonPrimitiveDatatypes() throws Exception {
        // create keyspace
        session().execute("CREATE KEYSPACE test_nonprimitive_datatypes " +
                "WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor': '1'}");
        session().execute("USE test_nonprimitive_datatypes");

        // counters and durations are not allowed inside collections
        DATA_TYPE_PRIMITIVES.remove(DataType.counter());
        DATA_TYPE_PRIMITIVES.remove(DataType.duration());

        // create UDT
        List<String> alpha_type_list = new ArrayList<String>();
        int startIndex = (int) 'a';
        for (int i = 0; i < DATA_TYPE_NON_PRIMITIVE_NAMES.size(); i++)
            for (int j = 0; j < DATA_TYPE_PRIMITIVES.size(); j++) {
                String typeString;
                if (DATA_TYPE_NON_PRIMITIVE_NAMES.get(i) == DataType.Name.MAP) {
                    typeString = (String.format("%s_%s %s<%s, %s>", Character.toString((char) (startIndex + i)),
                            Character.toString((char) (startIndex + j)), DATA_TYPE_NON_PRIMITIVE_NAMES.get(i),
                            DATA_TYPE_PRIMITIVES.get(j).getName(), DATA_TYPE_PRIMITIVES.get(j).getName()));
                } else if (DATA_TYPE_NON_PRIMITIVE_NAMES.get(i) == DataType.Name.TUPLE) {
                    typeString = (String.format("%s_%s frozen<%s<%s>>", Character.toString((char) (startIndex + i)),
                            Character.toString((char) (startIndex + j)), DATA_TYPE_NON_PRIMITIVE_NAMES.get(i),
                            DATA_TYPE_PRIMITIVES.get(j).getName()));
                } else {
                    typeString = (String.format("%s_%s %s<%s>", Character.toString((char) (startIndex + i)),
                            Character.toString((char) (startIndex + j)), DATA_TYPE_NON_PRIMITIVE_NAMES.get(i),
                            DATA_TYPE_PRIMITIVES.get(j).getName()));
                }
                alpha_type_list.add(typeString);
            }

        session().execute(String.format("CREATE TYPE alldatatypes (%s)", Joiner.on(',').join(alpha_type_list)));
        session().execute("CREATE TABLE mytable (a int PRIMARY KEY, b frozen<alldatatypes>)");

        // insert UDT data
        UserType alldatatypesDef = cluster().getMetadata().getKeyspace("test_nonprimitive_datatypes").getUserType("alldatatypes");
        UDTValue alldatatypes = alldatatypesDef.newValue();

        for (int i = 0; i < DATA_TYPE_NON_PRIMITIVE_NAMES.size(); i++)
            for (int j = 0; j < DATA_TYPE_PRIMITIVES.size(); j++) {
                DataType.Name name = DATA_TYPE_NON_PRIMITIVE_NAMES.get(i);
                DataType dataType = DATA_TYPE_PRIMITIVES.get(j);

                String index = Character.toString((char) (startIndex + i)) + "_" + Character.toString((char) (startIndex + j));
                Object sampleElement = PrimitiveTypeSamples.ALL.get(dataType);
                switch (name) {
                    case LIST:
                        alldatatypes.setList(index, Lists.newArrayList(sampleElement));
                        break;
                    case SET:
                        alldatatypes.setSet(index, Sets.newHashSet(sampleElement));
                        break;
                    case MAP:
                        alldatatypes.setMap(index, ImmutableMap.of(sampleElement, sampleElement));
                        break;
                    case TUPLE:
                        alldatatypes.setTupleValue(index, cluster().getMetadata().newTupleType(dataType).newValue(sampleElement));
                }
            }

        PreparedStatement ins = session().prepare("INSERT INTO mytable (a, b) VALUES (?, ?)");
        session().execute(ins.bind(0, alldatatypes));

        // retrieve and verify data
        ResultSet rs = session().execute("SELECT * FROM mytable");
        List<Row> rows = rs.all();
        assertEquals(1, rows.size());

        Row row = rows.get(0);

        assertEquals(row.getInt("a"), 0);
        assertEquals(row.getUDTValue("b"), alldatatypes);
    }

    /**
     * Test for ensuring nested UDT's are handled correctly.
     * Original code found in python-driver:integration.standard.test_udts.py:test_nested_registered_udts
     *
     * @throws Exception
     */
    @Test(groups = "short")
    public void udtNestedTest() throws Exception {
        final int MAX_NESTING_DEPTH = 4;
        // create keyspace
        session().execute("CREATE KEYSPACE udtNestedTest " +
                "WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor': '1'}");
        session().execute("USE udtNestedTest");

        // create UDT
        session().execute("CREATE TYPE depth_0 (age int, name text)");

        for (int i = 1; i <= MAX_NESTING_DEPTH; i++) {
            session().execute(String.format("CREATE TYPE depth_%s (value frozen<depth_%s>)", String.valueOf(i), String.valueOf(i - 1)));
        }

        session().execute(String.format("CREATE TABLE mytable (a int PRIMARY KEY, b frozen<depth_0>, c frozen<depth_1>, d frozen<depth_2>, e frozen<depth_3>," +
                "f frozen<depth_%s>)", MAX_NESTING_DEPTH));

        // insert UDT data
        UserType depthZeroDef = cluster().getMetadata().getKeyspace("udtNestedTest").getUserType("depth_0");
        UDTValue depthZero = depthZeroDef.newValue().setInt("age", 42).setString("name", "Bob");

        UserType depthOneDef = cluster().getMetadata().getKeyspace("udtNestedTest").getUserType("depth_1");
        UDTValue depthOne = depthOneDef.newValue().setUDTValue("value", depthZero);

        UserType depthTwoDef = cluster().getMetadata().getKeyspace("udtNestedTest").getUserType("depth_2");
        UDTValue depthTwo = depthTwoDef.newValue().setUDTValue("value", depthOne);

        UserType depthThreeDef = cluster().getMetadata().getKeyspace("udtNestedTest").getUserType("depth_3");
        UDTValue depthThree = depthThreeDef.newValue().setUDTValue("value", depthTwo);

        UserType depthFourDef = cluster().getMetadata().getKeyspace("udtNestedTest").getUserType("depth_4");
        UDTValue depthFour = depthFourDef.newValue().setUDTValue("value", depthThree);

        PreparedStatement ins = session().prepare("INSERT INTO mytable (a, b, c, d, e, f) VALUES (?, ?, ?, ?, ?, ?)");
        session().execute(ins.bind(0, depthZero, depthOne, depthTwo, depthThree, depthFour));

        // retrieve and verify data
        ResultSet rs = session().execute("SELECT * FROM mytable");
        List<Row> rows = rs.all();
        assertEquals(1, rows.size());

        Row row = rows.get(0);

        assertEquals(row.getInt("a"), 0);
        assertEquals(row.getUDTValue("b"), depthZero);
        assertEquals(row.getUDTValue("c"), depthOne);
        assertEquals(row.getUDTValue("d"), depthTwo);
        assertEquals(row.getUDTValue("e"), depthThree);
        assertEquals(row.getUDTValue("f"), depthFour);
    }

    /**
     * Test for inserting null values into UDT's
     * Original code found in python-driver:integration.standard.test_udts.py:test_udts_with_nulls
     *
     * @throws Exception
     */
    @Test(groups = "short")
    public void testUdtsWithNulls() throws Exception {
        // create keyspace
        session().execute("CREATE KEYSPACE testUdtsWithNulls " +
                "WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor': '1'}");
        session().execute("USE testUdtsWithNulls");

        // create UDT
        session().execute("CREATE TYPE user (a text, b int, c uuid, d blob)");
        session().execute("CREATE TABLE mytable (a int PRIMARY KEY, b frozen<user>)");

        // insert UDT data
        UserType userTypeDef = cluster().getMetadata().getKeyspace("testUdtsWithNulls").getUserType("user");
        UDTValue userType = userTypeDef.newValue().setString("a", null).setInt("b", 0).setUUID("c", null).setBytes("d", null);

        PreparedStatement ins = session().prepare("INSERT INTO mytable (a, b) VALUES (?, ?)");
        session().execute(ins.bind(0, userType));

        // retrieve and verify data
        ResultSet rs = session().execute("SELECT * FROM mytable");
        List<Row> rows = rs.all();
        assertEquals(1, rows.size());

        Row row = rows.get(0);

        assertEquals(row.getInt("a"), 0);
        assertEquals(row.getUDTValue("b"), userType);

        // test empty strings
        userType = userTypeDef.newValue().setString("a", "").setInt("b", 0).setUUID("c", null).setBytes("d", ByteBuffer.allocate(0));
        session().execute(ins.bind(0, userType));

        // retrieve and verify data
        rs = session().execute("SELECT * FROM mytable");
        rows = rs.all();
        assertEquals(1, rows.size());

        row = rows.get(0);

        assertEquals(row.getInt("a"), 0);
        assertEquals(row.getUDTValue("b"), userType);
    }

    /**
     * Test for inserting null values into collections of UDT's
     *
     * @throws Exception
     */
    @Test(groups = "short")
    public void testUdtsWithCollectionNulls() throws Exception {
        // create keyspace
        session().execute("CREATE KEYSPACE testUdtsWithCollectionNulls " +
                "WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor': '1'}");
        session().execute("USE testUdtsWithCollectionNulls");

        // create UDT
        session().execute("CREATE TYPE user (a List<text>, b Set<text>, c Map<text, text>, d frozen<Tuple<text>>)");
        session().execute("CREATE TABLE mytable (a int PRIMARY KEY, b frozen<user>)");

        // insert null UDT data
        PreparedStatement ins = session().prepare("INSERT INTO mytable (a, b) " +
                "VALUES (0, { a: ?, b: ?, c: ?, d: ? })");
        session().execute(ins.bind().setList(0, null).setSet(1, null).setMap(2, null).setTupleValue(3, null));

        // retrieve and verify data
        ResultSet rs = session().execute("SELECT * FROM mytable");
        List<Row> rows = rs.all();
        assertEquals(1, rows.size());

        Row row = rows.get(0);
        assertEquals(row.getInt("a"), 0);

        UserType userTypeDef = cluster().getMetadata().getKeyspace("testUdtsWithCollectionNulls").getUserType("user");
        UDTValue userType = userTypeDef.newValue().setList("a", null).setSet("b", null).setMap("c", null).setTupleValue("d", null);
        assertEquals(row.getUDTValue("b"), userType);

        // test missing UDT args
        ins = session().prepare("INSERT INTO mytable (a, b) " +
                "VALUES (1, { a: ? })");
        session().execute(ins.bind().setList(0, new ArrayList<Object>()));

        // retrieve and verify data
        rs = session().execute("SELECT * FROM mytable");
        rows = rs.all();
        assertEquals(2, rows.size());

        row = rows.get(0);
        assertEquals(row.getInt("a"), 1);

        userType = userTypeDef.newValue().setList(0, new ArrayList<Object>());
        assertEquals(row.getUDTValue("b"), userType);
    }
}
