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

package com.ning.billing.osgi.bundles.analytics.reports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.IDBI;

import com.ning.billing.osgi.bundles.analytics.BusinessExecutor;
import com.ning.billing.osgi.bundles.analytics.dao.BusinessDBIProvider;
import com.ning.billing.osgi.bundles.analytics.json.NamedXYTimeSeries;
import com.ning.billing.osgi.bundles.analytics.json.ReportConfigurationJson;
import com.ning.billing.osgi.bundles.analytics.json.XY;
import com.ning.billing.osgi.bundles.analytics.reports.analysis.Smoother;
import com.ning.billing.osgi.bundles.analytics.reports.analysis.Smoother.SmootherType;
import com.ning.billing.osgi.bundles.analytics.reports.configuration.ReportsConfigurationModelDao;
import com.ning.billing.osgi.bundles.analytics.reports.scheduler.JobsScheduler;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillDataSource;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;

public class ReportsUserApi {

    private static final Integer NB_THREADS = Integer.valueOf(System.getProperty("com.ning.billing.osgi.bundles.analytics.dashboard.nb_threads", "10"));
    private static final String NO_PIVOT = "____NO_PIVOT____";

    private final ExecutorService dbiThreadsExecutor = BusinessExecutor.newCachedThreadPool(NB_THREADS, "osgi-analytics-dashboard");

    private final IDBI dbi;
    private final ReportsConfiguration reportsConfiguration;
    private final JobsScheduler jobsScheduler;

    public ReportsUserApi(final OSGIKillbillDataSource osgiKillbillDataSource,
                          final ReportsConfiguration reportsConfiguration,
                          final JobsScheduler jobsScheduler) {
        this.reportsConfiguration = reportsConfiguration;
        this.jobsScheduler = jobsScheduler;
        dbi = BusinessDBIProvider.get(osgiKillbillDataSource.getDataSource());
    }

    public void shutdownNow() {
        dbiThreadsExecutor.shutdownNow();
    }

    public ReportConfigurationJson getReportConfiguration(final String reportName) {
        final ReportsConfigurationModelDao reportsConfigurationModelDao = reportsConfiguration.getReportConfigurationForReport(reportName);
        if (reportsConfigurationModelDao != null) {
            return new ReportConfigurationJson(reportsConfigurationModelDao);
        } else {
            return null;
        }
    }

    public void createReport(final ReportConfigurationJson reportConfigurationJson) {
        final ReportsConfigurationModelDao reportsConfigurationModelDao = new ReportsConfigurationModelDao(reportConfigurationJson);
        reportsConfiguration.createReportConfiguration(reportsConfigurationModelDao);
    }

    public void updateReport(final String reportName, final ReportConfigurationJson reportConfigurationJson) {
        final ReportsConfigurationModelDao currentReportsConfigurationModelDao = reportsConfiguration.getReportConfigurationForReport(reportName);
        final ReportsConfigurationModelDao reportsConfigurationModelDao = new ReportsConfigurationModelDao(reportConfigurationJson, currentReportsConfigurationModelDao);
        reportsConfiguration.updateReportConfiguration(reportsConfigurationModelDao);
    }

    public void deleteReport(final String reportName) {
        reportsConfiguration.deleteReportConfiguration(reportName);
    }

    public void refreshReport(final String reportName) {
        final ReportsConfigurationModelDao reportsConfigurationModelDao = reportsConfiguration.getReportConfigurationForReport(reportName);
        jobsScheduler.scheduleNow(reportsConfigurationModelDao);
    }

