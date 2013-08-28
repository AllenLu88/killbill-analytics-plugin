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
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoicePayment;
import com.ning.billing.osgi.bundles.analytics.utils.CurrencyConverter;
import com.ning.billing.payment.api.Payment;
import com.ning.billing.payment.api.PaymentMethod;
import com.ning.billing.payment.api.Refund;
import com.ning.billing.util.audit.AuditLog;

public class BusinessInvoicePaymentRefundModelDao extends BusinessInvoicePaymentBaseModelDao {

    public BusinessInvoicePaymentRefundModelDao() { /* When reading from the database */ }

    public BusinessInvoicePaymentRefundModelDao(final Account account,
                                                final Long accountRecordId,
                                                final Invoice invoice,
                                                final InvoicePayment invoicePayment,
                                                final Long invoicePaymentRecordId,
                                                final Payment payment,
                                                final Refund refund,
                                                @Nullable final PaymentMethod paymentMethod,
                                                final CurrencyConverter currencyConverter,
                                                @Nullable final AuditLog creationAuditLog,
                                                final Long tenantRecordId,
                                                @Nullable final ReportGroup reportGroup) {
        super(account,
              accountRecordId,
              invoice,
              invoicePayment,
              invoicePaymentRecordId,
              payment,
              refund,
              paymentMethod,
              currencyConverter,
              creationAuditLog,
              tenantRecordId,
              reportGroup);
    }

    @Override
    public String getTableName() {
        return INVOICE_PAYMENT_REFUNDS_TABLE_NAME;
    }
}
