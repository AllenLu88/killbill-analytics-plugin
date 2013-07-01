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

package com.ning.billing.osgi.bundles.analytics.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.osgi.service.log.LogService;

import com.ning.billing.osgi.bundles.analytics.AnalyticsRefreshException;
import com.ning.billing.osgi.bundles.analytics.api.BusinessSnapshot;
import com.ning.billing.osgi.bundles.analytics.api.user.AnalyticsUserApi;
import com.ning.billing.osgi.bundles.analytics.json.NamedXYTimeSeries;
import com.ning.billing.osgi.bundles.analytics.reports.ReportsUserApi;
import com.ning.billing.osgi.bundles.analytics.reports.analysis.Smoother;
import com.ning.billing.osgi.bundles.analytics.reports.analysis.Smoother.SmootherType;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.callcontext.CallOrigin;
import com.ning.billing.util.callcontext.UserType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.io.Resources;

public class AnalyticsServlet extends HttpServlet {

    public static final String SERVER_IP = System.getProperty("com.ning.core.server.ip", "127.0.0.1");
    public static final String SERVER_PORT = System.getProperty("com.ning.core.server.port", "8080");
    public static DateTimeFormatter DATE_FORMAT = DateTimeFormat.forPattern("yyyy-MM-dd");


    private static final String QUERY_TENANT_ID = "tenantId";
    private static final String HDR_CREATED_BY = "X-Killbill-CreatedBy";
    private static final String HDR_REASON = "X-Killbill-Reason";
    private static final String HDR_COMMENT = "X-Killbill-Comment";


    private final static String STATIC_RESOURCES = "static";

    private final static String PLAN_TRANSITIONS_OVER_TIME = "planTransitionsOverTime";
    private final static String RECURRING_REVENUE_OVER_TIME = "recurringRevenueOverTime";
    private final static String QUERY_START_DATE = "startDate";
    private final static String QUERY_END_DATE = "endDate";
    private final static String QUERY_PRODUCTS = "products";

    private static final String REPORTS = "reports";
    private static final String REPORTS_QUERY_NAME = "name";
    private static final String REPORTS_SMOOTHER_NAME = "smooth";

    private static final ObjectMapper mapper = ObjectMapperProvider.get();

    private final AnalyticsUserApi analyticsUserApi;
    private final ReportsUserApi reportsUserApi;
    private final LogService logService;

    public AnalyticsServlet(final AnalyticsUserApi analyticsUserApi, final ReportsUserApi reportsUserApi, final LogService logService) {
        this.analyticsUserApi = analyticsUserApi;
        this.reportsUserApi = reportsUserApi;
        this.logService = logService;
    }


