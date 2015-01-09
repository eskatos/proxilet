# proxilet

> Servlet that act as a reverse proxy.

[![Build Status](https://travis-ci.org/eskatos/proxilet.svg)](https://travis-ci.org/eskatos/proxilet)

The project is hosted in maven central.
[here](http://search.maven.org/#search%7Cga%7C1%7Cproxilet) you'll find a quick copy/paste for the dependency.

Based on ProxiServlet from Jason Edwards, available under the Apache Licence V2
http://edwardstx.net/wiki/Wiki.jsp?page=HttpProxyServlet

Patched to skip "Transfer-Encoding: chunked" headers, and avoid double slashes in proxied URL's.
http://code.google.com/p/google-web-toolkit/issues/detail?id=3131#c40

See also http://raibledesigns.com/rd/entry/how_to_do_cross_domain