    public List<NamedXYTimeSeries> getTimeSeriesDataForReport(final String[] rawReportNames,
                                                              @Nullable final LocalDate startDate,
                                                              @Nullable final LocalDate endDate,
                                                              @Nullable final SmootherType smootherType) {
        // Mapping of report name -> pivots -> data
        final Map<String, Map<String, List<XY>>> dataForReports = new ConcurrentHashMap<String, Map<String, List<XY>>>();

        // Parse the reports
        final List<ReportSpecification> reportSpecifications = new ArrayList<ReportSpecification>();
        for (final String rawReportName : rawReportNames) {
            reportSpecifications.add(new ReportSpecification(rawReportName));
        }

        // Fetch the latest reports configurations
        final Map<String, ReportsConfigurationModelDao> reportsConfigurations = reportsConfiguration.getAllReportConfigurations();

        // Fetch the data
        fetchData(reportSpecifications, dataForReports, reportsConfigurations);

        // Filter the data first
        filterValues(reportSpecifications, dataForReports, startDate, endDate);

        // Normalize and sort the data
        normalizeAndSortXValues(dataForReports, startDate, endDate);

        // Smooth the data if needed and build the named timeseries
        if (smootherType != null) {
            final Smoother smoother = smootherType.createSmoother(dataForReports);
            smoother.smooth();
            return buildNamedXYTimeSeries(smoother.getDataForReports(), reportsConfigurations);
        } else {
            return buildNamedXYTimeSeries(dataForReports, reportsConfigurations);
        }
    }

    private List<NamedXYTimeSeries> buildNamedXYTimeSeries(final Map<String, Map<String, List<XY>>> dataForReports, final Map<String, ReportsConfigurationModelDao> reportsConfigurations) {
        final List<NamedXYTimeSeries> results = new LinkedList<NamedXYTimeSeries>();
        for (final String reportName : dataForReports.keySet()) {
            final ReportsConfigurationModelDao reportConfiguration = getReportConfiguration(reportName, reportsConfigurations);

            // Sort the pivots by name for a consistent display in the dashboard
            for (final String pivotName : Ordering.natural().sortedCopy(dataForReports.get(reportName).keySet())) {
                final String timeSeriesName;
                if (NO_PIVOT.equals(pivotName)) {
                    timeSeriesName = reportConfiguration.getReportPrettyName();
                } else {
                    timeSeriesName = String.format("%s (%s)", reportConfiguration.getReportPrettyName(), pivotName);
                }

                final List<XY> timeSeries = dataForReports.get(reportName).get(pivotName);
                results.add(new NamedXYTimeSeries(timeSeriesName, timeSeries));
            }
        }

        return results;
    }

