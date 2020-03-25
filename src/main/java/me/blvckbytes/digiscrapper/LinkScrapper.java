package me.blvckbytes.digiscrapper;

import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LinkScrapper {

  private ConcurrentHashMap< String, String > uniqueLinks;
  private File tokenFile, outputDirectory;
  private ExecutorService exec;
  private String basePath;
  private CloseableHttpClient client;

  /**
   * Scraps for token sublinks from digi4school.at, a page which currently
   * offers all books for free access :). From here I can later further process this
   * by getting all pages from the book and collecting them into pdfs with the
   * scrapped title applied
   */
  public LinkScrapper() {
    this.uniqueLinks = new ConcurrentHashMap<>();

    // Max. 50 concurrent threads
    this.exec = Executors.newFixedThreadPool( 50 );

    // Start processing
    initializeFiles();
    begin();
  }

  /**
   * Begin either scrapping links or go straight to downloading books since
   * token list has been downloaded in a past session already
   */
  private void begin() {
    // Begin scrapping if file is non existent
    if( !this.tokenFile.exists() ) {
      System.out.println( "Token-file did not exist, starting to scrap tokens..." );
      this.client = Utils.createFastClient();
      loopCombinations( () -> new BookDownloader( this.tokenFile, this.outputDirectory ) );
    } else {
      System.out.println( "Token-file exists, skipping scrapping process!" );
      new BookDownloader( this.tokenFile, this.outputDirectory );
    }
  }

  /**
   * Get the base path (place of execution) and create output directory
   * for further use in downloader
   */
  private void initializeFiles() {
    // Fetch base path of jar
    try {
      this.basePath = getClass().getProtectionDomain().getCodeSource().getLocation().toURI().getPath();

      // Remove file if exists
      if( this.basePath.endsWith( ".jar" ) )
        this.basePath = this.basePath.substring( 0, this.basePath.lastIndexOf( "/" ) );
    } catch ( Exception e ) {
      e.printStackTrace();
    }

    System.out.println( "Base path is: " + this.basePath );

    // Get file
    this.tokenFile = new File( this.basePath, "tokenlist.csv" );
    this.outputDirectory = new File( this.basePath, "bookpages" );

    // Create dir
    if( !this.outputDirectory.exists() && !this.outputDirectory.mkdir() )
      System.out.println( "Could not create output directory! CRITICAL" );
  }

  /**
   * Generate combinations for a three letter string containing only
   * lower case alphabetic letters
   * @return String[] with all combinations
   */
  private String[] generateCombinations() {
    // Buffer for all combinations
    String[] combinations = new String[ ( int ) Math.pow( 26, 3 ) ];

    // Loop all combinations for three length string
    int index = 0;
    for ( int i = 97; i <= 122; i++ ) {
      for ( int j = 97; j <= 122; j++ ) {
        for ( int k = 97; k <= 122; k++ ) {

          // Build current combination and add it to buffer
          String currComb = String.valueOf( ( char ) i ) + ( char ) j + ( char ) k;
          combinations[ index ] = currComb;
          index++;
        }
      }
    }

    return combinations;
  }

  /**
   * Write the results to file
   */
  private void writeResults( int currOffset ) {
    StringBuilder lines = new StringBuilder();

    // Header for the file with some informations
    lines.append( "# These are all scrapped links from the page" ).append( System.lineSeparator() );
    lines.append( "# Last from-index was: " ).append( currOffset ).append( System.lineSeparator() );
    lines.append( "# Timestamp of writing this to file: " ).append( System.currentTimeMillis() ).append( System.lineSeparator() );
    lines.append( "# Format: Token-Sublink;Title" ).append( System.lineSeparator() );

    // Collect all lines in CSV format
    for( Map.Entry< String, String > entries : this.uniqueLinks.entrySet() ) {
      lines.append( entries.getKey() ).append( ";" ).append( entries.getValue() ).append( System.lineSeparator() );
    }

    try {
      // Create file if non existent
      if( !this.tokenFile.exists() && !this.tokenFile.createNewFile() ) {
        System.out.println( "File could not be created, cancelling!" );
        return;
      }

      // Write out
      BufferedWriter writer = new BufferedWriter( new FileWriter( this.tokenFile ) );
      writer.write( lines.toString() );
      writer.close();

      System.out.println( "Wrote all lines." );
    } catch ( Exception e ) {
      e.printStackTrace();
    }
  }

  /**
   * Since the text box only allows to enter at least three letters,
   * we need to loop all combinations of length three. This should yield
   * next to all book titles
   * @param done Callback when the function finishes (it's async)
   */
  private void loopCombinations( Runnable done ) {
    new Thread( () -> {
      // Send batches of requests while end has not been reached
      String[] combinations = generateCombinations();
      AtomicBoolean active = new AtomicBoolean( true );
      AtomicInteger status = new AtomicInteger( 0 );

      // Send out requests in range of combinations
      for ( String currTerm : combinations ) {
        // Stop when active state gets false
        if ( !active.get() )
          break;

        // One thread per combination
        exec.execute( () -> {
          // Get page with a certain timeout specified, then process it
          // Timeout can also mean that page blocked this client
          try {
            this.uniqueLinks.putAll( parseInformation( scrapPage( currTerm ) ) );
            status.incrementAndGet();
          }

          // Something timed out, since I don't wan't to loose stuff, just break
          // and write to file
          catch ( Exception e ) {
            System.out.println( "Timed out!" );
            active.set( false );
          }
        } );
      }

      // Wait for either finishing or cancelling
      while( status.get() != combinations.length && active.get() ) {
        System.out.println( "Scrapped " + status.get() + " / " + combinations.length + " combinations!" );
        try {
          Thread.sleep( 300 );
        } catch ( InterruptedException e ) {
          e.printStackTrace();
        }
      }

      // Done! Write to file
      System.out.println( "Done scrapping links, writing to file!" );
      writeResults( status.get() );
      done.run();
    } ).start();
  }

  /**
   * Parse the string into a html document and process out the links
   * with their corresponding book titles
   * @param html Page input
   * @return Map of link to title
   */
  private Map< String, String > parseInformation( String html ) {
    Map< String, String > buf = new HashMap<>();

    // Parse html and find all a tags
    Document doc = Jsoup.parse( html );
    Elements links = doc.select( "a" );

    // Append link and corresponding book title
    for( Element link : links ) {
      String href = link.attr( "href" );

      // Skip non book links
      if( !href.contains( "token" ) )
        continue;

      // Select title from first h1 element within the href
      String title = link.selectFirst( "h1" ).html();
      buf.put( href, title );
    }

    return buf;
  }

  /**
   * Scrap the content from the openlibrary's response to a given searchterm
   * @param search Searchterm to put in searchbar
   * @return HTML content of the server's response as a string
   */
  private String scrapPage( String search ) {
      try {
        // Create http-client and a post request object
        HttpPost request = new HttpPost( "https://digi4school.at/br/openshelf" );

        // All needed request headers for the site to accept the request
        request.addHeader( "Origin", "https://digi4school.at" );
        request.addHeader( "Referer", "https://digi4school.at/openlibrary" );
        request.addHeader( "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:76.0) Gecko/20100101 Firefox/76.0" );

        // Create parameter list with search term and other properties
        List< BasicNameValuePair > paramList = Arrays.asList(
          new BasicNameValuePair( "title", search ),
          new BasicNameValuePair( "publisher_id", "" ),
          new BasicNameValuePair( "level_of_education", "" )
        );

        // Set parameters to body and execute request
        request.setEntity( new UrlEncodedFormEntity( paramList ) );
        CloseableHttpResponse resp = client.execute( request );
        String content = EntityUtils.toString( resp.getEntity() );

        // Close resources and return
        resp.close();
        return content;
      } catch ( Exception e ) {
        return "";
      }
  }
}
