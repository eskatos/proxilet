/*
 * Copyright (c) 2010, Paul Merlin. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.codeartisans.proxilet;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;

import org.junit.Assert;
import org.junit.Test;

public class ProxiletTest
{

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8958;
    private static boolean gotGetOnTarget = false;

    @Test
    public void test()
            throws Exception
    {
        Server server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setHost( HOST );
        connector.setPort( PORT );
        server.addConnector( connector );

        WebAppContext webapp = new WebAppContext();
        webapp.setResourceBase( "" );
        webapp.setContextPath( "/" );

        Proxilet proxilet = new Proxilet();

        ServletHolder servletHolder = new ServletHolder( proxilet );
        servletHolder.setInitParameter( "targetHost", HOST );
        servletHolder.setInitParameter( "targetPort", String.valueOf( PORT ) );
        servletHolder.setInitParameter( "prefixPath", "/target" );
        servletHolder.setInitParameter( "sourcePath", "/reverse" );
        webapp.addServlet( servletHolder, "/reverse" );
        webapp.addServlet( TargetServlet.class, "/target" );

        server.setHandler( webapp );
        server.setStopAtShutdown( true );

        server.start();

        HttpClient client = new HttpClient();
        HttpMethod get = new GetMethod( "http://" + HOST + ":" + PORT + "/reverse" );
        client.executeMethod( get );

        server.stop();

        Assert.assertTrue( gotGetOnTarget );
    }

    @SuppressWarnings( "PublicInnerClass" )
    public static class TargetServlet
            extends HttpServlet
    {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet( HttpServletRequest req, HttpServletResponse resp )
                throws ServletException, IOException
        {
            gotGetOnTarget = true;
        }

    }

}
