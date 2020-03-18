package maersk.com.mq.metricsummary;

/*
 * Copyright 2019
 * Mick Moriarty - Maersk
 *
 * Metrics Summary
 * 
 */

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import maersk.com.mq.metrics.mqmetrics.MQBase;
import maersk.com.mq.metrics.mqmetrics.MQBase.LEVEL;

@RestController
@Component
public class MQMetricSummary extends MQBase {
	
	private static final int DAY_ONE = 1;
	
	protected static final String lookupChlCounts = MQPREFIX + "cummulativeChannelCounts";
	
	@Value("${application.save.metrics.filename:nofile.json}")
    private String metricsFileName;

    private Logger log = Logger.getLogger(this.getClass());
    private Channels channels;
    
    //Channel maps MetricChannelDetails
    private Map<String,MetricChannelDetails>cummChannelCounts = new HashMap<String, MetricChannelDetails>();
    private Map<String,AtomicLong>loadedCounts = new HashMap<String, AtomicLong>();

    private int timesCalled = 0;
    
    @RequestMapping(value = "/test", method = RequestMethod.GET,produces="text/plain")
    //public Iterator<Entry<String, MetricChannelDetails>> HelloWorld() {
    public List<String> HelloWorld() {
    	
   	 	//HashMap<String, Long> res = new HashMap<String, Long>();           
   	 	List<String> res = new ArrayList();
   	 	
		Iterator<Entry<String, MetricChannelDetails>> listChannels = this.cummChannelCounts.entrySet().iterator();
						
		// loop through, then put to a file
		boolean setHeader = true;
		
		/*
		while (listChannels.hasNext()) {

	        Map.Entry pair = (Map.Entry)listChannels.next();
	        MetricChannelDetails key = (MetricChannelDetails) pair.getValue();
		//	AtomicLong i = (AtomicLong) pair.getValue();

			String v = Long.toString(key.getCount());
			//res.put("mq:summary{queueManagerName=" + key.getQueueManagerName() + ",channelName=" + key.getChannelName() + ",} ", key.getCount());
			String x = "mq:summary{queueManagerName=\"" + key.getQueueManagerName() + "\"}, " + key.getInitialValue();
			res.add(x);
		}
		*/
		
		
   	 	
    	String qmName = this.channels.getQueueManagerName();
		String date = this.channels.getCurrentDate();
		
		for (Channel c : this.channels.getChannel()) {
			String channelName = c.getName();
			String channelType = c.getChannelType();
			String clusterName = c.getClusterName();
			long lastMonth = c.getLastmonth();
			long thisMonth = c.getThismonth();
			String v = Long.toString(thisMonth);
			res.add("mq:summary{queueManagerName=" + qmName + ",channelName=" + channelName + ",} 0");
			
		}
    	
		
      //   res.put("data", "hello world");
      //   res.put("errorCode", "0");
         return res;
         //return listChannels;
    }
    
	public MQMetricSummary() {
		if (getDebugLevel() == LEVEL.TRACE) { log.info("Invoking MQMetricSummary"); }
	}

	/*
	 * Load any metrics from the saved file 
	 */
	public void LoadMetrics() {
		
		try {
			LoadMetricsFromFile();
			
		} catch (NoSuchFileException e) {
			log.info("File : " + this.metricsFileName + " does not exist, creating it.");
		
		} catch (IOException e) {
			log.error("IOException: " + e.getMessage());
		
		} catch (Exception e) {
			log.error("Exception: " + e.getMessage());
		}
		
	}

