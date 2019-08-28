everit-async-httpclient-jettyclient
===================================

Jetty Client based implementation of the asynchronous Everit HTTP Client API.

## Usage

    // Instantiate and configure a jetty http client
    org.eclipse.jetty.client.HttpClient jettyClient = ...
    
    // Pass the jetty http client to the connector class
    HttpClient httpClient = new JettyClientHttpClient(jettyClient);
    
    // Use the Everit HTTP Client API to send HTTP requests. E.g.:
    
    // Craete the request with a builder
    HttpRequest request = HttpRequest.builder().url("https://mypage.com");
    
    // Send the request asynchronously
    Single<HttpResponse> responseSingle = httpClient.send(request);
    
    responseSingle.subscribe(httpResponse -> {
      System.out.println("Status: " + httpResponse.getStatus());
      
      // Get the body. It is low level to read the body with the
      // AsyncContentProvider interface, so it is better to use the Util
      // class to read the content as a Single.
      Single<String> bodySingle = AsyncContentUtil.readString(
          httpResponse.getBody(), StandardCharsets.UTF8);
      
      bodySingle.subscribe(content -> {
        httpResponse.close();
        System.out.println(content);
      }, error -> httpResponse.close());
    });

For more information, see the documentation of [Everit HTTP Client][0]

[0]: https://github.com/everit-org/everit-httpclient
