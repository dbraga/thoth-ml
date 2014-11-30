package com.trulia.thoth.quartz;

import com.trulia.thoth.Converter;
import com.trulia.thoth.pojo.ServerDetail;
import com.trulia.thoth.predictor.ModelHealth;
import com.trulia.thoth.predictor.StaticModelHealth;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;

/**
 * User: dbraga - Date: 7/21/14
 */
public class SamplerWorker implements Callable<String>{
  private ServerDetail server;
  private BufferedWriter writer;
  private HttpSolrServer thothIndex;
  private ObjectMapper mapper;
  private String fileName;

  private ModelHealth modelHealth;

  //TODO: To remove ASAP  - BEST-1377
  private StaticModelHealth userStaticModelHealth;
  private StaticModelHealth mobileStaticModelHealth;
  private StaticModelHealth drStaticModelHealth;
  private StaticModelHealth googleStaticModelHealth;
  // DR1 : search501
  private static final String DR1_HOSTNAME = "search501";
  // Google: search213
  private static final String GOOGLE_HOSTNAME = "search213";
  // User: search37
  private static final String USER_HOSTNAME = "search37";
  // Mobile: search39
  private static final String MOBILE_HOSTNAME = "search39";


  private static final int SLOW_FAST_QUERY_QTIME_THRESHOLD = 100;
  private String hostname;
  private String port;
  private String core;
  private String pool;

  public static <T> List<T> randomSample(List<T> items, int m){
    Random rnd = new Random();

    for(int i=0;i<m;i++){
      int pos = i + rnd.nextInt(items.size() - i);
      T tmp = items.get(pos);
      items.set(pos, items.get(i));
      items.set(i, tmp);
    }
    return items.subList(0, m);
  }

  public SamplerWorker(ServerDetail server, String samplingDirectory, ObjectMapper mapper, HttpSolrServer thothIndex, ModelHealth modelHealth,
                       StaticModelHealth userStaticModelHealth,StaticModelHealth drStaticModelHealth,StaticModelHealth mobileStaticModelHealth,StaticModelHealth googleStaticModelHealth ) throws IOException {
    this.server = server;
    this.mapper = mapper;
    DateFormat dateFormat = new SimpleDateFormat("yyyy_MM_dd");
    Date date = new Date();
    this.fileName =  samplingDirectory + dateFormat.format(date) + "_" + server.getName();
    writer = new BufferedWriter(new FileWriter(new File(fileName), true));
    this.hostname = server.getName();
    this.pool = server.getPool();
    this.core = server.getCore();
    this.port = server.getPort();
    this.thothIndex = thothIndex;
    this.modelHealth = modelHealth;

    this.userStaticModelHealth = userStaticModelHealth;
    this.drStaticModelHealth = drStaticModelHealth;
    this.mobileStaticModelHealth = mobileStaticModelHealth;
    this.googleStaticModelHealth = googleStaticModelHealth;

  }





  //TODO: To remove ASAP  - BEST-1377
  public void setSetStaticHealth(int qtime, boolean isDocumentIdentifiedAsSlow, boolean isSlowQueryPredictionValid, String hostname, StaticModelHealth staticModelHealth){
    staticModelHealth.incrementSampleCount();
      if (qtime > SLOW_FAST_QUERY_QTIME_THRESHOLD){
        if (isDocumentIdentifiedAsSlow){
          staticModelHealth.incrementTruePositive();
        } else {
          staticModelHealth.incrementFalseNegative();
        }
      } else {
        if (isDocumentIdentifiedAsSlow){
          staticModelHealth.incrementFalsePositive();
        } else {
          staticModelHealth.incrementTrueNegative();
        }
      }
      if (!isSlowQueryPredictionValid){
        staticModelHealth.incrementPredictionErrors();
      }
  }

  /**
   * Prepares the query used to get a sample of Thoth data
   * @return the solr query
   */
  private SolrQuery getSamplingSolrQuery(){
    // TODO: use same technique used in thoth core
    SolrQuery samplingSolrQuery = new SolrQuery("hostname_s:"+hostname
        +" AND port_i:"+port
        +" AND pool_s:"+pool
        +" AND coreName_s:"+core
        +" AND NOT exception_b:true AND NOT source_s:WatchingRequest" );
    samplingSolrQuery.setSort(new SolrQuery.SortClause("timestamp_dt", SolrQuery.ORDER.desc));
    samplingSolrQuery.setRows(2);  // Returning 100 docs
    return samplingSolrQuery;
  }

