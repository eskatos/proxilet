/*
 * Copyright (c) 200?, Oleg Kalnichevski. All Rights Reserved.
 * Copyright (c) 2009, Laurent Morel. All Rights Reserved.
 * Copyright (c) 2009, Paul Merlin. All Rights Reserved.
 * Copyright (c) 2009, Fabien Barbero. All Rights Reserved.
 * Copyright (c) 2009, David Emo. All Rights Reserved.
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.commons.httpclient.ConnectTimeoutException;
import org.apache.commons.httpclient.HttpClientError;
import org.apache.commons.httpclient.params.HttpConnectionParams;
import org.apache.commons.httpclient.protocol.SecureProtocolSocketFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class EasySSLProtocolSocketFactory
        implements SecureProtocolSocketFactory
{

    private static final Logger LOGGER = LoggerFactory.getLogger( EasySSLProtocolSocketFactory.class );
    private SSLContext sslcontext = null;

    public EasySSLProtocolSocketFactory()
    {
        super();
    }

    private static SSLContext createEasySSLContext()
    {
        try {

            TrustManager[] trustAllManager = new TrustManager[]{
                new X509TrustManager()
                {

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers()
                    {
                        return null;
                    }

                    @Override
                    public void checkClientTrusted(
                            java.security.cert.X509Certificate[] certs, String authType )
                    {
                    }

                    @Override
                    public void checkServerTrusted(
                            java.security.cert.X509Certificate[] certs, String authType )
                    {
                    }

                }
            };

            SSLContext context = SSLContext.getInstance( "SSL" );
            context.init( null, trustAllManager, null );

            return context;
        } catch ( Exception e ) {
            LOGGER.error( e.getMessage(), e );
            throw new HttpClientError( e.toString() );
        }
    }

    private SSLContext getSSLContext()
    {
        if ( this.sslcontext == null ) {
            this.sslcontext = createEasySSLContext();
        }
        return this.sslcontext;
    }

    @Override
    public Socket createSocket( String host, int port, InetAddress clientHost, int clientPort )
            throws IOException, UnknownHostException
    {
        return getSSLContext().getSocketFactory().createSocket( host, port, clientHost, clientPort );
    }

    @Override
    public Socket createSocket( String host, int port, InetAddress localAddress, int localPort, HttpConnectionParams params )
            throws IOException, UnknownHostException, ConnectTimeoutException
    {
        if ( params == null ) {
            throw new IllegalArgumentException( "Parameters may not be null" );
        }
        int timeout = params.getConnectionTimeout();
        SocketFactory socketfactory = getSSLContext().getSocketFactory();
        if ( timeout == 0 ) {
            return socketfactory.createSocket( host, port, localAddress, localPort );
        } else {
            Socket socket = socketfactory.createSocket();
            SocketAddress localaddr = new InetSocketAddress( localAddress, localPort );
            SocketAddress remoteaddr = new InetSocketAddress( host, port );
            socket.bind( localaddr );
            socket.connect( remoteaddr, timeout );
            return socket;
        }
    }

    @Override
    public Socket createSocket( String host, int port )
            throws IOException, UnknownHostException
    {
        return getSSLContext().getSocketFactory().createSocket( host, port );
    }

    @Override
    public Socket createSocket( Socket socket, String host, int port, boolean autoClose )
            throws IOException, UnknownHostException
    {
        return getSSLContext().getSocketFactory().createSocket( socket, host, port, autoClose );
    }

    @Override
    public boolean equals( Object obj )
    {
        return ( ( obj != null ) && obj.getClass().equals( EasySSLProtocolSocketFactory.class ) );
    }

    @Override
    public int hashCode()
    {
        return EasySSLProtocolSocketFactory.class.hashCode();
    }

}