    @Override
    protected void doOptions(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {

        final String uriOperationInfo = extractUriOperationInfo(req);
        logService.log(LogService.LOG_INFO, "doOptions " + uriOperationInfo);
        if (uriOperationInfo.equals(RECURRING_REVENUE_OVER_TIME) || uriOperationInfo.equals(PLAN_TRANSITIONS_OVER_TIME)) {

            logService.log(LogService.LOG_INFO, "doOptions *** " + uriOperationInfo);

            resp.setHeader("Access-Control-Allow-Origin", "http://0.0.0.0:8000");
            //resp.setHeader("Access-Control-Allow-Headers", "Authorization");
            resp.setHeader("Access-Control-Request-Method", "GET");
            resp.setHeader("Access-Control-Allow-Headers", "accept, origin, content-type");
            resp.setStatus(HttpServletResponse.SC_OK);
        } else {
            super.doOptions(req, resp);
        }
    }

    // Access-Control-Allow-Origin: *
    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {

        final String uriOperationInfo = extractUriOperationInfo(req);


        if (uriOperationInfo.equals(RECURRING_REVENUE_OVER_TIME)) {

            doHandleRecurringRevenueOverTime(req, resp);
        } else if (uriOperationInfo.equals(PLAN_TRANSITIONS_OVER_TIME)) {

            doHandlePlanTransitionsOverTime(req, resp);

        } else if (uriOperationInfo.startsWith(STATIC_RESOURCES)) {
            doHandleStaticResource(uriOperationInfo, resp);
        } else if (uriOperationInfo.startsWith(REPORTS)) {
            doHandleReports(req, resp);
        } else {
            final UUID kbAccountId = getKbAccountId(req, resp);
            final CallContext context = createCallContext(req, resp);

            final BusinessSnapshot businessSnapshot = analyticsUserApi.getBusinessSnapshot(kbAccountId, context);
            resp.getOutputStream().write(mapper.writeValueAsBytes(businessSnapshot));
            resp.setStatus(HttpServletResponse.SC_OK);
        }
    }

    @Override
    protected void doPut(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        final UUID kbAccountId = getKbAccountId(req, resp);
        final CallContext context = createCallContext(req, resp);

        try {
            analyticsUserApi.rebuildAnalyticsForAccount(kbAccountId, context);
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (AnalyticsRefreshException e) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private CallContext createCallContext(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String createdBy = Objects.firstNonNull(req.getHeader(HDR_CREATED_BY), req.getRemoteAddr());
        final String reason = req.getHeader(HDR_REASON);
        final String comment = Objects.firstNonNull(req.getHeader(HDR_COMMENT), req.getRequestURI());

        final String tenantIdString = req.getParameter(QUERY_TENANT_ID);

        UUID tenantId = null;
        if (tenantIdString != null) {
            try {
                tenantId = UUID.fromString(tenantIdString);
            } catch (final IllegalArgumentException e) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid UUID for tenant id: " + tenantIdString);
                return null;
            }
        }
        return new AnalyticsApiCallContext(createdBy, reason, comment, tenantId);
    }

    private String extractUriOperationInfo(final HttpServletRequest req) throws ServletException {

        logService.log(LogService.LOG_INFO, "extractUriOperationInfo :" + req.getPathInfo());
        final String res = req.getPathInfo().substring(1, req.getPathInfo().length());
        return res;
    }

    private void doHandleReports(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        final String[] rawReportNames = req.getParameterValues(REPORTS_QUERY_NAME);
        if (rawReportNames == null || rawReportNames.length == 0) {
            resp.sendError(404);
            return;
        }

        final LocalDate startDate = Strings.emptyToNull(req.getParameter(QUERY_START_DATE)) != null ? DATE_FORMAT.parseLocalDate(req.getParameter(QUERY_START_DATE)) : null;
        final LocalDate endDate = Strings.emptyToNull(req.getParameter(QUERY_END_DATE)) != null ? DATE_FORMAT.parseLocalDate(req.getParameter(QUERY_END_DATE)) : null;

        final SmootherType smootherType = Smoother.fromString(Strings.emptyToNull(req.getParameter(REPORTS_SMOOTHER_NAME)));

        // TODO PIERRE Switch to an equivalent of StreamingOutputStream?
        final List<NamedXYTimeSeries> result = reportsUserApi.getTimeSeriesDataForReport(rawReportNames, startDate, endDate, smootherType);

        resp.getOutputStream().write(mapper.writeValueAsBytes(result));
        resp.setContentType("application/json");
        setCrossSiteScriptingHeaders(resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    private void doHandlePlanTransitionsOverTime(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {

        final LocalDate startDate = req.getParameter(QUERY_START_DATE) != null ? DATE_FORMAT.parseLocalDate(req.getParameter(QUERY_START_DATE)) : null;
        final LocalDate endDate = req.getParameter(QUERY_END_DATE) != null ? DATE_FORMAT.parseLocalDate(req.getParameter(QUERY_END_DATE)) : null;
        final String products = req.getParameter(QUERY_PRODUCTS);

        logService.log(LogService.LOG_INFO, "PlanTransitionsOverTime " +
                                            "startDate = " + Objects.firstNonNull(startDate, "") +
                                            ", endDate = " + Objects.firstNonNull(endDate, "") +
                                            ", products = " + Objects.firstNonNull(products, ""));

        // TODO STEPH until we plug the DAO to get real data, fake it...
        final String linesDataJson = "[" +
                                     "{\"name\":\"new\"," +
                                     "\"values\":[{\"x\":\"2013-01-01\", \"y\":6}, {\"x\":\"2013-01-02\", \"y\":5}, {\"x\":\"2013-01-03\", \"y\":10}, {\"x\":\"2013-01-04\", \"y\":5}, {\"x\":\"2013-01-05\", \"y\":7}, {\"x\":\"2013-01-06\", \"y\":11}, {\"x\":\"2013-01-07\", \"y\":9}, {\"x\":\"2013-01-08\", \"y\":5}, {\"x\":\"2013-01-09\", \"y\":5}, {\"x\":\"2013-01-10\", \"y\":6}, {\"x\":\"2013-01-11\", \"y\":8}, {\"x\":\"2013-01-12\", \"y\":11}, {\"x\":\"2013-01-13\", \"y\":8}, {\"x\":\"2013-01-14\", \"y\":10}, {\"x\":\"2013-01-15\", \"y\":10}, {\"x\":\"2013-01-16\", \"y\":9}, {\"x\":\"2013-01-17\", \"y\":9}, {\"x\":\"2013-01-18\", \"y\":7}, {\"x\":\"2013-01-19\", \"y\":8}, {\"x\":\"2013-01-20\", \"y\":5}, {\"x\":\"2013-01-21\", \"y\":5}, {\"x\":\"2013-01-22\", \"y\":7}, {\"x\":\"2013-01-23\", \"y\":6}, {\"x\":\"2013-01-24\", \"y\":10}, {\"x\":\"2013-01-25\", \"y\":7}, {\"x\":\"2013-01-26\", \"y\":9}, {\"x\":\"2013-01-27\", \"y\":10}, {\"x\":\"2013-01-28\", \"y\":6}, {\"x\":\"2013-01-29\", \"y\":5}, {\"x\":\"2013-01-30\", \"y\":9}, {\"x\":\"2013-01-31\", \"y\":6}, {\"x\":\"2013-02-01\", \"y\":9}, {\"x\":\"2013-02-02\", \"y\":7}, {\"x\":\"2013-02-03\", \"y\":11}, {\"x\":\"2013-02-04\", \"y\":10}, {\"x\":\"2013-02-05\", \"y\":11}, {\"x\":\"2013-02-06\", \"y\":9}, {\"x\":\"2013-02-07\", \"y\":10}, {\"x\":\"2013-02-08\", \"y\":8}, {\"x\":\"2013-02-09\", \"y\":10}, {\"x\":\"2013-02-10\", \"y\":8}, {\"x\":\"2013-02-11\", \"y\":7}, {\"x\":\"2013-02-12\", \"y\":9}, {\"x\":\"2013-02-13\", \"y\":9}, {\"x\":\"2013-02-14\", \"y\":7}, {\"x\":\"2013-02-15\", \"y\":5}, {\"x\":\"2013-02-16\", \"y\":8}, {\"x\":\"2013-02-17\", \"y\":7}, {\"x\":\"2013-02-18\", \"y\":7}, {\"x\":\"2013-02-19\", \"y\":6}, {\"x\":\"2013-02-20\", \"y\":5}, {\"x\":\"2013-02-21\", \"y\":6}, {\"x\":\"2013-02-22\", \"y\":6}, {\"x\":\"2013-02-23\", \"y\":11}, {\"x\":\"2013-02-24\", \"y\":10}, {\"x\":\"2013-02-25\", \"y\":11}, {\"x\":\"2013-02-26\", \"y\":10}, {\"x\":\"2013-02-27\", \"y\":6}, {\"x\":\"2013-02-28\", \"y\":10}, {\"x\":\"2013-03-01\", \"y\":9}, {\"x\":\"2013-03-02\", \"y\":10}, {\"x\":\"2013-03-03\", \"y\":9}, {\"x\":\"2013-03-04\", \"y\":7}, {\"x\":\"2013-03-05\", \"y\":8}, {\"x\":\"2013-03-06\", \"y\":8}, {\"x\":\"2013-03-07\", \"y\":6}, {\"x\":\"2013-03-08\", \"y\":5}, {\"x\":\"2013-03-09\", \"y\":6}, {\"x\":\"2013-03-10\", \"y\":6}, {\"x\":\"2013-03-11\", \"y\":7}]}," +
                                     "{\"name\":\"changed\"," +
                                     "\"values\":[{\"x\":\"2013-01-01\", \"y\":5}, {\"x\":\"2013-01-02\", \"y\":6}, {\"x\":\"2013-01-03\", \"y\":7}, {\"x\":\"2013-01-04\", \"y\":6}, {\"x\":\"2013-01-05\", \"y\":6}, {\"x\":\"2013-01-06\", \"y\":6}, {\"x\":\"2013-01-07\", \"y\":7}, {\"x\":\"2013-01-08\", \"y\":6}, {\"x\":\"2013-01-09\", \"y\":6}, {\"x\":\"2013-01-10\", \"y\":5}, {\"x\":\"2013-01-11\", \"y\":6}, {\"x\":\"2013-01-12\", \"y\":5}, {\"x\":\"2013-01-13\", \"y\":5}, {\"x\":\"2013-01-14\", \"y\":5}, {\"x\":\"2013-01-15\", \"y\":5}, {\"x\":\"2013-01-16\", \"y\":6}, {\"x\":\"2013-01-17\", \"y\":5}, {\"x\":\"2013-01-18\", \"y\":5}, {\"x\":\"2013-01-19\", \"y\":5}, {\"x\":\"2013-01-20\", \"y\":5}, {\"x\":\"2013-01-21\", \"y\":5}, {\"x\":\"2013-01-22\", \"y\":5}, {\"x\":\"2013-01-23\", \"y\":7}, {\"x\":\"2013-01-24\", \"y\":7}, {\"x\":\"2013-01-25\", \"y\":6}, {\"x\":\"2013-01-26\", \"y\":6}, {\"x\":\"2013-01-27\", \"y\":7}, {\"x\":\"2013-01-28\", \"y\":7}, {\"x\":\"2013-01-29\", \"y\":7}, {\"x\":\"2013-01-30\", \"y\":7}, {\"x\":\"2013-01-31\", \"y\":5}, {\"x\":\"2013-02-01\", \"y\":5}, {\"x\":\"2013-02-02\", \"y\":7}, {\"x\":\"2013-02-03\", \"y\":6}, {\"x\":\"2013-02-04\", \"y\":6}, {\"x\":\"2013-02-05\", \"y\":6}, {\"x\":\"2013-02-06\", \"y\":6}, {\"x\":\"2013-02-07\", \"y\":6}, {\"x\":\"2013-02-08\", \"y\":5}, {\"x\":\"2013-02-09\", \"y\":5}, {\"x\":\"2013-02-10\", \"y\":6}, {\"x\":\"2013-02-11\", \"y\":6}, {\"x\":\"2013-02-12\", \"y\":5}, {\"x\":\"2013-02-13\", \"y\":7}, {\"x\":\"2013-02-14\", \"y\":7}, {\"x\":\"2013-02-15\", \"y\":7}, {\"x\":\"2013-02-16\", \"y\":7}, {\"x\":\"2013-02-17\", \"y\":7}, {\"x\":\"2013-02-18\", \"y\":7}, {\"x\":\"2013-02-19\", \"y\":6}, {\"x\":\"2013-02-20\", \"y\":7}, {\"x\":\"2013-02-21\", \"y\":5}, {\"x\":\"2013-02-22\", \"y\":7}, {\"x\":\"2013-02-23\", \"y\":5}, {\"x\":\"2013-02-24\", \"y\":7}, {\"x\":\"2013-02-25\", \"y\":5}, {\"x\":\"2013-02-26\", \"y\":5}, {\"x\":\"2013-02-27\", \"y\":7}, {\"x\":\"2013-02-28\", \"y\":7}, {\"x\":\"2013-03-01\", \"y\":5}, {\"x\":\"2013-03-02\", \"y\":5}, {\"x\":\"2013-03-03\", \"y\":6}, {\"x\":\"2013-03-04\", \"y\":6}, {\"x\":\"2013-03-05\", \"y\":5}, {\"x\":\"2013-03-06\", \"y\":5}, {\"x\":\"2013-03-07\", \"y\":6}, {\"x\":\"2013-03-08\", \"y\":7}, {\"x\":\"2013-03-09\", \"y\":5}, {\"x\":\"2013-03-10\", \"y\":6}, {\"x\":\"2013-03-11\", \"y\":7}]}," +
                                     "{\"name\":\"cancelled\"," +
                                     "\"values\":[{\"x\":\"2013-01-01\", \"y\":5}, {\"x\":\"2013-01-02\", \"y\":4}, {\"x\":\"2013-01-03\", \"y\":5}, {\"x\":\"2013-01-04\", \"y\":5}, {\"x\":\"2013-01-05\", \"y\":4}, {\"x\":\"2013-01-06\", \"y\":6}, {\"x\":\"2013-01-07\", \"y\":6}, {\"x\":\"2013-01-08\", \"y\":7}, {\"x\":\"2013-01-09\", \"y\":8}, {\"x\":\"2013-01-10\", \"y\":5}, {\"x\":\"2013-01-11\", \"y\":6}, {\"x\":\"2013-01-12\", \"y\":6}, {\"x\":\"2013-01-13\", \"y\":7}, {\"x\":\"2013-01-14\", \"y\":8}, {\"x\":\"2013-01-15\", \"y\":6}, {\"x\":\"2013-01-16\", \"y\":7}, {\"x\":\"2013-01-17\", \"y\":4}, {\"x\":\"2013-01-18\", \"y\":7}, {\"x\":\"2013-01-19\", \"y\":4}, {\"x\":\"2013-01-20\", \"y\":8}, {\"x\":\"2013-01-21\", \"y\":4}, {\"x\":\"2013-01-22\", \"y\":6}, {\"x\":\"2013-01-23\", \"y\":6}, {\"x\":\"2013-01-24\", \"y\":7}, {\"x\":\"2013-01-25\", \"y\":6}, {\"x\":\"2013-01-26\", \"y\":8}, {\"x\":\"2013-01-27\", \"y\":7}, {\"x\":\"2013-01-28\", \"y\":5}, {\"x\":\"2013-01-29\", \"y\":8}, {\"x\":\"2013-01-30\", \"y\":3}, {\"x\":\"2013-01-31\", \"y\":3}, {\"x\":\"2013-02-01\", \"y\":5}, {\"x\":\"2013-02-02\", \"y\":6}, {\"x\":\"2013-02-03\", \"y\":7}, {\"x\":\"2013-02-04\", \"y\":8}, {\"x\":\"2013-02-05\", \"y\":4}, {\"x\":\"2013-02-06\", \"y\":8}, {\"x\":\"2013-02-07\", \"y\":8}, {\"x\":\"2013-02-08\", \"y\":8}, {\"x\":\"2013-02-09\", \"y\":6}, {\"x\":\"2013-02-10\", \"y\":8}, {\"x\":\"2013-02-11\", \"y\":7}, {\"x\":\"2013-02-12\", \"y\":8}, {\"x\":\"2013-02-13\", \"y\":8}, {\"x\":\"2013-02-14\", \"y\":8}, {\"x\":\"2013-02-15\", \"y\":5}, {\"x\":\"2013-02-16\", \"y\":7}, {\"x\":\"2013-02-17\", \"y\":6}, {\"x\":\"2013-02-18\", \"y\":8}, {\"x\":\"2013-02-19\", \"y\":5}, {\"x\":\"2013-02-20\", \"y\":4}, {\"x\":\"2013-02-21\", \"y\":8}, {\"x\":\"2013-02-22\", \"y\":8}, {\"x\":\"2013-02-23\", \"y\":8}, {\"x\":\"2013-02-24\", \"y\":5}, {\"x\":\"2013-02-25\", \"y\":5}, {\"x\":\"2013-02-26\", \"y\":6}, {\"x\":\"2013-02-27\", \"y\":7}, {\"x\":\"2013-02-28\", \"y\":3}, {\"x\":\"2013-03-01\", \"y\":7}, {\"x\":\"2013-03-02\", \"y\":4}, {\"x\":\"2013-03-03\", \"y\":5}, {\"x\":\"2013-03-04\", \"y\":8}, {\"x\":\"2013-03-05\", \"y\":7}, {\"x\":\"2013-03-06\", \"y\":6}, {\"x\":\"2013-03-07\", \"y\":6}, {\"x\":\"2013-03-08\", \"y\":7}, {\"x\":\"2013-03-09\", \"y\":7}, {\"x\":\"2013-03-10\", \"y\":5}, {\"x\":\"2013-03-11\", \"y\":6}]}," +
                                     "{\"name\":\"next phase\"," +
                                     "\"values\":[{\"x\":\"2013-01-01\", \"y\":-1}, {\"x\":\"2013-01-02\", \"y\":6}, {\"x\":\"2013-01-03\", \"y\":6}, {\"x\":\"2013-01-04\", \"y\":1}, {\"x\":\"2013-01-05\", \"y\":13}, {\"x\":\"2013-01-06\", \"y\":2}, {\"x\":\"2013-01-07\", \"y\":14}, {\"x\":\"2013-01-08\", \"y\":12}, {\"x\":\"2013-01-09\", \"y\":7}, {\"x\":\"2013-01-10\", \"y\":2}, {\"x\":\"2013-01-11\", \"y\":-1}, {\"x\":\"2013-01-12\", \"y\":8}, {\"x\":\"2013-01-13\", \"y\":6}, {\"x\":\"2013-01-14\", \"y\":14}, {\"x\":\"2013-01-15\", \"y\":1}, {\"x\":\"2013-01-16\", \"y\":9}, {\"x\":\"2013-01-17\", \"y\":6}, {\"x\":\"2013-01-18\", \"y\":5}, {\"x\":\"2013-01-19\", \"y\":11}, {\"x\":\"2013-01-20\", \"y\":11}, {\"x\":\"2013-01-21\", \"y\":4}, {\"x\":\"2013-01-22\", \"y\":8}, {\"x\":\"2013-01-23\", \"y\":3}, {\"x\":\"2013-01-24\", \"y\":12}, {\"x\":\"2013-01-25\", \"y\":0}, {\"x\":\"2013-01-26\", \"y\":11}, {\"x\":\"2013-01-27\", \"y\":12}, {\"x\":\"2013-01-28\", \"y\":3}, {\"x\":\"2013-01-29\", \"y\":5}, {\"x\":\"2013-01-30\", \"y\":12}, {\"x\":\"2013-01-31\", \"y\":10}, {\"x\":\"2013-02-01\", \"y\":-2}, {\"x\":\"2013-02-02\", \"y\":4}, {\"x\":\"2013-02-03\", \"y\":12}, {\"x\":\"2013-02-04\", \"y\":7}, {\"x\":\"2013-02-05\", \"y\":4}, {\"x\":\"2013-02-06\", \"y\":10}, {\"x\":\"2013-02-07\", \"y\":14}, {\"x\":\"2013-02-08\", \"y\":4}, {\"x\":\"2013-02-09\", \"y\":6}, {\"x\":\"2013-02-10\", \"y\":9}, {\"x\":\"2013-02-11\", \"y\":14}, {\"x\":\"2013-02-12\", \"y\":9}, {\"x\":\"2013-02-13\", \"y\":14}, {\"x\":\"2013-02-14\", \"y\":8}, {\"x\":\"2013-02-15\", \"y\":5}, {\"x\":\"2013-02-16\", \"y\":13}, {\"x\":\"2013-02-17\", \"y\":6}, {\"x\":\"2013-02-18\", \"y\":0}, {\"x\":\"2013-02-19\", \"y\":1}, {\"x\":\"2013-02-20\", \"y\":11}, {\"x\":\"2013-02-21\", \"y\":11}, {\"x\":\"2013-02-22\", \"y\":9}, {\"x\":\"2013-02-23\", \"y\":9}, {\"x\":\"2013-02-24\", \"y\":5}, {\"x\":\"2013-02-25\", \"y\":2}, {\"x\":\"2013-02-26\", \"y\":-1}, {\"x\":\"2013-02-27\", \"y\":-2}, {\"x\":\"2013-02-28\", \"y\":1}, {\"x\":\"2013-03-01\", \"y\":2}, {\"x\":\"2013-03-02\", \"y\":7}, {\"x\":\"2013-03-03\", \"y\":4}, {\"x\":\"2013-03-04\", \"y\":1}, {\"x\":\"2013-03-05\", \"y\":6}, {\"x\":\"2013-03-06\", \"y\":10}, {\"x\":\"2013-03-07\", \"y\":5}, {\"x\":\"2013-03-08\", \"y\":1}, {\"x\":\"2013-03-09\", \"y\":4}, {\"x\":\"2013-03-10\", \"y\":11}, {\"x\":\"2013-03-11\", \"y\":10}]}" +
                                     "];";

        List<NamedXYTimeSeries> result = mapper.readValue(linesDataJson, List.class);
        resp.getOutputStream().write(mapper.writeValueAsBytes(result));
        resp.setContentType("application/json");
        setCrossSiteScriptingHeaders(resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    private void doHandleRecurringRevenueOverTime(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {

        final LocalDate startDate = req.getParameter(QUERY_START_DATE) != null ? DATE_FORMAT.parseLocalDate(req.getParameter(QUERY_START_DATE)) : null;
        final LocalDate endDate = req.getParameter(QUERY_END_DATE) != null ? DATE_FORMAT.parseLocalDate(req.getParameter(QUERY_END_DATE)) : null;
        final String products = req.getParameter(QUERY_PRODUCTS);

        logService.log(LogService.LOG_INFO, "RecurringRevenueOverTime " +
                                            "startDate = " + Objects.firstNonNull(startDate, "") +
                                            ", endDate = " + Objects.firstNonNull(endDate, "") +
                                            ", products = " + Objects.firstNonNull(products, ""));

        // TODO STEPH until we plug the DAO to get real data, fake it...
        final String layersDataDJson = "[" +
                                       "{\"name\":\"ultimate\"," + "\"values\":[{\"x\":\"2013-01-01\",\"y\":11},{\"x\":\"2013-01-02\",\"y\":37},{\"x\":\"2013-01-03\",\"y\":16},{\"x\":\"2013-01-04\",\"y\":29},{\"x\":\"2013-01-05\",\"y\":40},{\"x\":\"2013-01-06\",\"y\":3},{\"x\":\"2013-01-07\",\"y\":4},{\"x\":\"2013-01-08\",\"y\":39},{\"x\":\"2013-01-09\",\"y\":34},{\"x\":\"2013-01-10\",\"y\":31},{\"x\":\"2013-01-11\",\"y\":20},{\"x\":\"2013-01-12\",\"y\":28},{\"x\":\"2013-01-13\",\"y\":19},{\"x\":\"2013-01-14\",\"y\":15},{\"x\":\"2013-01-15\",\"y\":31},{\"x\":\"2013-01-16\",\"y\":16},{\"x\":\"2013-01-17\",\"y\":40},{\"x\":\"2013-01-18\",\"y\":29},{\"x\":\"2013-01-19\",\"y\":31},{\"x\":\"2013-01-20\",\"y\":11},{\"x\":\"2013-01-21\",\"y\":36},{\"x\":\"2013-01-22\",\"y\":18},{\"x\":\"2013-01-23\",\"y\":12},{\"x\":\"2013-01-24\",\"y\":23},{\"x\":\"2013-01-25\",\"y\":32},{\"x\":\"2013-01-26\",\"y\":27},{\"x\":\"2013-01-27\",\"y\":33},{\"x\":\"2013-01-28\",\"y\":34},{\"x\":\"2013-01-29\",\"y\":5},{\"x\":\"2013-01-30\",\"y\":7},{\"x\":\"2013-01-31\",\"y\":13},{\"x\":\"2013-02-01\",\"y\":10},{\"x\":\"2013-02-02\",\"y\":43},{\"x\":\"2013-02-03\",\"y\":15},{\"x\":\"2013-02-04\",\"y\":38},{\"x\":\"2013-02-05\",\"y\":34},{\"x\":\"2013-02-06\",\"y\":38},{\"x\":\"2013-02-07\",\"y\":26},{\"x\":\"2013-02-08\",\"y\":27},{\"x\":\"2013-02-09\",\"y\":1},{\"x\":\"2013-02-10\",\"y\":12},{\"x\":\"2013-02-11\",\"y\":28},{\"x\":\"2013-02-12\",\"y\":10},{\"x\":\"2013-02-13\",\"y\":27},{\"x\":\"2013-02-14\",\"y\":34},{\"x\":\"2013-02-15\",\"y\":25},{\"x\":\"2013-02-16\",\"y\":39},{\"x\":\"2013-02-17\",\"y\":39},{\"x\":\"2013-02-18\",\"y\":25},{\"x\":\"2013-02-19\",\"y\":38},{\"x\":\"2013-02-20\",\"y\":1},{\"x\":\"2013-02-21\",\"y\":8},{\"x\":\"2013-02-22\",\"y\":31},{\"x\":\"2013-02-23\",\"y\":38},{\"x\":\"2013-02-24\",\"y\":43},{\"x\":\"2013-02-25\",\"y\":16},{\"x\":\"2013-02-26\",\"y\":41},{\"x\":\"2013-02-27\",\"y\":44},{\"x\":\"2013-02-28\",\"y\":20},{\"x\":\"2013-03-01\",\"y\":44},{\"x\":\"2013-03-02\",\"y\":25},{\"x\":\"2013-03-03\",\"y\":41},{\"x\":\"2013-03-04\",\"y\":34},{\"x\":\"2013-03-05\",\"y\":4},{\"x\":\"2013-03-06\",\"y\":28},{\"x\":\"2013-03-07\",\"y\":34},{\"x\":\"2013-03-08\",\"y\":25},{\"x\":\"2013-03-09\",\"y\":9},{\"x\":\"2013-03-10\",\"y\":33},{\"x\":\"2013-03-11\",\"y\":40}]}," +
                                       "{\"name\":\"scale\",\"values\":[{\"x\":\"2013-01-01\",\"y\":22},{\"x\":\"2013-01-02\",\"y\":5},{\"x\":\"2013-01-03\",\"y\":20},{\"x\":\"2013-01-04\",\"y\":3},{\"x\":\"2013-01-05\",\"y\":7},{\"x\":\"2013-01-06\",\"y\":22},{\"x\":\"2013-01-07\",\"y\":3},{\"x\":\"2013-01-08\",\"y\":5},{\"x\":\"2013-01-09\",\"y\":5},{\"x\":\"2013-01-10\",\"y\":23},{\"x\":\"2013-01-11\",\"y\":6},{\"x\":\"2013-01-12\",\"y\":24},{\"x\":\"2013-01-13\",\"y\":7},{\"x\":\"2013-01-14\",\"y\":17},{\"x\":\"2013-01-15\",\"y\":5},{\"x\":\"2013-01-16\",\"y\":8},{\"x\":\"2013-01-17\",\"y\":3},{\"x\":\"2013-01-18\",\"y\":4},{\"x\":\"2013-01-19\",\"y\":15},{\"x\":\"2013-01-20\",\"y\":15},{\"x\":\"2013-01-21\",\"y\":2},{\"x\":\"2013-01-22\",\"y\":16},{\"x\":\"2013-01-23\",\"y\":5},{\"x\":\"2013-01-24\",\"y\":8},{\"x\":\"2013-01-25\",\"y\":10},{\"x\":\"2013-01-26\",\"y\":26},{\"x\":\"2013-01-27\",\"y\":15},{\"x\":\"2013-01-28\",\"y\":27},{\"x\":\"2013-01-29\",\"y\":24},{\"x\":\"2013-01-30\",\"y\":21},{\"x\":\"2013-01-31\",\"y\":18},{\"x\":\"2013-02-01\",\"y\":15},{\"x\":\"2013-02-02\",\"y\":23},{\"x\":\"2013-02-03\",\"y\":2},{\"x\":\"2013-02-04\",\"y\":16},{\"x\":\"2013-02-05\",\"y\":17},{\"x\":\"2013-02-06\",\"y\":15},{\"x\":\"2013-02-07\",\"y\":8},{\"x\":\"2013-02-08\",\"y\":23},{\"x\":\"2013-02-09\",\"y\":19},{\"x\":\"2013-02-10\",\"y\":26},{\"x\":\"2013-02-11\",\"y\":24},{\"x\":\"2013-02-12\",\"y\":22},{\"x\":\"2013-02-13\",\"y\":3},{\"x\":\"2013-02-14\",\"y\":0},{\"x\":\"2013-02-15\",\"y\":10},{\"x\":\"2013-02-16\",\"y\":14},{\"x\":\"2013-02-17\",\"y\":6},{\"x\":\"2013-02-18\",\"y\":5},{\"x\":\"2013-02-19\",\"y\":9},{\"x\":\"2013-02-20\",\"y\":29},{\"x\":\"2013-02-21\",\"y\":14},{\"x\":\"2013-02-22\",\"y\":16},{\"x\":\"2013-02-23\",\"y\":15},{\"x\":\"2013-02-24\",\"y\":29},{\"x\":\"2013-02-25\",\"y\":7},{\"x\":\"2013-02-26\",\"y\":4},{\"x\":\"2013-02-27\",\"y\":28},{\"x\":\"2013-02-28\",\"y\":29},{\"x\":\"2013-03-01\",\"y\":1},{\"x\":\"2013-03-02\",\"y\":23},{\"x\":\"2013-03-03\",\"y\":10},{\"x\":\"2013-03-04\",\"y\":26},{\"x\":\"2013-03-05\",\"y\":18},{\"x\":\"2013-03-06\",\"y\":25},{\"x\":\"2013-03-07\",\"y\":21},{\"x\":\"2013-03-08\",\"y\":5},{\"x\":\"2013-03-09\",\"y\":29},{\"x\":\"2013-03-10\",\"y\":13},{\"x\":\"2013-03-11\",\"y\":12}]}," +
                                       "{\"name\":\"basic\",\"values\":[{\"x\":\"2013-01-01\",\"y\":14},{\"x\":\"2013-01-02\",\"y\":55},{\"x\":\"2013-01-03\",\"y\":14},{\"x\":\"2013-01-04\",\"y\":55},{\"x\":\"2013-01-05\",\"y\":7},{\"x\":\"2013-01-06\",\"y\":15},{\"x\":\"2013-01-07\",\"y\":15},{\"x\":\"2013-01-08\",\"y\":39},{\"x\":\"2013-01-09\",\"y\":28},{\"x\":\"2013-01-10\",\"y\":30},{\"x\":\"2013-01-11\",\"y\":32},{\"x\":\"2013-01-12\",\"y\":49},{\"x\":\"2013-01-13\",\"y\":67},{\"x\":\"2013-01-14\",\"y\":69},{\"x\":\"2013-01-15\",\"y\":29},{\"x\":\"2013-01-16\",\"y\":39},{\"x\":\"2013-01-17\",\"y\":54},{\"x\":\"2013-01-18\",\"y\":56},{\"x\":\"2013-01-19\",\"y\":52},{\"x\":\"2013-01-20\",\"y\":60},{\"x\":\"2013-01-21\",\"y\":4},{\"x\":\"2013-01-22\",\"y\":37},{\"x\":\"2013-01-23\",\"y\":67},{\"x\":\"2013-01-24\",\"y\":72},{\"x\":\"2013-01-25\",\"y\":45},{\"x\":\"2013-01-26\",\"y\":2},{\"x\":\"2013-01-27\",\"y\":70},{\"x\":\"2013-01-28\",\"y\":26},{\"x\":\"2013-01-29\",\"y\":19},{\"x\":\"2013-01-30\",\"y\":36},{\"x\":\"2013-01-31\",\"y\":73},{\"x\":\"2013-02-01\",\"y\":63},{\"x\":\"2013-02-02\",\"y\":67},{\"x\":\"2013-02-03\",\"y\":11},{\"x\":\"2013-02-04\",\"y\":38},{\"x\":\"2013-02-05\",\"y\":7},{\"x\":\"2013-02-06\",\"y\":53},{\"x\":\"2013-02-07\",\"y\":52},{\"x\":\"2013-02-08\",\"y\":31},{\"x\":\"2013-02-09\",\"y\":18},{\"x\":\"2013-02-10\",\"y\":66},{\"x\":\"2013-02-11\",\"y\":32},{\"x\":\"2013-02-12\",\"y\":49},{\"x\":\"2013-02-13\",\"y\":38},{\"x\":\"2013-02-14\",\"y\":1},{\"x\":\"2013-02-15\",\"y\":61},{\"x\":\"2013-02-16\",\"y\":54},{\"x\":\"2013-02-17\",\"y\":71},{\"x\":\"2013-02-18\",\"y\":69},{\"x\":\"2013-02-19\",\"y\":59},{\"x\":\"2013-02-20\",\"y\":58},{\"x\":\"2013-02-21\",\"y\":28},{\"x\":\"2013-02-22\",\"y\":66},{\"x\":\"2013-02-23\",\"y\":59},{\"x\":\"2013-02-24\",\"y\":62},{\"x\":\"2013-02-25\",\"y\":65},{\"x\":\"2013-02-26\",\"y\":18},{\"x\":\"2013-02-27\",\"y\":57},{\"x\":\"2013-02-28\",\"y\":0},{\"x\":\"2013-03-01\",\"y\":24},{\"x\":\"2013-03-02\",\"y\":48},{\"x\":\"2013-03-03\",\"y\":2},{\"x\":\"2013-03-04\",\"y\":28},{\"x\":\"2013-03-05\",\"y\":58},{\"x\":\"2013-03-06\",\"y\":9},{\"x\":\"2013-03-07\",\"y\":59},{\"x\":\"2013-03-08\",\"y\":30},{\"x\":\"2013-03-09\",\"y\":30},{\"x\":\"2013-03-10\",\"y\":66},{\"x\":\"2013-03-11\",\"y\":48}]}" +
                                       "]";

        List<NamedXYTimeSeries> result = mapper.readValue(layersDataDJson.getBytes(), List.class);
        resp.getOutputStream().write(mapper.writeValueAsBytes(result));
        resp.setContentType("application/json");
        setCrossSiteScriptingHeaders(resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    private void setCrossSiteScriptingHeaders(final HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "http://0.0.0.0:8000");
        resp.setHeader("Access-Control-Request-Method", "GET");
        resp.setHeader("Access-Control-Allow-Headers", "accept, origin, content-type");
    }

    private void doHandleStaticResource(final String resourceName, final HttpServletResponse resp) throws IOException {

        logService.log(LogService.LOG_INFO, "doHandleStaticResource " + resourceName);

        final URL resourceUrl = Resources.getResource(resourceName);


        final String[] parts = resourceName.split("/");
        if (parts.length > 2) {
            if (parts[1].equals("javascript")) {
                resp.setContentType("application/javascript");
            } else if (parts[1].equals("styles")) {
                resp.setContentType("text/css");
            }
            Resources.copy(resourceUrl, resp.getOutputStream());
        } else {

            logService.log(LogService.LOG_INFO, "doHandleStaticResource rewritting html  " + resourceName);

            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            Resources.copy(resourceUrl, out);
            String inputHtml = new String(out.toByteArray());

            String tmp1 = inputHtml.replace("$VAR_SERVER", "\"" + SERVER_IP + "\"");
            String tmp2 = tmp1.replace("$VAR_PORT", "\"" + SERVER_PORT + "\"");
            resp.getOutputStream().write(tmp2.getBytes());
            resp.setContentType("text/html");
        }
        resp.setStatus(HttpServletResponse.SC_OK);
    }


    private UUID getKbAccountId(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String kbAccountIdString;
        try {
            kbAccountIdString = req.getPathInfo().substring(1, req.getPathInfo().length());
        } catch (final StringIndexOutOfBoundsException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Badly formed kb account id in request: " + req.getPathInfo());
            return null;
        }

        final UUID kbAccountId;
        try {
            kbAccountId = UUID.fromString(kbAccountIdString);
        } catch (final IllegalArgumentException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid UUID for kb account id: " + kbAccountIdString);
            return null;
        }

        return kbAccountId;
    }

    private static final class AnalyticsApiCallContext implements CallContext {

        private final String createdBy;
        private final String reason;
        private final String comment;
        private final UUID tenantId;
        private final DateTime now;

        private AnalyticsApiCallContext(final String createdBy,
                                        final String reason,
                                        final String comment,
                                        final UUID tenantId) {
            this.createdBy = createdBy;
            this.reason = reason;
            this.comment = comment;
            this.tenantId = tenantId;

            this.now = new DateTime(DateTimeZone.UTC);
        }

        @Override
        public UUID getUserToken() {
            return UUID.randomUUID();
        }

        @Override
        public String getUserName() {
            return createdBy;
        }

        @Override
        public CallOrigin getCallOrigin() {
            return CallOrigin.EXTERNAL;
        }

        @Override
        public UserType getUserType() {
            return UserType.ADMIN;
        }

        @Override
        public String getReasonCode() {
            return reason;
        }

        @Override
        public String getComments() {
            return comment;
        }

        @Override
        public DateTime getCreatedDate() {
            return now;
        }

        @Override
        public DateTime getUpdatedDate() {
            return now;
        }

        @Override
        public UUID getTenantId() {
            return tenantId;
        }
    }
}
