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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ning.billing.ObjectType;
import com.ning.billing.osgi.bundles.analytics.api.BusinessAccount;
import com.ning.billing.osgi.bundles.analytics.api.BusinessAccountTransition;
import com.ning.billing.osgi.bundles.analytics.api.BusinessField;
import com.ning.billing.osgi.bundles.analytics.api.BusinessInvoice;
import com.ning.billing.osgi.bundles.analytics.api.BusinessInvoicePayment;
import com.ning.billing.osgi.bundles.analytics.api.BusinessSubscriptionTransition;
import com.ning.billing.osgi.bundles.analytics.api.BusinessTag;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessAccountModelDao;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessAccountTransitionModelDao;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessFieldModelDao;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessInvoiceItemBaseModelDao;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessInvoiceModelDao;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessInvoicePaymentBaseModelDao;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessSubscriptionTransitionModelDao;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessTagModelDao;
import com.ning.billing.util.api.RecordIdApi;
import com.ning.billing.util.callcontext.TenantContext;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillAPI;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillDataSource;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillLogService;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class AnalyticsDao extends BusinessAnalyticsDaoBase {

    private final OSGIKillbillAPI osgiKillbillAPI;

    public AnalyticsDao(final OSGIKillbillLogService logService,
                        final OSGIKillbillAPI osgiKillbillAPI,
                        final OSGIKillbillDataSource osgiKillbillDataSource) {
        super(logService, osgiKillbillDataSource);
        this.osgiKillbillAPI = osgiKillbillAPI;
    }

    public BusinessAccount getAccountById(final UUID accountId, final TenantContext context) {
        final Long accountRecordId = getAccountRecordId(accountId, context);
        final Long tenantRecordId = getTenantRecordId(context);

        final BusinessAccountModelDao businessAccountModelDao = sqlDao.getAccountByAccountRecordId(accountRecordId, tenantRecordId, context);
        if (businessAccountModelDao == null) {
            return null;
        } else {
            return new BusinessAccount(businessAccountModelDao);
        }
    }

    public Collection<BusinessSubscriptionTransition> getSubscriptionTransitionsForAccount(final UUID accountId, final TenantContext context) {
        final Long accountRecordId = getAccountRecordId(accountId, context);
        final Long tenantRecordId = getTenantRecordId(context);

        final List<BusinessSubscriptionTransitionModelDao> businessSubscriptionTransitionModelDaos = sqlDao.getSubscriptionTransitionsByAccountRecordId(accountRecordId, tenantRecordId, context);
        return Lists.transform(businessSubscriptionTransitionModelDaos, new Function<BusinessSubscriptionTransitionModelDao, BusinessSubscriptionTransition>() {
            @Override
            public BusinessSubscriptionTransition apply(final BusinessSubscriptionTransitionModelDao input) {
                return new BusinessSubscriptionTransition(input);
            }
        });
    }

    public Collection<BusinessAccountTransition> getAccountTransitionsForAccount(final UUID accountId, final TenantContext context) {
        final Long accountRecordId = getAccountRecordId(accountId, context);
        final Long tenantRecordId = getTenantRecordId(context);

        final List<BusinessAccountTransitionModelDao> businessAccountTransitionModelDaos = sqlDao.getAccountTransitionsByAccountRecordId(accountRecordId, tenantRecordId, context);
        return Lists.transform(businessAccountTransitionModelDaos, new Function<BusinessAccountTransitionModelDao, BusinessAccountTransition>() {
            @Override
            public BusinessAccountTransition apply(final BusinessAccountTransitionModelDao input) {
                return new BusinessAccountTransition(input);
            }
        });
    }

    public Collection<BusinessInvoice> getInvoicesForAccount(final UUID accountId, final TenantContext context) {
        final Long accountRecordId = getAccountRecordId(accountId, context);
        final Long tenantRecordId = getTenantRecordId(context);

        final List<BusinessInvoiceItemBaseModelDao> businessInvoiceItemModelDaos = new ArrayList<BusinessInvoiceItemBaseModelDao>();
        businessInvoiceItemModelDaos.addAll(sqlDao.getInvoiceAdjustmentsByAccountRecordId(accountRecordId, tenantRecordId, context));
        businessInvoiceItemModelDaos.addAll(sqlDao.getInvoiceItemsByAccountRecordId(accountRecordId, tenantRecordId, context));
        businessInvoiceItemModelDaos.addAll(sqlDao.getInvoiceItemAdjustmentsByAccountRecordId(accountRecordId, tenantRecordId, context));
        businessInvoiceItemModelDaos.addAll(sqlDao.getInvoiceItemCreditsByAccountRecordId(accountRecordId, tenantRecordId, context));

        final Map<UUID, List<BusinessInvoiceItemBaseModelDao>> itemsPerInvoice = new LinkedHashMap<UUID, List<BusinessInvoiceItemBaseModelDao>>();
        for (final BusinessInvoiceItemBaseModelDao businessInvoiceModelDao : businessInvoiceItemModelDaos) {
            if (itemsPerInvoice.get(businessInvoiceModelDao.getInvoiceId()) == null) {
                itemsPerInvoice.put(businessInvoiceModelDao.getInvoiceId(), new LinkedList<BusinessInvoiceItemBaseModelDao>());
            }
            itemsPerInvoice.get(businessInvoiceModelDao.getInvoiceId()).add(businessInvoiceModelDao);
        }

        final List<BusinessInvoiceModelDao> businessInvoiceModelDaos = sqlDao.getInvoicesByAccountRecordId(accountRecordId, tenantRecordId, context);
        return Lists.transform(businessInvoiceModelDaos, new Function<BusinessInvoiceModelDao, BusinessInvoice>() {
            @Override
            public BusinessInvoice apply(final BusinessInvoiceModelDao input) {
                return new BusinessInvoice(input, Objects.firstNonNull(itemsPerInvoice.get(input.getInvoiceId()), ImmutableList.<BusinessInvoiceItemBaseModelDao>of()));
            }
        });
    }

    public Collection<BusinessInvoicePayment> getInvoicePaymentsForAccount(final UUID accountId, final TenantContext context) {
        final Long accountRecordId = getAccountRecordId(accountId, context);
        final Long tenantRecordId = getTenantRecordId(context);

        final List<BusinessInvoicePaymentBaseModelDao> businessInvoicePaymentModelDaos = new ArrayList<BusinessInvoicePaymentBaseModelDao>();
        businessInvoicePaymentModelDaos.addAll(sqlDao.getInvoicePaymentsByAccountRecordId(accountRecordId, tenantRecordId, context));
        businessInvoicePaymentModelDaos.addAll(sqlDao.getInvoicePaymentRefundsByAccountRecordId(accountRecordId, tenantRecordId, context));
        businessInvoicePaymentModelDaos.addAll(sqlDao.getInvoicePaymentChargebacksByAccountRecordId(accountRecordId, tenantRecordId, context));

        return Lists.transform(businessInvoicePaymentModelDaos, new Function<BusinessInvoicePaymentBaseModelDao, BusinessInvoicePayment>() {
            @Override
            public BusinessInvoicePayment apply(final BusinessInvoicePaymentBaseModelDao input) {
                return new BusinessInvoicePayment(input);
            }
        });
    }

    public Collection<BusinessField> getFieldsForAccount(final UUID accountId, final TenantContext context) {
        final Long accountRecordId = getAccountRecordId(accountId, context);
        final Long tenantRecordId = getTenantRecordId(context);

        final List<BusinessFieldModelDao> businessFieldModelDaos = new LinkedList<BusinessFieldModelDao>();
        businessFieldModelDaos.addAll(sqlDao.getAccountFieldsByAccountRecordId(accountRecordId, tenantRecordId, context));
        businessFieldModelDaos.addAll(sqlDao.getBundleFieldsByAccountRecordId(accountRecordId, tenantRecordId, context));
        businessFieldModelDaos.addAll(sqlDao.getInvoiceFieldsByAccountRecordId(accountRecordId, tenantRecordId, context));
        businessFieldModelDaos.addAll(sqlDao.getInvoicePaymentFieldsByAccountRecordId(accountRecordId, tenantRecordId, context));

        return Lists.transform(businessFieldModelDaos, new Function<BusinessFieldModelDao, BusinessField>() {
            @Override
            public BusinessField apply(final BusinessFieldModelDao input) {
                return BusinessField.create(input);
            }
        });
    }

    public Collection<BusinessTag> getTagsForAccount(final UUID accountId, final TenantContext context) {
        final Long accountRecordId = getAccountRecordId(accountId, context);
        final Long tenantRecordId = getTenantRecordId(context);

        final List<BusinessTagModelDao> businessTagModelDaos = new LinkedList<BusinessTagModelDao>();
        businessTagModelDaos.addAll(sqlDao.getAccountTagsByAccountRecordId(accountRecordId, tenantRecordId, context));
        businessTagModelDaos.addAll(sqlDao.getBundleTagsByAccountRecordId(accountRecordId, tenantRecordId, context));
        businessTagModelDaos.addAll(sqlDao.getInvoiceTagsByAccountRecordId(accountRecordId, tenantRecordId, context));
        businessTagModelDaos.addAll(sqlDao.getInvoicePaymentTagsByAccountRecordId(accountRecordId, tenantRecordId, context));

        return Lists.transform(businessTagModelDaos, new Function<BusinessTagModelDao, BusinessTag>() {
            @Override
            public BusinessTag apply(final BusinessTagModelDao input) {
                return BusinessTag.create(input);
            }
        });
    }

    private Long getAccountRecordId(final UUID accountId, final TenantContext context) {
        final RecordIdApi recordIdApi = osgiKillbillAPI.getRecordIdApi();
        return recordIdApi == null ? -1L : recordIdApi.getRecordId(accountId, ObjectType.ACCOUNT, context);
    }

    private Long getTenantRecordId(final TenantContext context) {
        final RecordIdApi recordIdApi = osgiKillbillAPI.getRecordIdApi();
        if (recordIdApi == null) {
            // Be safe
            return -1L;
        } else {
            return (context.getTenantId() == null) ? null : recordIdApi.getRecordId(context.getTenantId(), ObjectType.TENANT, context);
        }
    }
}
