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

import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;

import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.demo.cluster.tictactoe.service.GamesService;
import org.cometd.oort.Oort;
import org.cometd.oort.Seti;

public class StartupServlet extends GenericServlet {
    @Override
    public void init() throws ServletException {
        try {
            Seti seti = (Seti)getServletContext().getAttribute(Seti.SETI_ATTRIBUTE);
            Oort oort = seti.getOort();
            BayeuxServer bayeuxServer = oort.getBayeuxServer();

            // Services here are instantiated using constructor dependency injection instead of @Inject.
            // This guarantees that services are initialized in the right order, avoiding that a
            // service B that depends on another service A is initialized before service A.
            // Instead of using CometD's annotation servlet (that can only instantiate parameterless services)
            // we create and use a ServerAnnotationProcessor manually.
            ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeuxServer, oort, seti);
            GamesService gamesService = new GamesService();
            processor.process(gamesService);


            // TODO
        } catch (Exception x) {
            throw new ServletException(x);
        }
    }

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException {
        throw new UnavailableException("Configuration Servlet");
    }
}
