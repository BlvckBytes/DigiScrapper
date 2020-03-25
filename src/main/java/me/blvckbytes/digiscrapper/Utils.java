package me.blvckbytes.digiscrapper;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

public class Utils {

  /**
   * Creates a client that can be used in multithreading environments, thus
   * it can handle a lot of requests in short amounts of time without timeouting
   * @return CloseableHttpClient for further use
   */
  public static CloseableHttpClient createFastClient() {
    PoolingHttpClientConnectionManager pm = new PoolingHttpClientConnectionManager();
    pm.setMaxTotal( 100 );
    pm.setDefaultMaxPerRoute( 30 );

    // With many requests a bit of latency needs to be tolerated
    int timeout = 5;
    RequestConfig config = RequestConfig.custom()
      .setConnectTimeout( timeout * 1000 )
      .setConnectionRequestTimeout( timeout * 1000 )
      .setSocketTimeout( timeout * 1000 ).build();

    // Create client
    return HttpClients.custom()
      .setConnectionManager( pm )
      .setDefaultRequestConfig( config )
      .disableContentCompression()
      .build();
  }

}
