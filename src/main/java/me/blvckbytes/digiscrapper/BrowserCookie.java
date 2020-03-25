package me.blvckbytes.digiscrapper;

import java.util.HashMap;
import java.util.Map;

public class BrowserCookie {

  private Map< String, String > cookies;

  /**
   * Simulates a browser's cookie management
   */
  public BrowserCookie() {
    this.cookies = new HashMap<>();
  }

  /**
   * Write and or update cookies to/from the browser session
   * @param setPrompt Set-Cookie prompt from the headers
   */
  public void write( String setPrompt ) {
    String cookie = setPrompt.split( ";" )[ 0 ].trim();
    String[] cData = cookie.split( "=" );

    // Damaged format received
    if( cData.length != 2 )
      return;

    // Put onto cookie map
    cookies.put( cData[ 0 ], cData[ 1 ] );
  }

  /**
   * Generates the Cookie value for browser requests
   * @return Accumulated cookies
   */
  public String generate() {
    StringBuilder builder = new StringBuilder();

    // Append cookies with ; as delimiter
    for( Map.Entry< String, String > entry : this.cookies.entrySet() )
      builder.append( entry.getKey() ).append( "=" ).append( entry.getValue() ).append( "; " );

    return builder.toString();
  }
}
