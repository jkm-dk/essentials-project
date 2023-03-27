/*
 * Copyright 2021-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.cloudcreate.essentials.components.foundation.postgresql;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dk.cloudcreate.essentials.reactive.LocalEventBus;
import org.assertj.core.api.Fail;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MultiTableChangeListenerIT {
    private static final Logger log     = LoggerFactory.getLogger(MultiTableChangeListenerIT.class);
    public static final  String TABLE_1 = "table1";
    public static final  String TABLE_2 = "table2";
    public static final  String TABLE_3 = "table3";

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("listen-notify-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    protected Jdbi                                            jdbi;
    private ObjectMapper                                    objectMapper;
    private MultiTableChangeListener<TestTableNotification> listener;
    private LocalEventBus                                   localEventBus;

    @BeforeEach
    void setup() {
        objectMapper = createObjectMapper();
        jdbi = Jdbi.create(getPostgreSQLContainer().getJdbcUrl(), getPostgreSQLContainer().getUsername(), getPostgreSQLContainer().getPassword());

        jdbi.useTransaction(handle -> handle.execute("CREATE TABLE table1 (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, column1 TEXT, column2 TEXT)"));
        jdbi.useTransaction(handle -> handle.execute("CREATE TABLE table2 (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, column3 TEXT, column4 TEXT)"));
        jdbi.useTransaction(handle -> handle.execute("CREATE TABLE table3 (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, column5 TEXT, column6 TEXT)"));

        localEventBus = new LocalEventBus("Notification", 3, (consumer, tableChangeNotification, e) -> {
            throw new RuntimeException(e);
        });
    }

    protected PostgreSQLContainer<?> getPostgreSQLContainer() {
        return postgreSQLContainer;
    }

    @AfterEach
    void cleanup() {
        if (listener != null) {
            listener.close();
        }
    }


    @Test
    void test() throws JsonProcessingException {
        jdbi.useTransaction(handle -> {
            ListenNotify.addChangeNotificationTriggerToTable(handle, TABLE_1, List.of(ListenNotify.SqlOperation.INSERT), "id", "column1", "column2");
            ListenNotify.addChangeNotificationTriggerToTable(handle, TABLE_2, List.of(ListenNotify.SqlOperation.INSERT), "id", "column3", "column4");
            ListenNotify.addChangeNotificationTriggerToTable(handle, TABLE_3, List.of(ListenNotify.SqlOperation.INSERT), "id", "column5", "column6");
        });

        listener = new MultiTableChangeListener<>(jdbi, Duration.ofMillis(50), objectMapper, localEventBus);
        listener.listenToNotificationsFor(TABLE_1, Table1Notification.class);
        listener.listenToNotificationsFor(TABLE_2, Table2Notification.class);
        listener.listenToNotificationsFor(TABLE_3, Table3Notification.class);

        var notifications = new CopyOnWriteArrayList<TestTableNotification>();
        localEventBus.addAsyncSubscriber(e -> notifications.add((TestTableNotification) e));

        var numberOfInserts                     = 100;
        var expectedNumberOfTable1Notifications = 0;
        var expectedNumberOfTable2Notifications = 0;
        var expectedNumberOfTable3Notifications = 0;
        for (var insertIndex = 0; insertIndex < numberOfInserts; insertIndex++) {
            var tableName = "";
            var values    = "";
            if (insertIndex % 2 == 0) {
                expectedNumberOfTable2Notifications++;
                tableName = TABLE_2;
                values = " (column3, column4) VALUES ('Column3Value-" + expectedNumberOfTable2Notifications + "', 'Column4Value-" + expectedNumberOfTable2Notifications + "')";
            } else if (insertIndex % 3 == 0) {
                expectedNumberOfTable3Notifications++;
                tableName = TABLE_3;
                values = " (column5, column6) VALUES ('Column5Value-" + expectedNumberOfTable3Notifications + "', 'Column6Value-" + expectedNumberOfTable3Notifications + "')";
            } else {
                expectedNumberOfTable1Notifications++;
                tableName = TABLE_1;
                values = " (column1, column2) VALUES ('Column1Value-" + expectedNumberOfTable1Notifications + "', 'Column2Value-" + expectedNumberOfTable1Notifications + "')";
            }
            var sql = "INSERT INTO " + tableName + values;
            jdbi.useTransaction(handle -> {
                log.info("Performing: {}", sql);
                handle.execute(sql);
            });
        }

        Awaitility.waitAtMost(Duration.ofMillis(2000))
                  .untilAsserted(() -> assertThat(notifications.size()).isEqualTo(numberOfInserts));

        var notificationsOrdered = notifications.stream().sorted((o1, o2) -> Long.compare(o1.getId(), o2.getId())).collect(Collectors.toList());

        var actualNumberOfTable1Notifications = 0;
        var actualNumberOfTable2Notifications = 0;
        var actualNumberOfTable3Notifications = 0;
        for (var notification : notificationsOrdered) {
            log.info(objectMapper.writeValueAsString(notification));
            switch (notification.getTableName()) {
                case TABLE_1:
                    actualNumberOfTable1Notifications++;
                    assertThat(notification)
                            .describedAs("Table1 - 1-based index: " + actualNumberOfTable1Notifications)
                            .isInstanceOf(Table1Notification.class);
                    assertThat(notification.getId())
                            .describedAs("Table1 - 1-based index: " + actualNumberOfTable1Notifications)
                            .isEqualTo(actualNumberOfTable1Notifications);
                    assertThat(notification.getOperation())
                            .describedAs("Table1 - 1-based index: " + actualNumberOfTable1Notifications)
                            .isEqualTo(ListenNotify.SqlOperation.INSERT);
                    assertThat(((Table1Notification) notification).column1)
                            .describedAs("Table1 - 1-based index: " + actualNumberOfTable1Notifications)
                            .isEqualTo("Column1Value-" + actualNumberOfTable1Notifications);
                    assertThat(((Table1Notification) notification).column2)
                            .describedAs("Table1 - 1-based index: " + actualNumberOfTable1Notifications)
                            .isEqualTo("Column2Value-" + actualNumberOfTable1Notifications);
                    break;
                case TABLE_2:
                    actualNumberOfTable2Notifications++;
                    assertThat(notification)
                            .describedAs("Table2 - 1-based index: " + actualNumberOfTable2Notifications)
                            .isInstanceOf(Table2Notification.class);
                    assertThat(notification.getId())
                            .describedAs("Table2 - 1-based index: " + actualNumberOfTable2Notifications)
                            .isEqualTo(actualNumberOfTable2Notifications);
                    assertThat(notification.getOperation())
                            .describedAs("Table2 - 1-based index: " + actualNumberOfTable2Notifications)
                            .isEqualTo(ListenNotify.SqlOperation.INSERT);
                    assertThat(((Table2Notification) notification).column3)
                            .describedAs("Table2 - 1-based index: " + actualNumberOfTable2Notifications)
                            .isEqualTo("Column3Value-" + actualNumberOfTable2Notifications);
                    assertThat(((Table2Notification) notification).column4)
                            .describedAs("Table2 - 1-based index: " + actualNumberOfTable2Notifications)
                            .isEqualTo("Column4Value-" + actualNumberOfTable2Notifications);
                    break;
                case TABLE_3:
                    actualNumberOfTable3Notifications++;
                    assertThat(notification)
                            .describedAs("Table3 - 1-based index: " + actualNumberOfTable3Notifications)
                            .isInstanceOf(Table3Notification.class);
                    assertThat(notification.getId())
                            .describedAs("Table3 - 1-based index: " + actualNumberOfTable3Notifications)
                            .isEqualTo(actualNumberOfTable3Notifications);
                    assertThat(notification.getOperation())
                            .describedAs("Table3 - 1-based index: " + actualNumberOfTable3Notifications)
                            .isEqualTo(ListenNotify.SqlOperation.INSERT);
                    assertThat(((Table3Notification) notification).column5)
                            .describedAs("Table3 - 1-based index: " + actualNumberOfTable3Notifications)
                            .isEqualTo("Column5Value-" + actualNumberOfTable3Notifications);
                    assertThat(((Table3Notification) notification).column6)
                            .describedAs("Table3 - 1-based index: " + actualNumberOfTable3Notifications)
                            .isEqualTo("Column6Value-" + actualNumberOfTable3Notifications);
                    break;
                default:
                    Fail.fail("Unexpected table " + notification.getTableName());
            }
        }

        assertThat(actualNumberOfTable1Notifications)
                .isEqualTo(expectedNumberOfTable1Notifications);
        assertThat(actualNumberOfTable2Notifications)
                .isEqualTo(expectedNumberOfTable2Notifications);
        assertThat(actualNumberOfTable3Notifications)
                .isEqualTo(expectedNumberOfTable3Notifications);
    }

    private ObjectMapper createObjectMapper() {
        var objectMapper = JsonMapper.builder()
                                     .disable(MapperFeature.AUTO_DETECT_GETTERS)
                                     .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
                                     .disable(MapperFeature.AUTO_DETECT_SETTERS)
                                     .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                                     .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                     .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                     .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                                     .enable(MapperFeature.AUTO_DETECT_CREATORS)
                                     .enable(MapperFeature.AUTO_DETECT_FIELDS)
                                     .enable(MapperFeature.PROPAGATE_TRANSIENT_MARKER)
                                     .build();

        objectMapper.setVisibility(objectMapper.getSerializationConfig().getDefaultVisibilityChecker()
                                               .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                                               .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                                               .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                                               .withCreatorVisibility(JsonAutoDetect.Visibility.ANY));
        return objectMapper;
    }

    private static abstract class TestTableNotification extends TableChangeNotification {
        @JsonProperty("id")
        private long id;

        protected TestTableNotification() {
        }

        protected TestTableNotification(String tableName, ListenNotify.SqlOperation operation, long id) {
            super(tableName, operation);
            this.id = id;
        }

        public long getId() {
            return id;
        }
    }

    private static class Table1Notification extends TestTableNotification {
        @JsonProperty("column1")
        private String column1;
        @JsonProperty("column2")
        private String column2;
    }

    private static class Table2Notification extends TestTableNotification {
        @JsonProperty("column3")
        private String column3;
        @JsonProperty("column4")
        private String column4;
    }

    private static class Table3Notification extends TestTableNotification {
        @JsonProperty("column5")
        private String column5;
        @JsonProperty("column6")
        private String column6;
    }
}