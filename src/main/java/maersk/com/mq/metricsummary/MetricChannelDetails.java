package maersk.com.mq.metricsummary;

/*
 * Copyright 2019
 * Mick Moriarty - Maersk
 *
 * Metric Channel details
 * 
 */

public class MetricChannelDetails {

	private String ChannelName;
	private String QueueManagerName;
	private String ChannelType;
	private String ClusterName;
	
	public String getChannelName() {
		return ChannelName;
	}
	public void setChannelName(String channelName) {
		ChannelName = channelName;
	}
	public String getQueueManagerName() {
		return QueueManagerName;
	}
	public void setQueueManagerName(String queueManagerName) {
		QueueManagerName = queueManagerName;
	}
	public String getChannelType() {
		return ChannelType;
	}
	public void setChannelType(String channelType) {
		ChannelType = channelType;
	}
	public String getClusterName() {
		return ClusterName;
	}
	public void setClusterName(String clusterName) {
		ClusterName = clusterName;
	}
	
	@Override
	public String toString() {
		return this.ChannelName + "_" + this.ChannelType + "_" + this.QueueManagerName + "_" + this.ClusterName;
	}
	
}
