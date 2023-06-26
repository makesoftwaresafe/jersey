/*
 * Copyright (c) 2022 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.tests.performance.benchmark.headers;

import org.glassfish.jersey.CommonProperties;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;


public class HeadersApplication extends ResourceConfig {
    public HeadersApplication() {
        register(HeadersResource.class).register(HeadersMBRW.class);

        // Turn off Monitoring to not affect benchmarks.
        property(ServerProperties.MONITORING_ENABLED, false);
        property(ServerProperties.MONITORING_STATISTICS_ENABLED, false);
        property(ServerProperties.MONITORING_STATISTICS_MBEANS_ENABLED, false);

        property(ServerProperties.WADL_FEATURE_DISABLE, true);
        property(CommonProperties.PROVIDER_DEFAULT_DISABLE, "ALL");
    }
}
