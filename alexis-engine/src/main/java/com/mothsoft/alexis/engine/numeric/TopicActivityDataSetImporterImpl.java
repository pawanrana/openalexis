/*   Copyright 2012 Tim Garrett, Mothsoft LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.mothsoft.alexis.engine.numeric;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

import org.apache.log4j.Logger;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.mothsoft.alexis.dao.DataSetDao;
import com.mothsoft.alexis.dao.DataSetPointDao;
import com.mothsoft.alexis.dao.DataSetTypeDao;
import com.mothsoft.alexis.dao.TopicDao;
import com.mothsoft.alexis.domain.DataSet;
import com.mothsoft.alexis.domain.DataSetPoint;
import com.mothsoft.alexis.domain.DataSetType;
import com.mothsoft.alexis.domain.Topic;
import com.mothsoft.alexis.domain.TopicActivityDataSet;
import com.mothsoft.alexis.engine.util.TimeUtil;

public class TopicActivityDataSetImporterImpl implements TopicActivityDataSetImporter {

    private static final Logger logger = Logger.getLogger(TopicActivityDataSetImporterImpl.class);

    @PersistenceContext
    private EntityManager em;

    private PlatformTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    private DataSetDao dataSetDao;
    private DataSetPointDao dataSetPointDao;
    private DataSetTypeDao dataSetTypeDao;
    private TopicDao topicDao;

    public TopicActivityDataSetImporterImpl() {
        super();
    }

    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        this.transactionTemplate = new TransactionTemplate(this.transactionManager);
    }

    public void setDataSetDao(DataSetDao dataSetDao) {
        this.dataSetDao = dataSetDao;
    }

    public void setDataSetPointDao(DataSetPointDao dataSetPointDao) {
        this.dataSetPointDao = dataSetPointDao;
    }

    public void setDataSetTypeDao(DataSetTypeDao dataSetTypeDao) {
        this.dataSetTypeDao = dataSetTypeDao;
    }

    public void setTopicDao(TopicDao topicDao) {
        this.topicDao = topicDao;
    }

    @Override
    public void importData() {

        final List<Long> userIds = listUserIds();

        final Date now = new Date();
        final Date startDate = TimeUtil.floor(now);
        final Date endDate = TimeUtil.add(startDate, Calendar.MINUTE, 15);

        for (final Long userId : userIds) {
            importTopicDataForUser(userId, startDate, endDate);
        }
    }

    private List<Long> listUserIds() {
        final List<Long> userIds = this.transactionTemplate.execute(new TransactionCallback<List<Long>>() {

            @SuppressWarnings("unchecked")
            @Override
            public List<Long> doInTransaction(TransactionStatus txStatus) {
                final Query query = TopicActivityDataSetImporterImpl.this.em
                        .createQuery("SELECT id FROM User ORDER BY id ASC");
                return query.getResultList();
            }
        });
        return userIds;
    }

    private void importTopicDataForUser(final Long userId, final Date startDate, final Date endDate) {
        logger.debug(String.format("Importing topic activity for user: %d between %s and %s", userId,
                startDate.toString(), endDate.toString()));

        final List<Long> topicIds = this.transactionTemplate.execute(new TransactionCallback<List<Long>>() {
            @SuppressWarnings("unchecked")
            @Override
            public List<Long> doInTransaction(TransactionStatus txStatus) {
                final Query query = TopicActivityDataSetImporterImpl.this.em
                        .createQuery("SELECT id FROM Topic WHERE userId = :userId ORDER BY id ASC");
                query.setParameter("userId", userId);
                return query.getResultList();
            }
        });

        BigInteger total = BigInteger.ZERO;

        for (final Long topicId : topicIds) {
            BigInteger count = importTopicDataForTopic(userId, topicId, startDate, endDate, false);
            total = total.add(count);
        }

        recordAggregateTopicActivity(userId, startDate, total);

    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public BigInteger importTopicDataForTopic(final Long userId, final Long topicId, final Date startDate,
            final Date endDate) {
        return this.importTopicDataForTopic(userId, topicId, startDate, endDate, true);
    }

    private BigInteger importTopicDataForTopic(final Long userId, final Long topicId, final Date startDate,
            final Date endDate, final boolean incrementTotals) {
        logger.debug(String.format("Importing topic activity for topic: %d between %s and %s", topicId,
                startDate.toString(), endDate.toString()));

        final String queryString = "SELECT DATE_FORMAT(td.creation_date, '%Y-%m-%d %H:00:00') as the_hour, "
                + " COUNT(td.id) from topic_document td INNER JOIN topic on topic.id = td.topic_id "
                + " WHERE td.creation_date >= ? AND td.creation_date < ? AND td.topic_id = ? "
                + " GROUP BY the_hour ORDER BY td.creation_date";

        final BigInteger count = this.transactionTemplate.execute(new TransactionCallback<BigInteger>() {
            @Override
            public BigInteger doInTransaction(TransactionStatus txStatus) {
                final Query query = TopicActivityDataSetImporterImpl.this.em.createNativeQuery(queryString);
                query.setParameter(1, startDate);
                query.setParameter(2, endDate);
                query.setParameter(3, topicId);

                final List<?> results = query.getResultList();

                if (results == null || results.isEmpty()) {
                    return BigInteger.ZERO;
                } else {
                    final Object[] array = (Object[]) results.get(0);
                    return (BigInteger) array[1];
                }
            }
        });

        logger.debug("Data set point: (" + startDate + ", " + count + ")");

        this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {

            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                TopicActivityDataSet dataSet = TopicActivityDataSetImporterImpl.this.dataSetDao
                        .findTopicActivityDataSet(topicId);

                if (dataSet == null) {
                    final DataSetType type = TopicActivityDataSetImporterImpl.this.dataSetTypeDao
                            .findSystemDataSetType(DataSetType.TOPIC_ACTIVITY);
                    final Topic topic = TopicActivityDataSetImporterImpl.this.topicDao.get(topicId);
                    dataSet = new TopicActivityDataSet(topic, type);
                    TopicActivityDataSetImporterImpl.this.em.persist(dataSet);
                }

                DataSetPoint existing = TopicActivityDataSetImporterImpl.this.dataSetPointDao.findByTimestamp(dataSet,
                        new Timestamp(startDate.getTime()));

                if (existing != null) {
                    final double existingCount = existing.getY();
                    existing.setY(existingCount + count.doubleValue());
                    TopicActivityDataSetImporterImpl.this.dataSetPointDao.update(existing);

                    if (incrementTotals) {
                        // we assume that the aggregate already includes
                        // existing count and only increment by the difference
                        final BigInteger diffCount = BigInteger.valueOf(count.longValue() - (long) existingCount);
                        TopicActivityDataSetImporterImpl.this.recordAggregateTopicActivity(userId, existing.getX(),
                                diffCount);
                    }

                } else {
                    final DataSetPoint point = new DataSetPoint(dataSet, startDate, count.doubleValue());
                    TopicActivityDataSetImporterImpl.this.dataSetPointDao.add(point);

                    if (incrementTotals) {
                        TopicActivityDataSetImporterImpl.this.recordAggregateTopicActivity(userId, point.getX(), count);
                    }

                }

            }
        });

        return count;
    }

    public void recordAggregateTopicActivity(final Long userId, final Date startDate, final BigInteger total) {

        logger.debug("Recording aggregate topic activity for user: " + userId + "; (" + startDate.toString() + ", "
                + total + ")");

        this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus txStatus) {
                DataSet dataSet = TopicActivityDataSetImporterImpl.this.dataSetDao
                        .findAggregateTopicActivityDataSet(userId);

                if (dataSet == null) {
                    final DataSetType type = TopicActivityDataSetImporterImpl.this.dataSetTypeDao
                            .findSystemDataSetType(DataSetType.TOPIC_ACTIVITY);
                    dataSet = new DataSet(userId, "*All Topics*", type, true);
                    TopicActivityDataSetImporterImpl.this.dataSetDao.add(dataSet);
                }

                final DataSetPoint existing = TopicActivityDataSetImporterImpl.this.dataSetPointDao.findByTimestamp(
                        dataSet, new Timestamp(startDate.getTime()));

                if (existing != null) {
                    existing.setY(existing.getY() + total.doubleValue());
                    TopicActivityDataSetImporterImpl.this.dataSetPointDao.update(existing);
                } else {
                    final DataSetPoint totalPoint = new DataSetPoint(dataSet, startDate, total.doubleValue());
                    TopicActivityDataSetImporterImpl.this.dataSetPointDao.add(totalPoint);
                }

            }
        });
    }
}
