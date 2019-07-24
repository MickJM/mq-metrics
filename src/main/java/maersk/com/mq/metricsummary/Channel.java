package maersk.com.mq.metricsummary;

import java.util.concurrent.atomic.AtomicLong;

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
public class Channel {

	private String name;
	private String channelType;
	private String clusterName;
	private long lastmonth;
	private long thismonth;
	
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getChannelType() {
		return channelType;
	}
	public void setChannelType(String channelType) {
		this.channelType = channelType;
	}
	
	public String getClusterName() {
		return clusterName;
	}
	public void setClusterName(String clusterName) {
		this.clusterName = clusterName;
	}
	
	public long getLastmonth() {
		return lastmonth;
	}
	public void setLastmonth(long lastmonth) {
		this.lastmonth = lastmonth;
	}
	public long getThismonth() {
		return thismonth;
	}
	public void setThismonth(long i) {
		this.thismonth = i;
	}
	
	
	
	
}