  private void updateHealthScores(SolrDocument doc){
    // Update the model health based on the accuracy of the current prediction
    modelHealth.computeScore(((Boolean) doc.getFieldValue("isSlowQueryPredictionValid_b")) == true ? 0:1);

    Integer qtime = (Integer) doc.getFieldValue("qtime_i");
    boolean isDocumentIdentifiedAsSlow = (Boolean) doc.getFieldValue("slowQuery_b") == true;
    boolean isSlowQueryPredictionValid = ((Boolean) doc.getFieldValue("isSlowQueryPredictionValid_b")) == true;


    //TODO: To remove ASAP  - BEST-1377

    if (((String)doc.getFieldValue("params_s")).contains("truliatest")){
      setSetStaticHealth(qtime,isDocumentIdentifiedAsSlow,isSlowQueryPredictionValid,hostname, drStaticModelHealth);


    }

    if (GOOGLE_HOSTNAME.equals(hostname)){
      setSetStaticHealth(qtime,isDocumentIdentifiedAsSlow,isSlowQueryPredictionValid,hostname, googleStaticModelHealth);
    } else if (MOBILE_HOSTNAME.equals(hostname)){
      setSetStaticHealth(qtime,isDocumentIdentifiedAsSlow,isSlowQueryPredictionValid,hostname, mobileStaticModelHealth);
    } else if (USER_HOSTNAME.equals(hostname)){
      setSetStaticHealth(qtime,isDocumentIdentifiedAsSlow,isSlowQueryPredictionValid,hostname, userStaticModelHealth);
    }
  }

  /**
   * Take care of closing the file writer and printing out the action
   * @throws IOException
   */
  private void closeFileWriter() throws IOException {
    writer.close();
    System.out.println(fileName + " closed.");
  }

  @Override
  public String call() throws Exception {
    SolrDocumentList sampleOfThothDocs = thothIndex.query(getSamplingSolrQuery()).getResults();

    if (sampleOfThothDocs.size() < 1){
      System.out.println("ERROR: hostname: " + hostname+" returned 0 results. Skipping sampling" );
      closeFileWriter();
      return "skipped";
    }

    List<SolrDocument> randomSample = randomSample(sampleOfThothDocs, 100);
    for (SolrDocument doc: randomSample){
      //TODO: refactor this
      updateHealthScores(doc);
      writer.write(Converter.thothDocToTsv(doc, mapper));
    }
    closeFileWriter();
    return "done";
  }

  public static void main(String[] args) throws IOException, SolrServerException {
//    String params = "q={!spatial circles=33.6942,-112.033,1}(asmtBuildingArea_i:[ 2000 TO * ] ) AND (latitude_f:[ 33.27584 TO 34.06266 ]) AND (longitude_f:[ -112.32953 TO -111.91321 ])&sort=lastSaleDate_s desc&ghl=9w0sn3wx9c7-9mzg4bbgesf&fq=propertyId_s:[* TO *]&fq=lastSaleDate_s:[\"2013-10-09\" TO *]&fq=lastSalePrice_i:[1 TO *]&version=2.2&start=15&rows=15&cachebust=NOVERSION&wt=json&slowpool=1";
    ObjectMapper om  = new ObjectMapper();
    SamplerWorker samplerWorker = new SamplerWorker(new ServerDetail("search501","bot","8050","active"),"", om, new HttpSolrServer("http://thoth:8983/solr/collection1"), null, null, null,null,null);
//    System.out.println(samplerWorker.extractDetailsFromParams(params));
    HttpSolrServer solrServer = new HttpSolrServer("http://thoth.sv2.trulia.com:8983/solr/");
//    solrServer.query(getSamplingSolrQuery());

    QueryResponse qr = solrServer.query(samplerWorker.getSamplingSolrQuery());
    SolrDocumentList solrDocumentList = qr.getResults();
    for (SolrDocument doc: solrDocumentList) {

      System.out.println(Converter.thothDocToTsv(doc, om));

    }

  }
}
