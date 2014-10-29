package com.trulia.thoth;

import com.trulia.thoth.pojo.QueryPojo;
import com.trulia.thoth.pojo.QuerySamplingDetails;
import com.trulia.thoth.util.Utils;
import hex.gbm.GBM;
import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import water.Boot;
import water.H2O;
import water.Key;
import water.UKV;
import water.fvec.Frame;
import water.fvec.NFSFileVec;
import water.fvec.ParseDataset2;
import water.fvec.Vec;
import water.serial.Model2FileBinarySerializer;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.ArrayList;
import java.util.Random;

/**
 * User: dbraga - Date: 10/1/14
 */

@Component
public class Model {
  private static final String H2O_CLOUD_NAME = "predictorCloud";
  static final int slowQueryThreshold = 100;
  private static final Logger LOG = Logger.getLogger(Model.class);
  static ObjectMapper mapper = new ObjectMapper();
  private Random random = new Random();
  private String version;
  @Value("${thoth.merging.dir}")
  private String mergeDirectory;
  @Value("${train.dataset.location}")
  private String exportedTrainDataset;
  @Value("${test.dataset.location}")
  private String exportedTestDataset;
  @Value("${model.location}")
  private String modelLocation;



  private static Double[] createInstance(QueryPojo queryPojo) throws IOException {
    ArrayList<Double> instance = new ArrayList<Double>();
    int pos = 0;
    QuerySamplingDetails querySamplingDetails = mapper.readValue(queryPojo.getParams(), QuerySamplingDetails.class);
    QuerySamplingDetails.Details details = querySamplingDetails.getDetails();

    if(queryPojo.getQtime() == null) {
      // Handle this differently during prediction
      return null;
    }
    else {
      int qtime = Integer.parseInt(queryPojo.getQtime());
      // --------- for classification --------------
      if(qtime < slowQueryThreshold) {
        instance.add(0.0);
      }
      else {
        instance.add(1.0);
      }
    }

    int start = details.getStart();
    instance.add((double) start);

    String query = details.getQuery();
    if(query != null) {
      query = query.replace("(", "");
      query = query.replace(")", "");
      query = query.replace("\"", "");
      query = query.replace("+", "");
      String[] queryFields = query.split("AND|OR");
      // Number of fields as a separate field
      instance.add((double) queryFields.length);
    }

//    if(queryPojo.getHits() == null) {
//      // Log missing hits
//      // How critical is this? Can this ever be missing
//    }
//    else {
//      int hits = Integer.parseInt(queryPojo.getHits());
//      instance.add((double) hits);
//    }
    addBitmaskBooleanFields(instance, queryPojo.getBitmask());
    return instance.toArray(new Double[instance.size()]);
  }

  private static void addBitmaskBooleanFields(ArrayList<Double> instance, String bitmask) {
    if(bitmask.length() != 7) {
      LOG.error("Invalid bitmask: " + bitmask);
      return;
    }

    instance.add(Double.parseDouble(String.valueOf(bitmask.charAt(0))));
    instance.add(Double.parseDouble(String.valueOf(bitmask.charAt(1))));
    instance.add(Double.parseDouble(String.valueOf(bitmask.charAt(2))));
    instance.add(Double.parseDouble(String.valueOf(bitmask.charAt(3))));
    instance.add(Double.parseDouble(String.valueOf(bitmask.charAt(4))));
    instance.add(Double.parseDouble(String.valueOf(bitmask.charAt(5))));
    instance.add(Double.parseDouble(String.valueOf(bitmask.charAt(6))));
  }

  private static QueryPojo getQueryPojoFromSplitLine(String[] fields){
    QueryPojo queryPojo = new QueryPojo();
    queryPojo.setParams(fields[3]);
    if (!fields[4].isEmpty()) queryPojo.setQtime(fields[4]);
    if (!fields[5].isEmpty()) queryPojo.setHits(fields[5]);
    queryPojo.setBitmask(fields[6]);
    return queryPojo;
  }

  @PostConstruct
  public void init() {
    // Trying to fetch the version from file
    try {
      FileReader file = new FileReader("version");
      BufferedReader br = new BufferedReader(file);
      version = br.readLine();
    } catch (FileNotFoundException e) {
      // No version yet
      version = "-1";
    }
    catch (IOException e) {
      version = "-1";
    }
  }

