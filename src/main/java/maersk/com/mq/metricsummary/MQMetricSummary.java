package maersk.com.mq.metricsummary;

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
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import maersk.com.mq.metrics.mqmetrics.MQBase;

// https://www.oodlestechnologies.com/blogs/Simple-Steps-To-Read-JSON-file-and-save-it-to-database-in-spring-boot/

@Component
public class MQMetricSummary extends MQBase {
	
	private static final int DAY_ONE = 1;
	
	protected static final String lookupChlCounts = MQPREFIX + "cummulativeChannelCounts";
	
	@Value("${application.save.metrics.filename:nofile.json}")
    private String metricsFileName;

    private Logger log = Logger.getLogger(this.getClass());
    private Channels channels;
    
    //Channel maps
    private Map<MetricChannelDetails,AtomicLong>cummChannelCounts = new HashMap<MetricChannelDetails, AtomicLong>();
    private Map<String,AtomicLong>loadedCounts = new HashMap<String, AtomicLong>();

    private int timesCalled = 0;
    
	public MQMetricSummary() {
		if (this._debug) { log.info("Invoking MQMetricSummary"); }
	}

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

	// Load metrics from saved file
	private void LoadMetricsFromFile() throws IOException {

		if (this._debug) { log.info("Loading monthly metrics from file ... : " + this.metricsFileName); }
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
	
	
	// Create initial metrics
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
		
			UpdateCounts(channelName, channelType, qmName, clusterName, thisMonth);
			StoreCountsInMemeoryMap(channelName, thisMonth);
			
		}
	}
	
		
	// Store counts in memory from the initial load 
	private void StoreCountsInMemeoryMap(String channelName, long val) {

		AtomicLong c = loadedCounts.get(channelName);
		if (c == null) {
			loadedCounts.put(channelName, new AtomicLong(val));
		}		
	}
	
	// Create metrics from passed in params
	public void UpdateCounts(String channelName, String channelType, String qm, String clusterName,
			long count) {

		MetricChannelDetails mcd = new MetricChannelDetails();
		mcd.setChannelName(channelName);
		mcd.setChannelType(channelType);
		mcd.setClusterName(clusterName);
		mcd.setQueueManagerName(qm);
		
		meterRegistry.gauge(lookupChlCounts, 
				Tags.of("queueManagerName", qm,
						"channelType", channelType,
						"channelName", channelName,
						"cluster", clusterName)
				,count);

		/*
		AtomicLong c = FindValue(mcd);
		AtomicLong init = loadedCounts.get(channelName);
		if (init == null) {
			init = new AtomicLong(0);
		}
		
		if (c == null) {
			cummChannelCounts.put(mcd, Metrics.gauge(new StringBuilder()
					.append(MQPREFIX)
					.append("cummulativeChannelCounts").toString(), 
					Tags.of("queueManagerName", qm,
							"channelType", channelType,
							"channelName", channelName,
							"cluster", clusterName
							)
					, new AtomicLong(count)));
			
		} else {
			
			long c1 = count + init.get();
			c.set(c1);
			
		}
		*/
		
	}

	// Do we need to roll over this months metrics 
	public void DoWeNeedToRollOver() throws ParseException {

		Date today = new Date();
		Calendar cal = Calendar.getInstance();
		Calendar met = Calendar.getInstance();

		// Have we already rolled over ?
		String d = this.channels.getCurrentDate();
		Date metricDate = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS")
				.parse(d);
		met.setTime(metricDate);
		
		// Should be roll over 'this month' to 'last month' ?
		cal.setTime(today);
		if (this._debug) { 
			log.info("Today day of week: " + cal.get(Calendar.DAY_OF_MONTH));
			log.info(" File day of week: " + met.get(Calendar.DAY_OF_MONTH)); 
		}
		
		// Day of month already match, assume its been updated, so dont roll over again
		if (cal.get(Calendar.DAY_OF_MONTH) == met.get(Calendar.DAY_OF_MONTH)) {
			return;
		}
		// if its not the 1st day of the month, then dont roll over		
		if (cal.get(Calendar.DAY_OF_MONTH) == DAY_ONE) {
			RollValues();
		}
		
	}
	
	// Roll over this month to last month ... so we keep 2 sets
	private void RollValues() {

		this.channels.setCurrentDate(new SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS").format(new Date()));		
		for (Channel c : this.channels.getChannel()) {
			c.setLastmonth(c.getThismonth());
			c.setThismonth(0l);
		}
		
	}
	
	
	// Find the value to update
	//   currently only checking on channelName
	//
	private AtomicLong FindValue(MetricChannelDetails mcd) {
	
		Iterator<Entry<MetricChannelDetails, AtomicLong>> listChannels = this.cummChannelCounts.entrySet().iterator();
		while (listChannels.hasNext()) {

	        Map.Entry pair = (Map.Entry)listChannels.next();
	        MetricChannelDetails key = (MetricChannelDetails) pair.getKey();
			
	        if (key.getChannelName().equals(mcd.getChannelName())) {
	        	AtomicLong i = (AtomicLong) pair.getValue();
	        	return i;
	        }
		}
		return null;
		
	}
	
	public void resetMetric() {
		DeleteMetricEntry(lookupChlCounts);

	}

	public void SaveMetrics() {
		
		if (this._debug) { log.info("Saving metric summary to disk ..."); }
		
		String fileName = this.metricsFileName;
		Path path = Paths.get(fileName);
		
		Channels channels = new Channels();
		List<Channel> channelList = new ArrayList<Channel>();
		
		Iterator<Entry<MetricChannelDetails, AtomicLong>> listChannels = this.cummChannelCounts.entrySet().iterator();
		
		// Get the first entry, so we get the queue manager name
		Map.Entry first = (Map.Entry)listChannels.next();
        MetricChannelDetails firstkey = (MetricChannelDetails) first.getKey();
		channels.setQueueManagerName(firstkey.getQueueManagerName());
		channels.setCurrentDate(new SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS").format(new Date()));
				
		// loop through, then put to a file
		while (listChannels.hasNext()) {

	        Map.Entry pair = (Map.Entry)listChannels.next();
	        MetricChannelDetails key = (MetricChannelDetails) pair.getKey();
			AtomicLong i = (AtomicLong) pair.getValue();

			Channel channel = new Channel();
			channel.setName(key.getChannelName());
			channel.setChannelType(key.getChannelType());
			channel.setClusterName(key.getClusterName());
			channel.setLastmonth(0l);
			channel.setThismonth(i.get());
			channelList.add(channel);	        
			
		}
		channels.setChannel(channelList);
		
		
		try {
			
			if (this._debug) { log.info("Creating JSON file :" + fileName ); }
			ObjectMapper objectMapper = new ObjectMapper();
			objectMapper.writeValue(new File(fileName),channels);
			
		} catch (IOException e) {
			log.error("creating new JSON file ... : " + e.getMessage());
		}
	
		
	}
	
}

