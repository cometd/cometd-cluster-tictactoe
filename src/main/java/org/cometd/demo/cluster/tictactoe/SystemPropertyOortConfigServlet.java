/*
 * Copyright (c) 2020-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.demo.cluster.tictactoe;

import java.util.Objects;

import javax.servlet.ServletConfig;

import org.cometd.oort.Oort;
import org.cometd.oort.OortConfigServlet;

public class SystemPropertyOortConfigServlet extends OortConfigServlet {
    @Override
    protected String provideOortURL() {
        return Objects.requireNonNull(System.getProperty("oort.url"));
    }

    @Override
    protected void configureCloud(ServletConfig config, Oort oort) {
        String oortCloud = Objects.requireNonNull(System.getProperty("oort.cloud"));
        oort.observeComet(oortCloud);
    }
}