  /**
   * Retrieve model version
   * @return String representation of the version
   */
  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  /**
   * Set new version both on disk and in memory
   */
  private void setNewVersion(String version){
    // Write new version to disk
    File f = new File("version");
    PrintWriter pw = null;
    try {
      pw = new PrintWriter(f);
      pw.write(version);
      pw.close();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
    // Set new version in memory
    this.version = version;
  }


  /**
   * Create a newly version using time since epoch
   * @return new version
   */
  private String generateNewVersion(){
    // Fetch new version
    return String.valueOf(System.currentTimeMillis());
  }


  /**
   * Train the model and return the newly model version
   * @return
   */
  public String trainModel() throws Exception {
    LOG.info("Training new model ...");
    String tempVersion = generateNewVersion();
    trainAndStoreModel(tempVersion);
    setNewVersion(tempVersion);
    return version;
  }

  /**
   * Retrieve the model
   * @return model
   */
  public Object getModel(){
    return null;
  }

  /**
   * Generate datasets
   * @throws IOException
   */
  public void generateDataSet() throws IOException {
    LOG.info("Generating dataset ...");
    // Get file that contains Thoth sample data
    BufferedReader br = new BufferedReader(new FileReader(Utils.getThothSampledFileName(mergeDirectory)));
    // Training and Test datasets
    ArrayList<Double[]> train = new ArrayList<Double[]>();
    ArrayList<Double[]> test = new ArrayList<Double[]>();

    String line;
    while ((line=br.readLine()) != null) {
      String[] splitLine = line.split("\t");
      if (splitLine.length != 7) continue; //TODO: too specific, need to make it generic
      Double[] instance = createInstance(getQueryPojoFromSplitLine(splitLine));
      if(instance == null) continue;

      // Separate into training and test
      int next = random.nextInt(100);
      if (next >= 70) {
        test.add(instance);
      }
      else {
        train.add(instance);
      }
    }

    // Export train and test datasets
    exportDataset(train, exportedTrainDataset);
    exportDataset(test, exportedTestDataset);
    LOG.info("Training set size: " + train.size());
    LOG.info("Test set size: " + test.size());

  }

  /**
   * Exports dataset to file
   * @param dataset ArrayList of double arrays
   * @param path of the file that needs to be stored
   * @throws IOException
   */
  private void exportDataset(ArrayList<Double[]> dataset, String path) throws IOException {
    if (dataset == null) {
      LOG.warn("Empty dataset. Nothing to export. Skipping ...");
      return;
    }

    System.out.println("Trained set: " + exportedTrainDataset);

    BufferedWriter bw = new BufferedWriter(new FileWriter(path));
    for (Double[] example: dataset) {
      if (example.length != 10) { //TODO: too specific, need to make it generic
        // Perform this check?
      }
      StringBuffer sb = new StringBuffer();
      for(Double value: example) {
        sb.append(value + "\t");
      }
      bw.write(sb.toString().trim());
      bw.newLine();
    }
    bw.flush();
    bw.close();
  }

  /**
   * Boot the H2o cloud, train a new model and store it to disk
   * @param modelVersion unique version for the new model
   * @throws Exception
   */
  public void trainAndStoreModel(String modelVersion) throws Exception {
        System.out.println("Initialization of the H2oCloud ...");
        Boot.main(Model.class, new String[]{exportedTrainDataset, exportedTestDataset, modelLocation, modelVersion});
  }

  /**
   * Method helper to spawn the H2o Cloud
   * @param args
   * @throws IOException
   */
  public static void main(String[] args) throws IOException {
    H2O.main(new String[]{"-name", H2O_CLOUD_NAME, "-md5skip"});
    H2O.waitForCloudSize(1);
    System.out.println("H2oCloud("+H2O_CLOUD_NAME+") initialized.");

    File trainedDataSetFile = new File(args[0]);
    Key fkey1 = NFSFileVec.make(trainedDataSetFile);
    Key dest1 = Key.make("thoth-train.hex");

    //TODO: do we still need the test dataset?
    //TODO: bring back old code
    File file2 = new File(args[1]);
    Key fkey2 = NFSFileVec.make(file2);
    Key dest2 = Key.make("thoth-test.hex");
    Frame ftest = ParseDataset2.parse(dest2, new Key[]{fkey2});


    GBM gbm = new GBM();
    gbm.source = ParseDataset2.parse(dest1, new Key[]{fkey1});
    gbm.response = new PrepData() { @Override
                                    Vec prep(Frame fr) { return fr.vecs()[0]; } }.prep(gbm.source);
    gbm.ntrees = 1000;
    //    gbm.max_depth = 3;
    gbm.balance_classes = true;
    gbm.learn_rate = 0.1f;
    gbm.min_rows = 10;
    gbm.nbins = 20;
    gbm.cols =  new int[] {1,2,3,4,5,6,7,8,9};

    gbm.invoke();
    GBM.GBMModel model = UKV.get(gbm.dest());

    // Model serialization
    File modelFile = new File(args[2] + "/gbm_model_v" + args[3]);
    new Model2FileBinarySerializer().save(model, modelFile);
  }

  private abstract static class PrepData { abstract Vec prep(Frame fr); }

  public String getModelLocation() {
    return modelLocation;
  }
}
