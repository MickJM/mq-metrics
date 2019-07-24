package maersk.com.mq.metricsummary;

import java.util.List;

//https://www.journaldev.com/2324/jackson-json-java-parser-api-example-tutorial

/*
 * {"queueManagerName":"QMAP01",
 *  "currentDate":"2019-07-18:15:29:00",
 *  "channel":[
 *     {"name":"channelOne",
 *      "lastMonth":1234.00,
 *      "thisMonth":5432.00},
 *     {"name":"channelTwo",
 *      "lastMonth":0.00,
 *      "thismonth":123.00}
 *  ]
 * }
 * 
 */
public class Channels {

	private String queueManagerName;
	private String currentDate;
	private List<Channel>channel;
	
	public String getQueueManagerName() {
		return queueManagerName;
	}
	public void setQueueManagerName(String queueManagerName) {
		this.queueManagerName = queueManagerName;
	}
	public String getCurrentDate() {
		return currentDate;
	}
	public void setCurrentDate(String currentDate) {
		this.currentDate = currentDate;
	}
	public List<Channel> getChannel() {
		return channel;
	}
	public void setChannel(List<Channel> channel) {
		this.channel = channel;
	}
	
	
	
	
}
