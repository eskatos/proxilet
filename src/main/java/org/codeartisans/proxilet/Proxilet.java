/*
 * Copyright (c) 2007, Jason Edwards. All Rights Reserved.
 * Copyright (c) 2010, Laurent Morel. All Rights Reserved.
 * Copyright (c) 2010, Fabien Barbero. All Rights Reserved.
 * Copyright (c) 2010, David Emo. All Rights Reserved.
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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.methods.multipart.ByteArrayPartSource;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.methods.multipart.StringPart;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet that act as a reverse proxy.
 *
 * Based on ProxiServlet from Jason Edwards, available under the Apache Licence V2
 * http://edwardstx.net/wiki/Wiki.jsp?page=HttpProxyServlet
 * <p/>
 * Patched to skip "Transfer-Encoding: chunked" headers, and avoid double slashes in proxied URL's.
 * http://code.google.com/p/google-web-toolkit/issues/detail?id=3131#c40
 * <p/>
 * See also http://raibledesigns.com/rd/entry/how_to_do_cross_domain
 * and a comment about charset encoding for getWriter() below...
 */
public class Proxilet
        extends HttpServlet
{

    private static final Logger LOGGER = LoggerFactory.getLogger( Proxilet.class );

    static {
        // TODO Put a boolean init parameter for this
        System.setProperty( "sun.security.ssl.allowUnsafeRenegotiation", "true" );
        // So that any server connection is accepted : (port number is only the default one)
        Protocol easyhttps = new Protocol( "https", ( ProtocolSocketFactory ) new EasySSLProtocolSocketFactory(), 443 );
        Protocol.registerProtocol( "https", easyhttps );
    }

    private static final Protocol SSL_PROTOCOL = new Protocol( "https", ( ProtocolSocketFactory ) new EasySSLProtocolSocketFactory(), 443 );

    private static final int FOUR_KB = 4196;

    private static final long serialVersionUID = 1L;

    private static final String HEADER_LOCATION = "Location";

    private static final String HEADER_CONTENT_TYPE = "Content-Type";

    private static final String HEADER_CONTENT_LENGTH = "Content-Length";

    private static final String HEADER_HOST = "Host";

    private static final File FILE_UPLOAD_TEMP_DIRECTORY = new File( System.getProperty( "java.io.tmpdir" ) );
    // Target host params

    private String targetHost;

    private int targetPort = 80;

    private boolean targetSsl;

    private String targetCredentials; // format is user:password

    /**
     * The (optional) path on the proxy host to which we are proxying requests. Default value is "".
     */
    private String proxyPath = "";

    private String stringPrefixPath = "";

    private String stringMimeType = "text/x-gwt-rpc";

    private String stringSourcePath = "";

    private String stringDestinationPath = "";

    private String[] stringForwardTypes;

    /**
     * Setting that allows removing the initial path from client. Allows specifying /twitter/* as synonym for
     * twitter.com.
     */
    private boolean removePrefix = false;

    private int maxFileUploadSize = 5 * 1024 * 1024; // Defaults to 5MB

    private boolean followRedirects;

    @Override
    public void init( ServletConfig servletConfig )
    {
        // Get the proxy host
        String stringProxyHostNew = servletConfig.getInitParameter( "targetHost" );
        if ( stringProxyHostNew == null || stringProxyHostNew.length() == 0 ) {
            throw new IllegalArgumentException( "Proxy host not set, please set init-param 'targetHost' in web.xml" );
        }
        targetHost = stringProxyHostNew;
        // Get the proxy port if specified
        String stringProxyPortNew = servletConfig.getInitParameter( "targetPort" );
        if ( stringProxyPortNew != null && stringProxyPortNew.length() > 0 ) {
            targetPort = Integer.parseInt( stringProxyPortNew );
        }

        // Get the credentials, if specified
        String strProxyCredentials = servletConfig.getInitParameter( "targetCredentials" );
        if ( strProxyCredentials != null && strProxyCredentials.length() > 0 ) {
            targetCredentials = strProxyCredentials;
        }
        // Get the secure flag if specified
        String secure = servletConfig.getInitParameter( "targetSsl" );
        if ( secure != null && secure.length() > 0 ) {
            targetSsl = "true".equalsIgnoreCase( secure );
        }
        // Get the proxy path if specified
        String stringProxyPathNew = servletConfig.getInitParameter( "proxyPath" );
        if ( stringProxyPathNew != null && stringProxyPathNew.length() > 0 ) {
            proxyPath = stringProxyPathNew;
        }

        String strPrefixPath = servletConfig.getInitParameter( "prefixPath" );
        if ( strPrefixPath != null && strPrefixPath.length() > 0 ) {
            stringPrefixPath = strPrefixPath;
        }

        String strMimeType = servletConfig.getInitParameter( "mimeType" );
        if ( strMimeType != null && strMimeType.length() > 0 ) {
            stringMimeType = strMimeType;
        }

        String strForwardTypes = servletConfig.getInitParameter( "forwardTypes" );
        if ( strForwardTypes != null && strForwardTypes.length() > 0 ) {
            stringForwardTypes = strForwardTypes.split( "," );
        }

        String strRemovePrexif = servletConfig.getInitParameter( "removePrefix" );
        if ( strRemovePrexif != null && strRemovePrexif.length() > 0 ) {
            removePrefix = Boolean.valueOf( strRemovePrexif );
        }

        // Get the maximum file upload size if specified
        String stringMaxFileUploadSize = servletConfig.getInitParameter( "maxFileUploadSize" );
        if ( stringMaxFileUploadSize != null && stringMaxFileUploadSize.length() > 0 ) {
            maxFileUploadSize = Integer.parseInt( stringMaxFileUploadSize );
        }

        String strSourcePath = servletConfig.getInitParameter( "sourcePath" );
        if ( strSourcePath != null && strSourcePath.length() > 0 ) {
            stringSourcePath = strSourcePath;
        }

        String strDestinationPath = servletConfig.getInitParameter( "destinationPath" );
        if ( strDestinationPath != null && strDestinationPath.length() > 0 ) {
            stringDestinationPath = strDestinationPath;
        }

    }

    /**
     * Performs an HTTP GET request.
     *
     * @param httpServletRequest    The {@link HttpServletRequest} object passed in by the servlet engine representing the client request to be proxied
     * @param httpServletResponse   The {@link HttpServletResponse} object by which we can send a proxied response to the client
     */
    @Override
    public void doGet( HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse )
            throws IOException, ServletException
    {
        // Create a GET request
        String destinationUrl = this.getProxyURL( httpServletRequest );
        LOGGER.trace( "GET {} => {}", httpServletRequest.getRequestURL(), destinationUrl );
        GetMethod getMethodProxyRequest = new GetMethod( destinationUrl );
        // Forward the request headers
        setProxyRequestHeaders( httpServletRequest, getMethodProxyRequest );
        // Execute the proxy request
        this.executeProxyRequest( getMethodProxyRequest, httpServletRequest, httpServletResponse );
    }

    /**
     * Performs an HTTP POST request.
     *
     * @param httpServletRequest    The {@link HttpServletRequest} object passed in by the servlet engine representing the client request to be proxied
     * @param httpServletResponse   The {@link HttpServletResponse} object by which we can send a proxied response to the client
     */
    @Override
    public void doPost( HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse )
            throws IOException, ServletException
    {
        // Create a standard POST request
        String contentType = httpServletRequest.getContentType();
        String destinationUrl = this.getProxyURL( httpServletRequest );
        LOGGER.trace( "POST {} => {} ({})" + httpServletRequest.getRequestURL(), destinationUrl, contentType );
        PostMethod postMethodProxyRequest = new PostMethod( destinationUrl );
        // Forward the request headers
        setProxyRequestHeaders( httpServletRequest, postMethodProxyRequest );
        // Check if this is a mulitpart (file upload) POST
        if ( ServletFileUpload.isMultipartContent( httpServletRequest ) ) {
            this.handleMultipartPost( postMethodProxyRequest, httpServletRequest );
        } else {
            if ( contentType == null || PostMethod.FORM_URL_ENCODED_CONTENT_TYPE.equals( contentType ) ) {
                this.handleStandardPost( postMethodProxyRequest, httpServletRequest );
            } else {
                this.handleContentPost( postMethodProxyRequest, httpServletRequest );
            }
        }
        // Execute the proxy request
        this.executeProxyRequest( postMethodProxyRequest, httpServletRequest, httpServletResponse );
    }

    /**
     * Sets up the given {@link PostMethod} to send the same multipart POST data as was sent in the given
     * {@link HttpServletRequest}.
     *
     * @param postMethodProxyRequest    The {@link PostMethod} that we are configuring to send a multipart POST request
     * @param httpServletRequest    The {@link HttpServletRequest} that contains the mutlipart POST data to be sent via the {@link PostMethod}
     */
    @SuppressWarnings( "unchecked" )
    private void handleMultipartPost( PostMethod postMethodProxyRequest, HttpServletRequest httpServletRequest )
            throws ServletException
    {
        // Create a factory for disk-based file items
        DiskFileItemFactory diskFileItemFactory = new DiskFileItemFactory();
        // Set factory constraints
        diskFileItemFactory.setSizeThreshold( maxFileUploadSize );
        diskFileItemFactory.setRepository( FILE_UPLOAD_TEMP_DIRECTORY );
        // Create a new file upload handler
        ServletFileUpload servletFileUpload = new ServletFileUpload( diskFileItemFactory );
        // Parse the request
        try {
            // Get the multipart items as a list
            List<FileItem> listFileItems = ( List<FileItem> ) servletFileUpload.parseRequest( httpServletRequest );
            // Create a list to hold all of the parts
            List<Part> listParts = new ArrayList<Part>();
            // Iterate the multipart items list
            for ( FileItem fileItemCurrent : listFileItems ) {
                // If the current item is a form field, then create a string part
                if ( fileItemCurrent.isFormField() ) {
                    StringPart stringPart = new StringPart( fileItemCurrent.getFieldName(), // The field name
                                                            fileItemCurrent.getString() // The field value
                            );
                    // Add the part to the list
                    listParts.add( stringPart );
                } else {
                    // The item is a file upload, so we create a FilePart
                    FilePart filePart = new FilePart( fileItemCurrent.getFieldName(), // The field name
                                                      new ByteArrayPartSource( fileItemCurrent.getName(), // The uploaded file name
                                                                               fileItemCurrent.get() // The uploaded file contents
                            ) );
                    // Add the part to the list
                    listParts.add( filePart );
                }
            }
            MultipartRequestEntity multipartRequestEntity = new MultipartRequestEntity(
                    listParts.toArray( new Part[]{} ), postMethodProxyRequest.getParams() );
            postMethodProxyRequest.setRequestEntity( multipartRequestEntity );
            // The current content-type header (received from the client) IS of
            // type "multipart/form-data", but the content-type header also
            // contains the chunk boundary string of the chunks. Currently, this
            // header is using the boundary of the client request, since we
            // blindly copied all headers from the client request to the proxy
            // request. However, we are creating a new request with a new chunk
            // boundary string, so it is necessary that we re-set the
            // content-type string to reflect the new chunk boundary string
            postMethodProxyRequest.setRequestHeader( HEADER_CONTENT_TYPE, multipartRequestEntity.getContentType() );
        } catch ( FileUploadException fileUploadException ) {
            throw new ServletException( fileUploadException );
        }
    }

    /**
     * Sets up the given {@link PostMethod} to send the same standard POST data as was sent in the given
     * {@link HttpServletRequest}.
     *
     * @param postMethodProxyRequest    The {@link PostMethod} that we are configuring to send a standard POST request
     * @param httpServletRequest        The {@link HttpServletRequest} that contains the POST data to be sent via the {@link PostMethod}
     */
    @SuppressWarnings( "unchecked" )
    private void handleStandardPost( PostMethod postMethodProxyRequest, HttpServletRequest httpServletRequest )
    {
        // Get the client POST data as a Map
        Map<String, String[]> mapPostParameters = ( Map<String, String[]> ) httpServletRequest.getParameterMap();
        // Create a List to hold the NameValuePairs to be passed to the PostMethod
        List<NameValuePair> listNameValuePairs = new ArrayList<NameValuePair>();
        // Iterate the parameter names
        for ( String stringParameterName : mapPostParameters.keySet() ) {
            // Iterate the values for each parameter name
            String[] stringArrayParameterValues = mapPostParameters.get( stringParameterName );
            for ( String stringParamterValue : stringArrayParameterValues ) {
                // Create a NameValuePair and store in list
                NameValuePair nameValuePair = new NameValuePair( stringParameterName, stringParamterValue );
                listNameValuePairs.add( nameValuePair );
            }
        }
        // Set the proxy request POST data
        postMethodProxyRequest.setRequestBody( listNameValuePairs.toArray( new NameValuePair[]{} ) );
    }

    /**
     * Sets up the given {@link PostMethod} to send the same content POST data (JSON, XML, etc.) as was sent in the
     * given {@link HttpServletRequest}.
     *
     * @param postMethodProxyRequest    The {@link PostMethod} that we are configuring to send a standard POST request
     * @param httpServletRequest        The {@link HttpServletRequest} that contains the POST data to be sent via the {@link PostMethod}
     */
    private void handleContentPost( PostMethod postMethodProxyRequest, HttpServletRequest httpServletRequest )
            throws IOException, ServletException
    {
        StringBuilder content = new StringBuilder();
        BufferedReader reader = httpServletRequest.getReader();
        for ( ;; ) {
            String line = reader.readLine();
            if ( line == null ) {
                break;
            }
            content.append( line );
        }

        String contentType = httpServletRequest.getContentType();
        String postContent = content.toString();

        // Hack to trickle main server gwt rpc servlet
        // this avoids warnings like the following :
        // "ERROR: The module path requested, /testmodule/, is not in the same web application as this servlet"
        // or
        // "WARNING: Failed to get the SerializationPolicy '29F4EA1240F157649C12466F01F46F60' for module 'http://localhost:8888/testmodule/'"
        //
        // Actually it avoids a NullPointerException in server logging :
        // See http://code.google.com/p/google-web-toolkit/issues/detail?id=3624
        if ( contentType.startsWith( this.stringMimeType ) ) {
            String clientHost = httpServletRequest.getLocalName();
            if ( clientHost.equals( "127.0.0.1" ) || clientHost.equals( "0:0:0:0:0:0:0:1" ) ) {
                clientHost = "localhost";
            }

            int clientPort = httpServletRequest.getLocalPort();
            String clientUrl = clientHost + ( ( clientPort != 80 ) ? ":" + clientPort : "" );
            String serverUrl = targetHost + ( ( targetPort != 80 ) ? ":" + targetPort : "" ) + stringPrefixPath;

            // Replace more completely if destination server is https :
            if ( targetSsl ) {
                clientUrl = "http://" + clientUrl;
                serverUrl = "https://" + serverUrl;
            }
            postContent = postContent.replace( clientUrl, serverUrl );
        }

        String encoding = httpServletRequest.getCharacterEncoding();
        LOGGER.trace( "POST Content Type: {} Encoding: {} Content: {}", new Object[]{ contentType, encoding, postContent } );
        StringRequestEntity entity;
        try {
            entity = new StringRequestEntity( postContent, contentType, encoding );
        } catch ( UnsupportedEncodingException e ) {
            throw new ServletException( e );
        }
        // Set the proxy request POST data
        postMethodProxyRequest.setRequestEntity( entity );
    }

    private HttpClient createClientWithLogin()
    {
        // Create a default HttpClient
        HttpClient httpClient = new HttpClient();
        Protocol.registerProtocol( "https", SSL_PROTOCOL );

        // if login/password authentication is required :
        if ( targetCredentials != null ) {
            httpClient.getParams().setAuthenticationPreemptive( true );
            String[] creds = targetCredentials.split( ":" );
            Credentials defaultcreds = new UsernamePasswordCredentials( creds[0], creds[1] );
            httpClient.getState().setCredentials( new AuthScope( targetHost, targetPort, AuthScope.ANY_REALM ), defaultcreds );
        }
        return httpClient;
    }

    /**
     * Executes the {@link HttpMethod} passed in and sends the proxy response back to the client via the given
     * {@link HttpServletResponse}.
     *
     * @param httpMethodProxyRequest    An object representing the proxy request to be made
     * @param httpServletResponse       An object by which we can send the proxied response back to the client
     * @throws IOException              Can be thrown by the {@link HttpClient}.executeMethod
     * @throws ServletException         Can be thrown to indicate that another error has occurred
     */
    private void executeProxyRequest( HttpMethod httpMethodProxyRequest, HttpServletRequest httpServletRequest,
                                      HttpServletResponse httpServletResponse )
            throws IOException, ServletException
    {
        // Create a default HttpClient
        HttpClient httpClient;
        httpClient = createClientWithLogin();
        httpMethodProxyRequest.setFollowRedirects( false );

        // Execute the request
        int intProxyResponseCode = httpClient.executeMethod( httpMethodProxyRequest );
//        String response = httpMethodProxyRequest.getResponseBodyAsString();

        // Check if the proxy response is a redirect
        // The following code is adapted from org.tigris.noodle.filters.CheckForRedirect
        // Hooray for open source software
        if ( intProxyResponseCode >= HttpServletResponse.SC_MULTIPLE_CHOICES /* 300 */
             && intProxyResponseCode < HttpServletResponse.SC_NOT_MODIFIED /* 304 */ ) {
            String stringStatusCode = Integer.toString( intProxyResponseCode );
            String stringLocation = httpMethodProxyRequest.getResponseHeader( HEADER_LOCATION ).getValue();
            if ( stringLocation == null ) {
                throw new ServletException( "Received status code: " + stringStatusCode + " but no " + HEADER_LOCATION + " header was found in the response" );
            }
            // Modify the redirect to go to this proxy servlet rather that the proxied host
            String stringMyHostName = httpServletRequest.getServerName();
            if ( httpServletRequest.getServerPort() != 80 ) {
                stringMyHostName += ":" + httpServletRequest.getServerPort();
            }
            stringMyHostName += httpServletRequest.getContextPath();
            if ( followRedirects ) {
                httpServletResponse.sendRedirect( stringLocation.replace( getProxyHostAndPort() + proxyPath, stringMyHostName ) );
                return;
            }
        } else if ( intProxyResponseCode == HttpServletResponse.SC_NOT_MODIFIED ) {
            // 304 needs special handling. See:
            // http://www.ics.uci.edu/pub/ietf/http/rfc1945.html#Code304
            // We get a 304 whenever passed an 'If-Modified-Since'
            // header and the data on disk has not changed; server
            // responds w/ a 304 saying I'm not going to send the
            // body because the file has not changed.
            httpServletResponse.setIntHeader( HEADER_CONTENT_LENGTH, 0 );
            httpServletResponse.setStatus( HttpServletResponse.SC_NOT_MODIFIED );
            return;
        }

        // Pass the response code back to the client
        httpServletResponse.setStatus( intProxyResponseCode );

        // Pass response headers back to the client
        Header[] headerArrayResponse = httpMethodProxyRequest.getResponseHeaders();
        for ( Header header : headerArrayResponse ) {
            if ( header.getName().equals( "Transfer-Encoding" ) && header.getValue().equals( "chunked" )
                 || header.getName().equals( "Content-Encoding" ) && header.getValue().equals( "gzip" ) ) {
                // proxy servlet does not support chunked encoding
            } else {
                httpServletResponse.setHeader( header.getName(), header.getValue() );
            }
        }

        List<Header> responseHeaders = Arrays.asList( headerArrayResponse );

        // FIXME We should handle both String and bytes response in the same way:
        String response = null;
        byte[] bodyBytes = null;

        if ( isBodyParameterGzipped( responseHeaders ) ) {
            LOGGER.trace( "GZipped: true" );
            if ( !followRedirects && intProxyResponseCode == HttpServletResponse.SC_MOVED_TEMPORARILY ) {
                response = httpMethodProxyRequest.getResponseHeader( HEADER_LOCATION ).getValue();
                httpServletResponse.setStatus( HttpServletResponse.SC_OK );
                intProxyResponseCode = HttpServletResponse.SC_OK;
                httpServletResponse.setHeader( HEADER_LOCATION, response );
                httpServletResponse.setContentLength( response.length() );
            } else {
                bodyBytes = ungzip( httpMethodProxyRequest.getResponseBody() );
                httpServletResponse.setContentLength( bodyBytes.length );
            }
        }

        if ( httpServletResponse.getContentType() != null
             && httpServletResponse.getContentType().contains( "text" ) ) {
            LOGGER.trace( "Received status code: {} Response: {}", intProxyResponseCode, response );
        } else {
            LOGGER.trace( "Received status code: {} [Response is not textual]", intProxyResponseCode );
        }

        // Send the content to the client
        if ( response != null ) {
            httpServletResponse.getWriter().write( response );
        } else if ( bodyBytes != null ) {
            httpServletResponse.getOutputStream().write( bodyBytes );
        } else {
            IOUtils.copy( httpMethodProxyRequest.getResponseBodyAsStream(), httpServletResponse.getOutputStream() );
        }
    }

    /**
     * The response body will be assumed to be gzipped if the GZIP header has been set.
     *
     * @param responseHeaders   of response headers
     * @return                  true if the body is gzipped
     */
    private boolean isBodyParameterGzipped( List<Header> responseHeaders )
    {
        for ( Header header : responseHeaders ) {
            if ( header.getValue().equals( "gzip" ) ) {
                return true;
            }
        }
        return false;
    }

    /**
     * A highly performant ungzip implementation.
     *
     * Do not refactor this without taking new timings. See ElementTest in ehcache for timings
     *
     * @param gzipped       the gzipped content
     * @return              an ungzipped byte[]
     * @throws IOException  when something bad happens
     */
    private byte[] ungzip( final byte[] gzipped )
            throws IOException
    {
        final GZIPInputStream inputStream = new GZIPInputStream( new ByteArrayInputStream( gzipped ) );
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream( gzipped.length );
        final byte[] buffer = new byte[ FOUR_KB ];
        int bytesRead = 0;
        while ( bytesRead != -1 ) {
            bytesRead = inputStream.read( buffer, 0, FOUR_KB );
            if ( bytesRead != -1 ) {
                byteArrayOutputStream.write( buffer, 0, bytesRead );
            }
        }
        byte[] ungzipped = byteArrayOutputStream.toByteArray();
        inputStream.close();
        byteArrayOutputStream.close();
        return ungzipped;
    }

    /**
     * Retreives all of the headers from the servlet request and sets them on the proxy request.
     *
     * @param httpServletRequest        The request object representing the client's request to the servlet engine
     * @param httpMethodProxyRequest    The request that we are about to send to the proxy host
     */
    @SuppressWarnings( "unchecked" )
    private void setProxyRequestHeaders( HttpServletRequest httpServletRequest, HttpMethod httpMethodProxyRequest )
    {
        // Get an Enumeration of all of the header names sent by the client
        Enumeration<String> enumerationOfHeaderNames = httpServletRequest.getHeaderNames();
        while ( enumerationOfHeaderNames.hasMoreElements() ) {
            String stringHeaderName = enumerationOfHeaderNames.nextElement();
            if ( stringHeaderName.equalsIgnoreCase( HEADER_CONTENT_LENGTH ) ) {
                continue;
            }
            // As per the Java Servlet API 2.5 documentation:
            // Some headers, such as Accept-Language can be sent by clients
            // as several headers each with a different value rather than
            // sending the header as a comma separated list.
            // Thus, we get an Enumeration of the header values sent by the client
            Enumeration<String> enumerationOfHeaderValues = httpServletRequest.getHeaders( stringHeaderName );
            while ( enumerationOfHeaderValues.hasMoreElements() ) {
                String stringHeaderValue = enumerationOfHeaderValues.nextElement();
                // In case the proxy host is running multiple virtual servers,
                // rewrite the Host header to ensure that we get content from
                // the correct virtual server
                if ( stringHeaderName.equalsIgnoreCase( HEADER_HOST ) ) {
                    stringHeaderValue = getProxyHostAndPort();
                }
                Header header = new Header( stringHeaderName, stringHeaderValue );
                // Set the same header on the proxy request
                httpMethodProxyRequest.setRequestHeader( header );
            }
        }
    }

    // Accessors
    private String getProxyURL( HttpServletRequest httpServletRequest )
    {
        // Set the protocol to HTTP
        String protocol = ( targetSsl ) ? "https://" : "http://";
        String stringProxyURL = protocol + this.getProxyHostAndPort();
//System.out.println("proxyURL 1="+stringProxyURL);

        String uri = httpServletRequest.getRequestURI();

        if ( !removePrefix ) {
            if ( uri.contains( this.stringSourcePath ) ) {
                stringProxyURL += stringPrefixPath + uri.replace( this.stringSourcePath, this.stringDestinationPath );
            } else {
                stringProxyURL += stringPrefixPath + uri;
            }
        }
//System.out.println("proxyURL 2="+stringProxyURL);

// LOLO : Ça double le dernier élément du path ?!
//        stringProxyURL += "/";
//
//        // Handle the path given to the servlet
//        String pathInfo = httpServletRequest.getPathInfo();
//System.out.println("pathInfo="+pathInfo);
//        if (pathInfo != null && pathInfo.startsWith("/")) {
//            if (stringProxyURL != null && stringProxyURL.endsWith("/")) {
//                // avoid double '/'
//                stringProxyURL += pathInfo.substring(1);
//System.out.println("proxyURL 3="+stringProxyURL);
//            }
//        } else {
//            if (pathInfo != null) {
//                stringProxyURL += httpServletRequest.getPathInfo();
//System.out.println("proxyURL 4="+stringProxyURL);
//            } else {
//                stringProxyURL = stringProxyURL.substring(0, stringProxyURL.length() - 1);
//System.out.println("proxyURL 5="+stringProxyURL);
//            }
//        }
        // Handle the query string
        if ( httpServletRequest.getQueryString() != null ) {
            stringProxyURL += "?" + httpServletRequest.getQueryString();
//System.out.println("proxyURL 6="+stringProxyURL);
        }
        return stringProxyURL;
    }

    private String getProxyHostAndPort()
    {
        if ( targetPort == 80 ) {
            return targetHost;
        } else {
            return targetHost + ":" + targetPort;
        }
    }

}
