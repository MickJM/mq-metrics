package maersk.com.mq.metricsummary;

/*
 * Copyright 2019
 * Mick Moriarty - Maersk
 *
 * Channel objects for saving metrics
 * 
 */

import java.util.List;

public class Channels {

	private String queueManagerName;		// Name of the queue manager
	private String currentDate;				// Date
	private List<Channel>channel;			// List of channels
	
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