    private void fetchData(final List<ReportSpecification> reportSpecifications, final Map<String, Map<String, List<XY>>> dataForReports, final Map<String, ReportsConfigurationModelDao> reportsConfigurations) {
        final List<Future> jobs = new LinkedList<Future>();
        for (final ReportSpecification reportSpecification : reportSpecifications) {
            final String reportName = reportSpecification.getReportName();
            final ReportsConfigurationModelDao reportConfiguration = getReportConfiguration(reportName, reportsConfigurations);
            final String tableName = reportConfiguration.getSourceTableName();
            jobs.add(dbiThreadsExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    final Map<String, List<XY>> data = getData(tableName);
                    dataForReports.put(reportName, data);
                }
            }));
        }

        for (final Future job : jobs) {
            try {
                job.get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void filterValues(final List<ReportSpecification> reportSpecifications,
                              final Map<String, Map<String, List<XY>>> dataForReports,
                              @Nullable final LocalDate startDate,
                              @Nullable final LocalDate endDate) {
        if (startDate == null && endDate == null) {
            return;
        }

        for (final ReportSpecification reportSpecification : reportSpecifications) {
            final String reportName = reportSpecification.getReportName();
            final Map<String, List<XY>> dataForReport = dataForReports.get(reportName);
            if (dataForReport == null) {
                throw new IllegalArgumentException();
            }

            // Handle the exclusion list
            Iterables.removeAll(dataForReport.keySet(), reportSpecification.getPivotNamesToExclude());

            // Handle the inclusion list
            if (reportSpecification.getPivotNamesToInclude().size() > 0) {
                Iterables.removeIf(dataForReport.keySet(),
                                   new Predicate<String>() {
                                       @Override
                                       public boolean apply(final String pivotName) {
                                           return !reportSpecification.getPivotNamesToInclude().contains(pivotName);
                                       }
                                   });
            }

            // Handle the dates filter
            for (final List<XY> dataForPivot : dataForReport.values()) {
                Iterables.removeIf(dataForPivot,
                                   new Predicate<XY>() {
                                       @Override
                                       public boolean apply(final XY xy) {
                                           return startDate != null && xy.getxDate().toLocalDate().isBefore(startDate) ||
                                                  endDate != null && xy.getxDate().toLocalDate().isAfter(endDate);
                                       }
                                   });
            }
        }
    }

    // TODO PIERRE Naive implementation
    private void normalizeAndSortXValues(final Map<String, Map<String, List<XY>>> dataForReports, @Nullable final LocalDate startDate, @Nullable final LocalDate endDate) {
        DateTime minDate = null;
        if (startDate != null) {
            minDate = startDate.toDateTimeAtStartOfDay(DateTimeZone.UTC);
        }

        DateTime maxDate = null;
        if (endDate != null) {
            maxDate = endDate.toDateTimeAtStartOfDay(DateTimeZone.UTC);
        }

        // If no min and/or max was specified, infer them from the data
        if (minDate == null || maxDate == null) {
            for (final Map<String, List<XY>> dataForReport : dataForReports.values()) {
                for (final List<XY> dataForPivot : dataForReport.values()) {
                    for (final XY xy : dataForPivot) {
                        if (minDate == null || xy.getxDate().isBefore(minDate)) {
                            minDate = xy.getxDate();
                        }
                        if (maxDate == null || xy.getxDate().isAfter(maxDate)) {
                            maxDate = xy.getxDate();
                        }
                    }
                }
            }
        }

        if (minDate == null || maxDate == null) {
            throw new IllegalStateException();
        }

        // Add 0 for missing days
        DateTime curDate = minDate;
        while (maxDate.isAfter(curDate)) {
            for (final Map<String, List<XY>> dataForReport : dataForReports.values()) {
                for (final List<XY> dataForPivot : dataForReport.values()) {
                    addMissingValueForDateIfNeeded(curDate, dataForPivot);
                }
            }
            curDate = curDate.plusDays(1);
        }

        // Sort the data for the dashboard
        for (final String reportName : dataForReports.keySet()) {
            for (final String pivotName : dataForReports.get(reportName).keySet()) {
                Collections.sort(dataForReports.get(reportName).get(pivotName),
                                 new Comparator<XY>() {
                                     @Override
                                     public int compare(final XY o1, final XY o2) {
                                         return o1.getxDate().compareTo(o2.getxDate());
                                     }
                                 });
            }
        }
    }

    private void addMissingValueForDateIfNeeded(final DateTime curDate, final List<XY> dataForPivot) {
        final XY valueForCurrentDate = Iterables.tryFind(dataForPivot, new Predicate<XY>() {
            @Override
            public boolean apply(final XY xy) {
                return xy.getxDate().compareTo(curDate) == 0;
            }
        }).orNull();

        if (valueForCurrentDate == null) {
            dataForPivot.add(new XY(curDate, (float) 0));
        }
    }

    private Map<String, List<XY>> getData(final String tableName) {
        final Map<String, List<XY>> timeSeries = new LinkedHashMap<String, List<XY>>();

        Handle handle = null;
        try {
            handle = dbi.open();
            final List<Map<String, Object>> results = handle.select("select * from " + tableName);
            for (final Map<String, Object> row : results) {
                if (row.get("day") == null || row.get("count") == null) {
                    continue;
                }

                final String date = row.get("day").toString();
                final Float value = Float.valueOf(row.get("count").toString());

                if (row.keySet().size() == 2) {
                    // No pivot
                    if (timeSeries.get(NO_PIVOT) == null) {
                        timeSeries.put(NO_PIVOT, new LinkedList<XY>());
                    }
                    timeSeries.get(NO_PIVOT).add(new XY(date, value));
                } else if (row.get("pivot") != null) {
                    final String pivot = row.get("pivot").toString();
                    if (timeSeries.get(pivot) == null) {
                        timeSeries.put(pivot, new LinkedList<XY>());
                    }
                    timeSeries.get(pivot).add(new XY(date, value));
                }
            }
        } finally {
            if (handle != null) {
                handle.close();
            }
        }

        return timeSeries;
    }

    private ReportsConfigurationModelDao getReportConfiguration(final String reportName, final Map<String, ReportsConfigurationModelDao> reportsConfigurations) {
        final ReportsConfigurationModelDao reportConfiguration = reportsConfigurations.get(reportName);
        if (reportConfiguration == null) {
            throw new IllegalArgumentException("Report " + reportName + " is not configured");
        }
        return reportConfiguration;
    }
}
