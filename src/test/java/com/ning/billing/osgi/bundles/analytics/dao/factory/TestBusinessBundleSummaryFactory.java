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

package com.ning.billing.osgi.bundles.analytics.dao.factory;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.ning.billing.catalog.api.Currency;
import com.ning.billing.catalog.api.Plan;
import com.ning.billing.catalog.api.Product;
import com.ning.billing.entitlement.api.SubscriptionBundle;
import com.ning.billing.osgi.bundles.analytics.AnalyticsTestSuiteNoDB;
import com.ning.billing.osgi.bundles.analytics.BusinessExecutor;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessSubscription;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessSubscriptionEvent;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessSubscriptionTransitionModelDao;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillDataSource;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillLogService;

import com.google.common.collect.ImmutableList;

public class TestBusinessBundleSummaryFactory extends AnalyticsTestSuiteNoDB {

    private BusinessBundleSummaryFactory bundleSummaryDao;

    @Override
    @BeforeMethod(groups = "fast")
    public void setUp() throws Exception {
        super.setUp();

        final OSGIKillbillDataSource osgiKillbillDataSource = Mockito.mock(OSGIKillbillDataSource.class);

        final DataSource dataSource = Mockito.mock(DataSource.class);
        Mockito.when(osgiKillbillDataSource.getDataSource()).thenReturn(dataSource);

        final OSGIKillbillLogService osgiKillbillLogService = Mockito.mock(OSGIKillbillLogService.class);
        Mockito.doAnswer(new Answer() {
            @Override
            public Object answer(final InvocationOnMock invocation) throws Throwable {
                logger.info(Arrays.toString(invocation.getArguments()));
                return null;
            }
        }).when(osgiKillbillLogService).log(Mockito.anyInt(), Mockito.anyString());

        bundleSummaryDao = new BusinessBundleSummaryFactory(osgiKillbillLogService, null, osgiKillbillDataSource, BusinessExecutor.newCachedThreadPool(), clock);
    }

    @Test(groups = "fast")
    public void testFilterBsts() throws Exception {
        final UUID bundleId1 = UUID.randomUUID();
        final LocalDate bundle1StartDate = new LocalDate(2012, 1, 1);
        final LocalDate bundle1PhaseDate = new LocalDate(2012, 2, 2);
        final UUID bundleId2 = UUID.randomUUID();
        final LocalDate bundle2StartDate = new LocalDate(2012, 2, 1);
        final LocalDate bundle2PhaseDate = new LocalDate(2012, 3, 2);
        final UUID bundleId3 = UUID.randomUUID();
        final LocalDate bundle3StartDate = new LocalDate(2012, 3, 1);

        // Real order is: bundleId1 START_ENTITLEMENT_BASE, bundleId2 START_ENTITLEMENT_BASE, bundleId1 SYSTEM_CHANGE_BASE, bundleId3 START_ENTITLEMENT_BASE bundleId2 SYSTEM_CHANGE_BASE
        final Collection<BusinessSubscriptionTransitionModelDao> bsts = ImmutableList.<BusinessSubscriptionTransitionModelDao>of(
                createBst(bundleId1, "START_ENTITLEMENT_BASE", bundle1StartDate),
                createBst(bundleId1, "SYSTEM_CHANGE_BASE", bundle1PhaseDate),
                createBst(bundleId2, "START_ENTITLEMENT_BASE", bundle2StartDate),
                createBst(bundleId2, "SYSTEM_CHANGE_BASE", bundle2PhaseDate),
                createBst(bundleId3, "START_ENTITLEMENT_BASE", bundle3StartDate),
                createBst(UUID.randomUUID(), "START_ENTITLEMENT_ADD_ON", new LocalDate(DateTimeZone.UTC))
                                                                                                                                );

        final Map<UUID, Integer> rankForBundle = new LinkedHashMap<UUID, Integer>();
        final Map<UUID, BusinessSubscriptionTransitionModelDao> bstForBundle = new LinkedHashMap<UUID, BusinessSubscriptionTransitionModelDao>();
        bundleSummaryDao.filterBstsForBasePlans(bsts, rankForBundle, bstForBundle);

        final List<BusinessSubscriptionTransitionModelDao> filteredBsts = ImmutableList.<BusinessSubscriptionTransitionModelDao>copyOf(bstForBundle.values());
        Assert.assertEquals(filteredBsts.size(), 3);

        Assert.assertEquals(filteredBsts.get(0).getBundleId(), bundleId1);
        Assert.assertEquals(filteredBsts.get(0).getNextStartDate(), bundle1PhaseDate);
        Assert.assertEquals(filteredBsts.get(1).getBundleId(), bundleId2);
        Assert.assertEquals(filteredBsts.get(1).getNextStartDate(), bundle2PhaseDate);
        Assert.assertEquals(filteredBsts.get(2).getBundleId(), bundleId3);
        Assert.assertEquals(filteredBsts.get(2).getNextStartDate(), bundle3StartDate);
    }

    private BusinessSubscriptionTransitionModelDao createBst(final UUID bundleId, final String eventString, final LocalDate startDate) {
        final SubscriptionBundle bundle = Mockito.mock(SubscriptionBundle.class);
        Mockito.when(bundle.getId()).thenReturn(bundleId);

        final BusinessSubscriptionEvent event = BusinessSubscriptionEvent.valueOf(eventString);
        final BusinessSubscription previousSubscription = null; // We don't look at it

        final Product product = Mockito.mock(Product.class);
        Mockito.when(product.getCategory()).thenReturn(event.getCategory());
        final Plan plan = Mockito.mock(Plan.class);
        Mockito.when(plan.getProduct()).thenReturn(product);
        final BusinessSubscription nextSubscription = new BusinessSubscription(plan,
                                                                               null,
                                                                               null,
                                                                               Currency.GBP,
                                                                               startDate,
                                                                               "ACTIVE",
                                                                               currencyConverter);

        return new BusinessSubscriptionTransitionModelDao(account,
                                                          accountRecordId,
                                                          bundle,
                                                          subscriptionTransition,
                                                          subscriptionEventRecordId,
                                                          event,
                                                          previousSubscription,
                                                          nextSubscription,
                                                          currencyConverter,
                                                          auditLog,
                                                          tenantRecordId,
                                                          reportGroup);
    }
}
