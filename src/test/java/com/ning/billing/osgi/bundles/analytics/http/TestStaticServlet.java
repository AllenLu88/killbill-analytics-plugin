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

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.billing.osgi.bundles.analytics.AnalyticsTestSuiteNoDB;

import com.google.common.io.Resources;

public class TestStaticServlet extends AnalyticsTestSuiteNoDB {

    @Test(groups = "fast")
    public void testHtmlAddocTemplate() throws IOException {
        final URL resourceUrl = Resources.getResource("static/dashboard.html");

        final String inputHtml = Resources.toString(resourceUrl, Charset.forName("UTF-8"));
        Assert.assertTrue(inputHtml.contains("$VAR_SERVER"));
        Assert.assertTrue(inputHtml.contains("$VAR_PORT"));

        final String rewrittenOut = StaticServlet.rewriteStaticResource(resourceUrl);
        Assert.assertFalse(rewrittenOut.contains("$VAR_SERVER"));
        Assert.assertFalse(rewrittenOut.contains("$VAR_PORT"));
    }
}
