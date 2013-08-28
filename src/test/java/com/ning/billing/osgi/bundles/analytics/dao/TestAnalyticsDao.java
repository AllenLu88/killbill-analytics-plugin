/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.osgi.bundles.analytics.dao;

import java.math.BigDecimal;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.billing.osgi.bundles.analytics.AnalyticsTestSuiteWithEmbeddedDB;
import com.ning.billing.osgi.bundles.analytics.api.BusinessAccount;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessAccountModelDao;

public class TestAnalyticsDao extends AnalyticsTestSuiteWithEmbeddedDB {

    @Test(groups = "slow")
    public void testDao() throws Exception {
        final AnalyticsDao analyticsDao = new AnalyticsDao(logService, killbillAPI, killbillDataSource);
        Assert.assertNull(analyticsDao.getAccountById(account.getId(), callContext));

        final BusinessAccountModelDao accountModelDao = new BusinessAccountModelDao(account,
                                                                                    accountRecordId,
                                                                                    BigDecimal.ONE,
                                                                                    invoice,
                                                                                    invoice,
                                                                                    payment,
                                                                                    3,
                                                                                    currencyConverter,
                                                                                    auditLog,
                                                                                    tenantRecordId,
                                                                                    reportGroup);

        analyticsSqlDao.create(accountModelDao.getTableName(), accountModelDao, callContext);
        Assert.assertEquals(analyticsDao.getAccountById(account.getId(), callContext), new BusinessAccount(accountModelDao));

        analyticsSqlDao.deleteByAccountRecordId(accountModelDao.getTableName(),
                                                accountModelDao.getAccountRecordId(),
                                                accountModelDao.getTenantRecordId(),
                                                callContext);
        Assert.assertNull(analyticsDao.getAccountById(account.getId(), callContext));
    }
}
