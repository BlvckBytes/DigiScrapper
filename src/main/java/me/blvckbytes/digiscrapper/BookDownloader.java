package me.blvckbytes.digiscrapper;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BookDownloader {

  private File tokenFile, outputDir;
  private Map< String, String > tokens;
  private BrowserCookie cookie;
  private CloseableHttpClient client;
  private ExecutorService pageExec;
  private ExecutorService depExec;

  /**
   * Downloads all books frsom a provided token file. This file needs to contain
   * token and book-title in csv format. Comments start with #
   * @param tokenFile File containing token csv
   * @param outputDir Folder containing book pages
   */
  public BookDownloader( File tokenFile, File outputDir ) {
    this.tokenFile = tokenFile;
    this.outputDir = outputDir;
    this.tokens = new HashMap<>();
    this.cookie = new BrowserCookie();

    // Threadpools for page download and page dependency download (images)
    // These numbers could be higher, sure, but my internet speed won't support it anyways...
    this.pageExec = Executors.newFixedThreadPool( 40 );
    this.depExec = Executors.newFixedThreadPool( 150 );

    // Create file if non existent
    try {
      if( this.tokenFile.exists() && this.tokenFile.createNewFile() )
        System.out.println( "Provided tokenfile did not exist, made a new one!" );
    } catch ( IOException e ) {
      e.printStackTrace();
    }

    System.out.println( "Starting to download book pages..." );

    // Begin processing
    this.client = Utils.createFastClient();
    readTokens();
    processTokens();
  }

  /**
   * Process all tokens in order to receive all books with corresponding images
   */
  private void processTokens() {
    // List all book-token folders that already exist
    Set< String > done = new HashSet<>();
    for( File f : Objects.requireNonNull( this.outputDir.listFiles() ) )
      done.add( f.getName() );

    // Loop tokens
    for( String token : this.tokens.keySet() ) {
      // Check if this book has already been downloaded, then skip it
      if( done.contains( token ) )
        continue;

      // Get url template from token activation and download pages
      downloadBook( token, activateToken( token ) );
    }

    // Notify of completion
    System.out.println( "All books completed!" );
    System.out.println( "Shutting down..." );

    // Shut down threadpools
    this.depExec.shutdown();
    this.pageExec.shutdown();
  }

  /**
   * Get the last page from the current book
   * @param urlTemplate Url template used to download the book
   * @return Last page of this book
   */
  private int getLastPage( String urlTemplate ) {
    try {
      String url = urlTemplate.replace( "{{page}}/", "" ).replace( "{{file}}", "index.html?page=1" );
      HttpGet fReq = new HttpGet( url );
      fReq.addHeader( "Host", "a.digi4school.at" );
      fReq.addHeader( "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:76.0) Gecko/20100101 Firefox/76.0" );
      fReq.addHeader( "Cookie", this.cookie.generate() );

      CloseableHttpResponse resp = this.client.execute( fReq );
      String answer = EntityUtils.toString( resp.getEntity() );
      resp.close();

      // Parse out max page from #makeNavBar function
      int begin = answer.indexOf( "IDRViewer.makeNavBar" );
      answer = answer.substring( begin, answer.indexOf( ");", begin ) ).replace( " ", "" );
      answer = answer.substring( answer.indexOf( "(" ) + 1, answer.indexOf( "," ) );
      return Integer.parseInt( answer.trim() );
    } catch ( Exception e ) {
      e.printStackTrace();
      return 0;
    }
  }

  /**
   * Inject a bit of css in order to keep book page at full height,
   * otherwise it would collapse. This style is on the page which this
   * tool won't scrap, so I inject it
   * @param svg Svg file input
   * @return Svg file output
   */
  private String injectDimCSS( String svg ) {
    // Inject CSS to fully scale the image
    String beginInj = "<style type=\"text/css\"><![CDATA[";

    // SVG contains style tag, thus inject
    if( svg.contains( beginInj ) ) {
      int offset = svg.indexOf( beginInj ) + beginInj.length();
      String buf = svg.substring( 0, offset );
      buf += "\nsvg{height: 1300px;width:100%;}";
      svg = buf + svg.substring( offset );
    }

    // No style tag found, append a custom one
    else {
      String appendPoint = "<defs>";
      int offset = svg.indexOf( appendPoint ) + appendPoint.length();
      String buf = svg.substring( 0, offset );
      buf += "<style type=\"text/css\"><![CDATA[\nsvg{height: 1300px;width:100%;}]]></style>";
      svg = buf + svg.substring( offset );
    }

    return svg;
  }

  /**
   * Download all pages and needed images from a book into target
   * download directory
   * @param token Token of this book
   * @param urlTemplate Template of url from book with {{page}} and {{file}} placeholders
   */
  private void downloadBook( String token, String urlTemplate ) {

    // Resource was damaged, skip processing
    if( urlTemplate == null ) {
      try {
        // Create error file
        File errFile = new File( this.outputDir.getAbsolutePath() + "/" + token, "error.txt" );
        if( !errFile.exists() && !errFile.getParentFile().mkdirs() && !errFile.createNewFile() )
          throw new Exception( "Could not create error file!" );

        // Write error message
        PrintWriter writer = new PrintWriter( errFile );
        writer.print( "This resource was damaged serverside, so it got skipped.\n" );
        writer.print( "Maybe try again later!" );
        writer.close();
      } catch ( Exception e ) {
        e.printStackTrace();
      }
      return;
    }

    // Keeping track of progress and status
    AtomicInteger finishCounter = new AtomicInteger( 0 );
    AtomicInteger initCounter = new AtomicInteger( 0 );

    // Loop all available pages
    int maxPage = getLastPage( urlTemplate );
    for( int i = 1; i <= maxPage; i++ ) {

      // Initialized, keep track
      int finalI = i;
      initCounter.incrementAndGet();

      // Execute request async
      pageExec.execute( () -> {
        try {
          // Create request for current page
          String currUrl = urlTemplate.replace( "{{page}}", String.valueOf( finalI ) );
          HttpGet pageReq = new HttpGet( currUrl.replace( "{{file}}", finalI + ".svg" ) );
          pageReq.addHeader( "Host", "a.digi4school.at" );
          pageReq.addHeader( "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:76.0) Gecko/20100101 Firefox/76.0" );
          pageReq.addHeader( "Cookie", this.cookie.generate() );

          // Make request and get result
          CloseableHttpResponse resp = client.execute( pageReq );

          // End of book reached, break loop and shut down pool
          if( resp.getStatusLine().getStatusCode() == 404 ) {
            // Trash request, decrement initializeds
            initCounter.decrementAndGet();
            resp.close();
            return;
          }

          // Update cookies
          for( Header header : resp.getHeaders( "Set-Cookie" ) )
            this.cookie.write( header.getValue() );

          // Get result and close
          String svg = EntityUtils.toString( resp.getEntity() );
          resp.close();

          svg = injectDimCSS( svg );

          // Create page file
          File page = new File( this.outputDir.getAbsolutePath() + "/" + token, finalI + ".svg" );
          if( !page.exists() && !page.getParentFile().mkdirs() && !page.createNewFile() )
            throw new Exception( "Could not create page file!" );

          // Download all needed dependencies (images, shades, ...)
          // This also unique-ifys the image names, thus re-set svg
          String processedSVG = downloadDependencies( currUrl, token, svg );

          // Write out page
          PrintWriter writer = new PrintWriter( page );
          writer.print( processedSVG );
          writer.close();

          // Increment counters
          finishCounter.incrementAndGet();
          System.out.println( "Page " + finalI + " from book-token " + token + " done" );
        } catch ( Exception e ) {
          e.printStackTrace();
        }
      } );
    }

    // Wait for completion
    while ( finishCounter.get() != initCounter.get() ) {
      try {
        Thread.sleep( 100 );
      } catch ( InterruptedException e ) {
        e.printStackTrace();
      }
    }
  }

  /**
   * This downloads all dependencies a book-page needs
   * @param currUrl Current url template with {{file}} placeholder
   * @param token Name of containing folder
   * @param svg SVG with all it's dependencies
   */
  private String downloadDependencies( String currUrl, String token, String svg ) {
    // Buffer to be modified in thread below
    String svgBuf = svg;

    // Regex to find all image tags
    Pattern pattern = Pattern.compile( "<image[^<>]+/>" );
    Matcher matcher = pattern.matcher( svgBuf );

    // Two counters in order to keep track of processed items
    AtomicInteger initiated = new AtomicInteger( 0 );
    AtomicInteger done = new AtomicInteger( 0 );

    // Loop occurances
    while( matcher.find() ) {
      String found = matcher.group();

      // Regex to find image links
      Pattern linkP = Pattern.compile( "href=\"[^\"]+\"" );
      Matcher linkM = linkP.matcher( found );

      // Damaged tag
      if( !linkM.find() )
        continue;

      // Initialize file donwload
      String identifier = UUID.randomUUID().toString();
      String imgName = linkM.group().split( "=" )[ 1 ].replaceAll( "\"", "" );

      // Replace file name in svg code
      String newName = imgName.replaceAll( "([^./]+).([^.]+)$", identifier + ".$2" );
      svgBuf = svgBuf.replace( imgName, newName );

      // Download image
      initiated.incrementAndGet();
      String dUrl = currUrl.replace( "{{file}}", imgName );

      // Execute download asynchronously
      depExec.execute( () -> {
        downloadImage( dUrl, token, newName );
        done.incrementAndGet();
      } );
    }

    // Wait for completion
    while( initiated.get() != done.get() ) {
      try {
        Thread.sleep( 90 );
      } catch ( InterruptedException e ) {
        e.printStackTrace();
      }
    }

    // Done with dependencies, shut down executor and return
    return svgBuf;
  }

  /**
   * Download an image from the provided url and save it as the provided
   * file name in the img directory for later use with svg files
   * @param url Url of image
   * @param token Name of the containing folder
   * @param fileName Name of output file
   */
  private void downloadImage( String url, String token, String fileName ) {
    try {
      // Make directory if non existent
      if( !this.outputDir.exists() && !this.outputDir.mkdir() )
        throw new Exception( "Unable to create output directory!" );

      HttpGet imgReq = new HttpGet( url );
      imgReq.addHeader( "Host", "a.digi4school.at" );
      imgReq.addHeader( "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:76.0) Gecko/20100101 Firefox/76.0" );
      imgReq.addHeader( "Cookie", this.cookie.generate() );

      // Get output stream of binary object
      CloseableHttpResponse clResp = client.execute( imgReq );
      HttpEntity resp = clResp.getEntity();
      File of = new File( this.outputDir.getAbsolutePath() + "/" + token, fileName );

      // Create file and parent dirs if non existent
      if( !of.exists() && !of.getParentFile().mkdirs() && !of.createNewFile() )
        throw new Exception( "Could not create image output file!" );

      // Create ouput stream on file handle
      FileOutputStream fos = new FileOutputStream( of );

      // Write to file
      resp.writeTo( fos );
      fos.close();
      clResp.close();
    } catch ( Exception e ) {
      e.printStackTrace();
    }
  }

  /**
   * Read the tokens with corresponding book title
   * into lookup map
   */
  private void readTokens() {
    try {
      Scanner s = new Scanner( this.tokenFile );

      // Read all lines
      while( s.hasNextLine() ) {
        String line = s.nextLine();

        // Skip comments
        if( line.startsWith( "#" ) )
          continue;

        String[] data = line.split( ";", 2 );

        // Input format mismatch, skip
        if( data.length != 2 )
          continue;

        // Map token-sublink to booktitle
        this.tokens.put( data[ 0 ].replace( "/token/", "" ), data[ 1 ] );
      }

      s.close();
      System.out.println( "Loaded " + this.tokens.size() + " tokens from file!" );
    } catch ( Exception e ) {
      e.printStackTrace();
    }
  }

  /**
   * Activate a token and return the final book's url which is capable of
   * selecting pages over url get params (?page=x)
   * @param token Token to activate on current cookie session
   * @return Final book url as string
   */
  private String activateToken( String token ) {
    try {
      // Create http-client and a post request object
      HttpGet request = new HttpGet( "https://digi4school.at/token/" + token );
      request.addHeader( "Origin", "https://digi4school.at" );
      request.addHeader( "Referer", "https://digi4school.at/openlib" );
      request.addHeader( "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:76.0) Gecko/20100101 Firefox/76.0" );
      request.addHeader( "Cookie", this.cookie.generate() );

      // Find out what the redirect endpoint is
      HttpClientContext context = HttpClientContext.create();
      CloseableHttpResponse resp = client.execute( request, context );

      // Update cookies
      for( Header header : resp.getHeaders( "Set-Cookie" ) )
        this.cookie.write( header.getValue() );

      // Process first stage LTI auth
      resp = followLTI( resp );

      // Damaged, cancel...
      if( resp == null )
        return null;

      // Process second stage LTI auth
      resp = followLTI( resp );

      // Damaged, cancel...
      if( resp == null )
        return null;

      String bookLoc = resp.getLastHeader( "Location" ).getValue().replaceAll( ":[0-9]+", "" );

      HttpGet bookRequest = new HttpGet( bookLoc );
      bookRequest.addHeader( "Host", "a.digi4school.at" );
      bookRequest.addHeader( "Referer", "https://kat.digi4school.at/" );
      bookRequest.addHeader( "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:76.0) Gecko/20100101 Firefox/76.0" );
      bookRequest.addHeader( "Cookie", this.cookie.generate() );

      // Make request, get answer and close resources
      client = HttpClients.createDefault();
      resp = client.execute( bookRequest );
      String servAnswer = EntityUtils.toString( resp.getEntity() );
      resp.close();

      // Check if this book has extra material provided, if so - append id/ to url in order to get the book itself
      if( !( servAnswer.contains( "id=\"mainContent\"" ) || servAnswer.contains( "id='mainContent'" ) ) ) {

        // Parse out target link id of first thumbnail (the book)
        Document doc = Jsoup.parse( servAnswer );
        Element thumbnails = doc.selectFirst( "#content" );
        String tarLink = thumbnails.selectFirst( "a" ).attr( "href" );
        tarLink = tarLink.substring( 0, tarLink.indexOf( "/" ) );

        bookLoc += tarLink + "/";
      }

      // Return with page template
      return bookLoc + "{{page}}/{{file}}";
    } catch ( Exception e ) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * Process the lti request prompt which is represented by a hidden form that just
   * needs to be postet on the given url. This should be processed by javascript,
   * but this bot obviously works differently
   * @param lastResp Prompt from webpage (as response)
   * @return Response from webpage
   * @throws Exception Errors in the process
   */
  private CloseableHttpResponse followLTI( CloseableHttpResponse lastResp ) throws Exception {
    // Parse page and grab LTI form
    String formPrompt = EntityUtils.toString( lastResp.getEntity() );

    // Serverside error with auth
    if( lastResp.getStatusLine().getStatusCode() != 200 )
      return null;

    Document page = Jsoup.parse( formPrompt );
    Element form = page.selectFirst( "#lti" );

    // Get post target and encode type
    String targetPath = form.attr( "action" );

    // Create post request with type form and current token as referrer
    HttpPost formReq = new HttpPost( targetPath );
    formReq.addHeader( "Origin", "https://digi4school.at" );
    formReq.addHeader( "Host", "kat.digi4school.at" );
    formReq.addHeader( "Referer", "https://digi4school.at/" );
    formReq.addHeader( "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:76.0) Gecko/20100101 Firefox/76.0" );

    // Append form data
    List< BasicNameValuePair > paramList = new ArrayList<>();
    for( Element input : form.select( "input" ) )
      paramList.add( new BasicNameValuePair( input.attr( "name" ), input.attr( "value" ) ) );

    // Append body and execute
    formReq.setEntity( new UrlEncodedFormEntity( paramList ) );

    // Execute and get
    CloseableHttpResponse resp = client.execute( formReq );

    // Update cookies
    for( Header header : resp.getHeaders( "Set-Cookie" ) )
      this.cookie.write( header.getValue() );

    // Return
    return resp;
  }
}