	/*
	 *  Load metrics from saved file
	 */
	private void LoadMetricsFromFile() throws IOException {

		if (getDebugLevel() == LEVEL.TRACE) { log.trace("Loading monthly metrics from file ... : " + this.metricsFileName); }
		log.info("file: " + Paths.get(this.metricsFileName));
		
		Path pathToFile = Paths.get(this.metricsFileName);
		File fileName = new File(this.metricsFileName);
		if (!fileName.exists()) {
			this.channels = new Channels();
			this.channels.setQueueManagerName("");
			this.channels.setCurrentDate(new SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS").format(new Date()));
		}
		
		byte[] mapData = Files.readAllBytes(Paths.get(this.metricsFileName));
		
		try {
			ObjectMapper objectMapper = new ObjectMapper();
			this.channels = objectMapper.readValue(mapData, Channels.class);		
			CreateMetrics();

		} catch (Exception e) {
			log.error("creating initial cummulative metrics");
		}

	}
	
	
	/*
	 *  Create initial metrics
	 */
	private void CreateMetrics() {

		resetMetric();
		
		String qmName = this.channels.getQueueManagerName();
		String date = this.channels.getCurrentDate();
		
		for (Channel c : this.channels.getChannel()) {
			String channelName = c.getName();
			String channelType = c.getChannelType();
			String clusterName = c.getClusterName();
			long lastMonth = c.getLastmonth();
			long thisMonth = c.getThismonth();
		
			UpdateCounts(channelName, channelType, qmName, clusterName, thisMonth, true);
			StoreCountsInMemeoryMap(channelName, thisMonth);
			
		}
	}
	
		
	/*
	 *  Store counts in memory from the initial load 
	 */
	private void StoreCountsInMemeoryMap(String channelName, long val) {

		AtomicLong c = loadedCounts.get(channelName);
		if (c == null) {
			loadedCounts.put(channelName, new AtomicLong(val));
		}		
	}
	
	/*
	 *  Create metrics from passed in params
	 */
	public void UpdateCounts(String channelName, String channelType, String qm, String clusterName,
			long count, boolean initialcall) {

		MetricChannelDetails mcd = new MetricChannelDetails();
		mcd.setChannelName(channelName);
		mcd.setChannelType(channelType);
		mcd.setClusterName(clusterName);
		mcd.setQueueManagerName(qm);
		if (initialcall) {
			mcd.setInitialValue(count);
		} else {
			mcd.setCount(count);
		}
		
		MetricChannelDetails val = cummChannelCounts.get(mcd.toString());
		if (val == null) {
			cummChannelCounts.put(mcd.toString(), mcd);
		} else {
			val.incCount(count, val.getInitialValue());
		}

		/*
		cummChannelCounts.put(mcd.toString(), 
				meterRegistry.gauge(lookupChlCounts, 
						Tags.of("queueManagerName", qm,
								"channelType", channelType,
								"channelName", channelName,
								"cluster", clusterName)
						,new AtomicLong(count)));
	} else {
		val.set(count);
	}
		
		
		
		meterRegistry.gauge(lookupChlCounts, 
				Tags.of("queueManagerName", qm,
						"channelType", channelType,
						"channelName", channelName,
						"cluster", clusterName)
				,count);

		*/
		
	}

	/*
	 *  Do we need to roll over this months metrics 
	 */
	public void DoWeNeedToRollOver() throws ParseException {

		Date today = new Date();
		Calendar cal = Calendar.getInstance();
		Calendar met = Calendar.getInstance();

		// Have we already rolled over ?
		String d = this.channels.getCurrentDate();
		Date metricDate = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS")
				.parse(d);
		met.setTime(metricDate);
		
		/*
		 *  Should we roll over 'this month' to 'last month' ?
		 */
		cal.setTime(today);
		if (getDebugLevel() == LEVEL.TRACE) { 
			log.trace("Today day of week: " + cal.get(Calendar.DAY_OF_MONTH));
			log.trace(" File day of week: " + met.get(Calendar.DAY_OF_MONTH)); 
		}
		
		/*
		 *  Day of month already matched, assume its been updated, so dont roll over again
		 */
		if (cal.get(Calendar.DAY_OF_MONTH) == met.get(Calendar.DAY_OF_MONTH)) {
			return;
		}
		
		/*
		 * Roll over on the 1st of the month
		 */
		if (cal.get(Calendar.DAY_OF_MONTH) == DAY_ONE) {
			RollValues();
		}
		
	}
	
	/*
	 *  Roll over this month to last month ... so we keep 2 sets
	 */
	private void RollValues() {

		this.channels.setCurrentDate(new SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS").format(new Date()));		
		for (Channel c : this.channels.getChannel()) {
			c.setLastmonth(c.getThismonth());
			c.setThismonth(0l);
		}
		
	}
	
		
	public void resetMetric() {
		deleteMetricEntry(lookupChlCounts);

	}

	/*
	 * Save the metrics to a file 
	 */
	public void SaveMetrics() {
		
		if (getDebugLevel() == LEVEL.TRACE) { log.info("Saving metric summary to disk ..."); }
		
		String fileName = this.metricsFileName;
		Path path = Paths.get(fileName);
		
		Channels channels = new Channels();
		List<Channel> channelList = new ArrayList<Channel>();
		
		//MetricChannelDetails
		//Iterator<Entry<MetricChannelDetails, AtomicLong>> listChannels = this.cummChannelCounts.entrySet().iterator();
		Iterator<Entry<String, MetricChannelDetails>> listChannels = this.cummChannelCounts.entrySet().iterator();
		
		// Get the first entry, so we get the queue manager name
		////Map.Entry first = (Map.Entry)listChannels.next();
        //MetricChannelDetails firstkey = (MetricChannelDetails) first.getKey();
		////MetricChannelDetails firstkey = (MetricChannelDetails) first.getValue();
		
		////channels.setQueueManagerName(firstkey.getQueueManagerName());
		////channels.setCurrentDate(new SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS").format(new Date()));
				
		// loop through, then put to a file
		boolean setHeader = true;
		
		while (listChannels.hasNext()) {

	        Map.Entry pair = (Map.Entry)listChannels.next();
	        MetricChannelDetails key = (MetricChannelDetails) pair.getValue();
		//	AtomicLong i = (AtomicLong) pair.getValue();

			if (setHeader) {
				channels.setQueueManagerName(key.getQueueManagerName());
				channels.setCurrentDate(new SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS").format(new Date()));
				setHeader = false;
			}
	        
			Channel channel = new Channel();
			channel.setName(key.getChannelName());
			channel.setChannelType(key.getChannelType());
			channel.setClusterName(key.getClusterName());
			channel.setLastmonth(0l);
			channel.setThismonth(key.getCount());
			//channel.setThismonth(i.get());
			channelList.add(channel);	        
			
		}
		
		channels.setChannel(channelList);
			
		try {
			
			if (getDebugLevel() == LEVEL.TRACE) { log.trace("Creating JSON file :" + fileName ); }
			ObjectMapper objectMapper = new ObjectMapper();
			objectMapper.writeValue(new File(fileName),channels);
			
		} catch (IOException e) {
			log.error("Error creating new JSON file ... : " + e.getMessage());
		}
	
		
	}
	
}


