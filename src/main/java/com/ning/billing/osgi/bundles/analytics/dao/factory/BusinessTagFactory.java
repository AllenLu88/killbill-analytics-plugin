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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ning.billing.ObjectType;
import com.ning.billing.account.api.Account;
import com.ning.billing.clock.Clock;
import com.ning.billing.entitlement.api.SubscriptionBundle;
import com.ning.billing.osgi.bundles.analytics.AnalyticsRefreshException;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessModelDaoBase.ReportGroup;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessTagModelDao;
import com.ning.billing.util.audit.AuditLog;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.tag.Tag;
import com.ning.billing.util.tag.TagDefinition;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillAPI;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillDataSource;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillLogService;

public class BusinessTagFactory extends BusinessFactoryBase {

    public BusinessTagFactory(final OSGIKillbillLogService logService,
                              final OSGIKillbillAPI osgiKillbillAPI,
                              final OSGIKillbillDataSource osgiKillbillDataSource,
                              final Clock clock) {
        super(logService, osgiKillbillAPI, osgiKillbillDataSource, clock);
    }

    public Collection<BusinessTagModelDao> createBusinessTags(final UUID accountId,
                                                              final CallContext context) throws AnalyticsRefreshException {
        final Account account = getAccount(accountId, context);

        final Long accountRecordId = getAccountRecordId(account.getId(), context);
        final Long tenantRecordId = getTenantRecordId(context);
        final ReportGroup reportGroup = getReportGroup(account.getId(), context);

        final Collection<Tag> tags = getTagsForAccount(account.getId(), context);

        // Lookup once all SubscriptionBundle for that account (optimized call, should be faster in case an account has a lot
        // of tagged bundles)
        final List<SubscriptionBundle> bundlesForAccount = getSubscriptionBundlesForAccount(accountId, context);
        final Map<UUID, SubscriptionBundle> bundles = new LinkedHashMap<UUID, SubscriptionBundle>();
        for (final SubscriptionBundle bundle : bundlesForAccount) {
            bundles.put(bundle.getId(), bundle);
        }

        final Collection<BusinessTagModelDao> tagModelDaos = new LinkedList<BusinessTagModelDao>();
        // We process tags sequentially: in practice, an account will be associated with a dozen tags at most
        for (final Tag tag : tags) {
            final Long tagRecordId = getTagRecordId(tag.getId(), context);
            final TagDefinition tagDefinition = getTagDefinition(tag.getTagDefinitionId(), context);
            final AuditLog creationAuditLog = getTagCreationAuditLog(tag.getId(), context);

            SubscriptionBundle bundle = null;
            if (ObjectType.BUNDLE.equals(tag.getObjectType())) {
                bundle = bundles.get(tag.getObjectId());
            }
            final BusinessTagModelDao tagModelDao = BusinessTagModelDao.create(account,
                                                                               accountRecordId,
                                                                               bundle,
                                                                               tag,
                                                                               tagRecordId,
                                                                               tagDefinition,
                                                                               creationAuditLog,
                                                                               tenantRecordId,
                                                                               reportGroup);
            tagModelDaos.add(tagModelDao);
        }

        return tagModelDaos;
    }
}
