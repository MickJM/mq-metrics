package maersk.com.mq.metricsummary;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;

// https://www.oodlestechnologies.com/blogs/Simple-Steps-To-Read-JSON-file-and-save-it-to-database-in-spring-boot/

@Component
public class MQMetricSummary {
	
	private static final String MQPREFIX = "mq:";
	
    private Logger log = Logger.getLogger(this.getClass());
    private Channels channels;
    
    //Channel maps
    private Map<MetricChannelDetails,AtomicLong>cummChannelCounts = new HashMap<MetricChannelDetails, AtomicLong>();

    private int timesCalled = 0;
    
	public MQMetricSummary() {
		log.info("Invoking MQMetricSummary");
	}

	public void LoadMetrics() {
		
		
		try {
			LoadMetricsFromFile();
			CreateNewMetricsFile();
			
		} catch (IOException e) {
			log.info("Error: " + e.getMessage());
		}
		
	}

	//
	private void CreateNewMetricsFile() {
	
		String fileName = "currentChannels_02.json";
		Path path = Paths.get(fileName);
		
		List<Channel> listChannel = new ArrayList<Channel>();
		Channel c1 = new Channel();
		c1.setName("TEST.CHANNEL.ONE");
		c1.setLastmonth(123l);
		c1.setThismonth(345l);
		listChannel.add(c1);
		
		Channels cs1 = new Channels();
		cs1.setQueueManagerName("TESTQM");
		cs1.setCurrentDate("TodaysDate");
		cs1.setChannel(listChannel);
		
		
	//	byte[] bFileData = fileData.getBytes();
		
	//	byte[] strToBytes = fileData.getBytes();
		try {
			
			log.info("Creating JSON test file");
			
	//		Files.write(path,bFileData);
			ObjectMapper objectMapper = new ObjectMapper();
			objectMapper.writeValue(new File(fileName),cs1);
			
		} catch (IOException e) {
			log.error("creating new JSON file ... : " + e.getMessage());
		}
		
		
	}
	
	private void LoadMetricsFromFile() throws IOException {

		byte[] mapData = Files.readAllBytes(Paths.get("currentChannels_03.json"));
		
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

		String qmName = this.channels.getQueueManagerName();
		String date = this.channels.getCurrentDate();
		
		for (Channel c : this.channels.getChannel()) {
			String channelName = c.getName();
			String channelType = c.getChannelType();
			String clusterName = c.getClusterName();
			long lastMonth = c.getLastmonth();
			long thisMonth = c.getThismonth();
		
			UpdateCounts(channelName, channelType, qmName, clusterName, thisMonth);
			/*
			AtomicLong c = cummChannelCounts.get(name);
			if (c == null) {
				cummChannelCounts.put(name, Metrics.gauge(new StringBuilder()
						.append(MQPREFIX)
						.append("cummulativeChannelCounts").toString(), 
						Tags.of("queueManagerName", "QMAP01",
								"channelType", "channel",
								"channelName", name,
								"cluster", "ClusterA"
								)
						, new AtomicLong(cs.getThismonth())));
			} else {
				c.set(cs.getThismonth());
			}
			*/
		}
		
	}
	
	
	/*
	public Channel GetChannelValue(String channelName) {
		
		for (Channel c : this.channels.getChannel()) {
			if (c.getName().equals(channelName)) {
				return c;
			}
		}
		return null;
	}
	*/
	
	// Create metrics 
	public void UpdateCounts(String channelName, String channelType, String qm, String clusterName,
			long count) {

		MetricChannelDetails mcd = new MetricChannelDetails();
		mcd.setChannelName(channelName);
		mcd.setChannelType(channelType);
		mcd.setClusterName(clusterName);
		mcd.setQueueManagerName(qm);
		
		AtomicLong c = FindValue(mcd);
		
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
			long c1 = c.get() + count;
			log.info("Counts: " + channelName + c1 + " - " + count);
			c.set(c1);
		}
		
		//ResetMetrics(0L);
		
	}

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
	
	public void SaveMetrics() {
		log.info("Saving metric summary to disk ...");		
		
		String fileName = "currentChannels_03.json";
		Path path = Paths.get(fileName);

		
		Channels channels = new Channels();
		List<Channel> channelList = new ArrayList<Channel>();
		
		Boolean firstEntry = true;
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
			
			log.info("Creating JSON file :" + fileName );
			ObjectMapper objectMapper = new ObjectMapper();
			objectMapper.writeValue(new File(fileName),channels);
			
		} catch (IOException e) {
			log.error("creating new JSON file ... : " + e.getMessage());
		}
	
		
	}
	
	private void ResetMetrics(long val) {
		
		Iterator<Entry<MetricChannelDetails, AtomicLong>> listChannels = this.cummChannelCounts.entrySet().iterator();
		while (listChannels.hasNext()) {
	        Map.Entry pair = (Map.Entry)listChannels.next();
	        MetricChannelDetails key = (MetricChannelDetails) pair.getKey();
	        try {
				AtomicLong i = (AtomicLong) pair.getValue();
				log.info("key : " + key.toString());
			
				//	if (i != null) {
			//		i.set(val);
			//	}
	        } catch (Exception e) {
	        	log.error("Unable to set channel status ");
	        }
		}

		
	}
}


/*
* {"qm":"QMAP01",
*  "currentDate":"2019-07-18:15:29:00",
*  "channel":[
*     {"name":"channelOne",
*      "0719":1234,
*      "0819":5432},
*     {"name":"channelTwo",
*      "0719":0,
*      "0819":123}
*  ]
* }
*/