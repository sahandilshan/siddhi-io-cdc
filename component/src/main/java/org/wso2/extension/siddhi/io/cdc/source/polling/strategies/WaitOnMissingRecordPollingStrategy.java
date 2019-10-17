/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.extension.siddhi.io.cdc.source.polling.strategies;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.log4j.Logger;
import org.wso2.extension.siddhi.io.cdc.source.polling.CDCPollingModeException;
import org.wso2.extension.siddhi.io.cdc.util.CDCPollingUtil;
import org.wso2.extension.siddhi.io.cdc.util.CDCSourceConstants;
import org.wso2.siddhi.core.stream.input.source.SourceEventListener;
import org.wso2.siddhi.core.util.config.ConfigReader;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Polling strategy implementation to wait-on-missing records. If the polling strategy identifies a missed record in
 * the polled chunk, it holds the rest of the processing until the record comes in. This uses {@code pollingColumn},
 * {@code pollingInterval}, {@code missedRecordRetryIntervalMS} and {@code missedRecordWaitingTimeoutMS}.
 */
public class WaitOnMissingRecordPollingStrategy extends PollingStrategy {
    private static final Logger log = Logger.getLogger(WaitOnMissingRecordPollingStrategy.class);

    private String pollingColumn;
    private int pollingInterval;
    private int retryIntervalMS;
    private int waitingTimeoutMS;
    // The 'wait on missed records' events only work with numeric type. Hence assuming the polling.column is a number.
    private Integer lastReadPollingColumnValue;

    public WaitOnMissingRecordPollingStrategy(HikariDataSource dataSource, ConfigReader configReader,
                                              SourceEventListener sourceEventListener, String tableName,
                                              String pollingColumn, int pollingInterval,
                                              int retryIntervalMS, int waitingTimeoutMS) {
        super(dataSource, configReader, sourceEventListener, tableName);
        this.pollingColumn = pollingColumn;
        this.pollingInterval = pollingInterval;
        this.retryIntervalMS = retryIntervalMS;
        this.waitingTimeoutMS = waitingTimeoutMS;
    }

    @Override
    public void poll() {
        String selectQuery;
        ResultSetMetaData metadata;
        Map<String, Object> detailsMap;
        Connection connection = getConnection();
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        boolean breakOnMissingRecord = false;
        if (retryIntervalMS <= 0) {
            retryIntervalMS = pollingInterval * 1000;
            log.debug("Missed record retry interval is set to " + retryIntervalMS + "ms.");
        }
        try {
            // If lastReadPollingColumnValue is null, assign it with last record of the table.
            if (lastReadPollingColumnValue == null) {
                selectQuery = getSelectQuery("MAX(" + pollingColumn + ")", "").trim();
                statement = connection.prepareStatement(selectQuery);
                resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    lastReadPollingColumnValue = resultSet.getInt(1);
                }
                // If the table is empty, set last offset to a negative value.
                if (lastReadPollingColumnValue == null) {
                    lastReadPollingColumnValue = -1;
                }
            }

            selectQuery = getSelectQuery("*", "WHERE " + pollingColumn + " > ?");
            statement = connection.prepareStatement(selectQuery);

            int waitingFor = -1;
            long waitingFrom = -1;

            while (true) {
                if (paused) {
                    pauseLock.lock();
                    try {
                        while (paused) {
                            pauseLockCondition.await();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        pauseLock.unlock();
                    }
                }
                try {
                    statement.setInt(1, lastReadPollingColumnValue);
                    resultSet = statement.executeQuery();
                    metadata = resultSet.getMetaData();
                    while (resultSet.next()) {
                        int currentPollingColumnValue = resultSet.getInt(pollingColumn);
                        if (currentPollingColumnValue - lastReadPollingColumnValue > 1) {
                            if (waitingFor == -1) {
                                // This is the first time to wait for the current record. Hence set the expected record
                                // id and the starting timestamp.
                                waitingFor = lastReadPollingColumnValue + 1;
                                waitingFrom = System.currentTimeMillis();
                            }

                            if ((waitingTimeoutMS == -1) ||
                                    (waitingFrom + waitingTimeoutMS >= System.currentTimeMillis())) {
                                log.debug("Missing record found at " + waitingFor + ". Hence pausing the process and " +
                                        "retry in " + retryIntervalMS + "ms.");
                                breakOnMissingRecord = true;
                                break;
                            }
                        }
                        if (waitingFor > -1) {
                            log.debug("Missed record received or timed-out. Hence resuming the process.");
                            waitingFor = -1;
                            waitingFrom = -1;
                        }
                        detailsMap = new HashMap<>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            String key = metadata.getColumnName(i);
                            Object value = resultSet.getObject(key);
                            detailsMap.put(key.toLowerCase(Locale.ENGLISH), value);
                        }
                        lastReadPollingColumnValue = resultSet.getInt(pollingColumn);
                        handleEvent(detailsMap);
                    }
                } catch (SQLException ex) {
                    log.error(ex);
                } finally {
                    CDCPollingUtil.cleanupConnection(resultSet, null, null);
                }
                try {
                    if (breakOnMissingRecord) {
                        Thread.sleep(retryIntervalMS);
                        breakOnMissingRecord = false;
                    } else {
                        Thread.sleep((long) pollingInterval * 1000);
                    }
                } catch (InterruptedException e) {
                    log.error("Error while polling. Current mode: " + CDCSourceConstants.MODE_POLLING, e);
                }
            }
        } catch (SQLException ex) {
            throw new CDCPollingModeException("Error in polling for changes on " + tableName + ". Current mode: " +
                    CDCSourceConstants.MODE_POLLING, ex);
        } finally {
            CDCPollingUtil.cleanupConnection(resultSet, statement, connection);
        }
    }

    @Override
    public String getLastReadPollingColumnValue() {
        return String.valueOf(lastReadPollingColumnValue);
    }

    @Override
    public void setLastReadPollingColumnValue(String lastReadPollingColumnValue) {
        this.lastReadPollingColumnValue = Integer.parseInt(lastReadPollingColumnValue);
    }
}
