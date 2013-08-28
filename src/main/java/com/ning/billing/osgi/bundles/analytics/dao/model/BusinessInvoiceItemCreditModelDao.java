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

package com.ning.billing.osgi.bundles.analytics.dao.model;

import javax.annotation.Nullable;

import com.ning.billing.account.api.Account;
import com.ning.billing.catalog.api.Plan;
import com.ning.billing.catalog.api.PlanPhase;
import com.ning.billing.entitlement.api.SubscriptionBundle;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoiceItem;
import com.ning.billing.osgi.bundles.analytics.utils.CurrencyConverter;
import com.ning.billing.util.audit.AuditLog;

public class BusinessInvoiceItemCreditModelDao extends BusinessInvoiceItemBaseModelDao {

    public BusinessInvoiceItemCreditModelDao() { /* When reading from the database */ }

    public BusinessInvoiceItemCreditModelDao(final Account account,
                                             final Long accountRecordId,
                                             final Invoice invoice,
                                             final InvoiceItem invoiceItem,
                                             @Nullable final ItemSource itemSource,
                                             final Long invoiceItemRecordId,
                                             final Long secondInvoiceItemRecordId,
                                             @Nullable final SubscriptionBundle bundle,
                                             @Nullable final Plan plan,
                                             @Nullable final PlanPhase planPhase,
                                             final CurrencyConverter currencyConverter,
                                             @Nullable final AuditLog creationAuditLogs,
                                             final Long tenantRecordId,
                                             @Nullable final ReportGroup reportGroup) {
        super(account,
              accountRecordId,
              invoice,
              invoiceItem,
              itemSource,
              invoiceItemRecordId,
              secondInvoiceItemRecordId,
              bundle,
              plan,
              planPhase,
              currencyConverter,
              creationAuditLogs,
              tenantRecordId,
              reportGroup);
    }

    @Override
    public String getTableName() {
        return ACCOUNT_CREDITS_TABLE_NAME;
    }

    @Override
    public BusinessInvoiceItemType getBusinessInvoiceItemType() {
        return BusinessInvoiceItemType.ACCOUNT_CREDIT;
    }
}
