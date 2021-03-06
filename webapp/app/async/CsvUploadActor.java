package async;


import inference.InferenceService;

import java.io.File;
import java.io.FileReader;
import java.text.ParseException;

import org.opengis.referencing.operation.TransformException;
import org.openplans.tools.tracking.impl.Observation;
import org.openplans.tools.tracking.impl.TimeOrderException;

import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import au.com.bytecode.opencsv.CSVReader;
import controllers.Api;

public class CsvUploadActor extends UntypedActor {
  LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  public static void traceLocation(String csvFileName, String vehicleId,
		    String timestamp, String latStr, String lonStr, String velocity,
		    String heading, String accuracy) throws NumberFormatException, ParseException, 
		    TransformException, TimeOrderException {

		    final Observation location = Observation.createObservation(
		        vehicleId, timestamp, latStr, lonStr, velocity, heading, accuracy);
		    
		    // TODO set flags for result record handling
		    InferenceService.processRecord(location);
		    

		  }
  
  @Override
  public void onReceive(Object csvFile) throws Exception {
    if (csvFile instanceof File) {

      final CSVReader gps_reader = new CSVReader(
          new FileReader((File) csvFile), ';');
      String[] line;
      gps_reader.readNext();
      log.info("processing gps data");

      /*
       * FIXME TODO reset only data relevant to a re-run trace.
       */
      InferenceService.clearInferenceData();
      Observation.clearRecordData();

      while ((line = gps_reader.readNext()) != null) {

        try {
          traceLocation(((File) csvFile).getName(), line[3], line[1],
              line[5], line[7], line[10], null, null);
          log.info("processed time: " + line[1]); 
        } catch (final TimeOrderException ex) {
          log.info("bad time order: " + line); 
        } catch (final Exception e) {
          log.error("bad csv line: " + line.toString() + "\n Exception:" + e.getMessage()); // bad line
          e.printStackTrace();
          break;
        } 
        log.info("finished processing"); 

      }
    }

  }
}