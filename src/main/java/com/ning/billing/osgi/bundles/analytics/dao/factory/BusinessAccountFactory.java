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

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.ning.billing.account.api.Account;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.clock.Clock;
import com.ning.billing.entitlement.api.Entitlement.EntitlementState;
import com.ning.billing.entitlement.api.Subscription;
import com.ning.billing.entitlement.api.SubscriptionBundle;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.osgi.bundles.analytics.AnalyticsRefreshException;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessAccountModelDao;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessModelDaoBase.ReportGroup;
import com.ning.billing.osgi.bundles.analytics.utils.CurrencyConverter;
import com.ning.billing.payment.api.Payment;
import com.ning.billing.util.audit.AuditLog;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillAPI;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillDataSource;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillLogService;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

public class BusinessAccountFactory extends BusinessFactoryBase {

    public BusinessAccountFactory(final OSGIKillbillLogService logService,
                                  final OSGIKillbillAPI osgiKillbillAPI,
                                  final OSGIKillbillDataSource osgiKillbillDataSource,
                                  final Clock clock) {
        super(logService, osgiKillbillAPI, osgiKillbillDataSource, clock);
    }

    public BusinessAccountModelDao createBusinessAccount(final UUID accountId,
                                                         final CallContext context) throws AnalyticsRefreshException {
        final Account account = getAccount(accountId, context);

        // Retrieve the account creation audit log
        final AuditLog creationAuditLog = getAccountCreationAuditLog(account.getId(), context);

        // Retrieve the account balance
        // Note: since we retrieve the invoices below, we could compute it ourselves and avoid fetching the invoices
        // twice, but that way the computation logic is owned by invoice
        final BigDecimal accountBalance = getAccountBalance(account.getId(), context);

        // Retrieve invoices information
        Invoice oldestUnpaidInvoice = null;
        Invoice lastInvoice = null;
        final Collection<Invoice> invoices = getInvoicesByAccountId(account.getId(), context);
        for (final Invoice invoice : invoices) {
            if (BigDecimal.ZERO.compareTo(invoice.getBalance()) < 0 &&
                (oldestUnpaidInvoice == null || invoice.getInvoiceDate().isBefore(oldestUnpaidInvoice.getInvoiceDate()))) {
                oldestUnpaidInvoice = invoice;
            }
            if (lastInvoice == null || invoice.getInvoiceDate().isAfter(lastInvoice.getInvoiceDate())) {
                lastInvoice = invoice;
            }
        }

        // Retrieve payments information
        Payment lastPayment = null;
        final Collection<Payment> payments = getPaymentsByAccountId(account.getId(), context);
        for (final Payment payment : payments) {
            if (lastPayment == null || payment.getEffectiveDate().isAfter(lastPayment.getEffectiveDate())) {
                lastPayment = payment;
            }
        }

        final List<SubscriptionBundle> bundles = getSubscriptionBundlesForAccount(account.getId(), context);
        final int nbActiveBundles = Iterables.<SubscriptionBundle>size(Iterables.<SubscriptionBundle>filter(bundles,
                                                                                                            new Predicate<SubscriptionBundle>() {
                                                                                                                @Override
                                                                                                                public boolean apply(final SubscriptionBundle bundle) {
                                                                                                                    return Iterables.<Subscription>size(Iterables.<Subscription>filter(bundle.getSubscriptions(),
                                                                                                                                                                                       new Predicate<Subscription>() {
                                                                                                                                                                                           @Override
                                                                                                                                                                                           public boolean apply(final Subscription subscription) {
                                                                                                                                                                                               // Bundle is active iff its base entitlement is not cancelled
                                                                                                                                                                                               return ProductCategory.BASE.equals(subscription.getLastActiveProductCategory()) &&
                                                                                                                                                                                                      !subscription.getState().equals(EntitlementState.CANCELLED);
                                                                                                                                                                                           }
                                                                                                                                                                                       })) > 0; /* 0 or 1 */
                                                                                                                }
                                                                                                            }));

        final Long accountRecordId = getAccountRecordId(account.getId(), context);
        final Long tenantRecordId = getTenantRecordId(context);
        final ReportGroup reportGroup = getReportGroup(account.getId(), context);
        final CurrencyConverter converter = getCurrencyConverter();

        return new BusinessAccountModelDao(account,
                                           accountRecordId,
                                           accountBalance,
                                           oldestUnpaidInvoice,
                                           lastInvoice,
                                           lastPayment,
                                           nbActiveBundles,
                                           converter,
                                           creationAuditLog,
                                           tenantRecordId,
                                           reportGroup);
    }
}
