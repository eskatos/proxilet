# proxilet

> Servlet that act as a reverse proxy.

Based on ProxiServlet from Jason Edwards, available under the Apache Licence V2
http://edwardstx.net/wiki/Wiki.jsp?page=HttpProxyServlet

Patched to skip "Transfer-Encoding: chunked" headers, and avoid double slashes in proxied URL's.
http://code.google.com/p/google-web-toolkit/issues/detail?id=3131#c40

See also http://raibledesigns.com/rd/entry/how_to_do_cross_domain

