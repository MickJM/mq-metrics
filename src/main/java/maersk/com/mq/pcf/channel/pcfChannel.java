package maersk.com.mq.pcf.channel;

/*
 * Copyright 2019
 * Mick Moriarty - Maersk
 *
 * Get channel details
 * 
 */

import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

import io.micrometer.core.instrument.Tags;
import maersk.com.mq.metrics.mqmetrics.MQBase;
import maersk.com.mq.metrics.mqmetrics.MQPCFConstants;
import maersk.com.mq.metricsummary.MQMetricSummary;

@Component
public class pcfChannel extends MQBase {

    private Logger log = Logger.getLogger(this.getClass());

	private static final int SAVEMETRICS = 0;
	protected static final String lookupChannel = MQPREFIX + "channelStatus";
	protected static final String lookupMsgRec = MQPREFIX + "messagesReceived";
	protected static final String lookupBytesRec = MQPREFIX + "bytesReceived";
	protected static final String lookupBytesSent = MQPREFIX + "bytesSent";
	
    private Map<String,AtomicInteger>channelMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicLong>msgRecMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>msgBytesRecMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>msgBytesSentMap = new HashMap<String,AtomicLong>();

	private String queueManager;

	@Value("${application.save.summary.stats:3}")
    private int saveSummaryStats;
    private boolean summaryRequired;

	@Value("${ibm.mq.objects.channels.exclude}")
    private String[] excludeChannels;
	@Value("${ibm.mq.objects.channels.include}")
    private String[] includeChannels;
	
    private PCFMessageAgent messageAgent;
    public void setMessageAgent(PCFMessageAgent agent) {
    	this.messageAgent = agent;
    	this.queueManager = this.messageAgent.getQManagerName().trim();    	
    
    }
	
    private MQMetricSummary metricSummary;    
    private int metricSummaryCount = 0;

	
    /*
     * Constructor ...
     *    If metricsSummary data is being created, re-load the metrics
     */
    public pcfChannel(MQMetricSummary metricSummary) {

		if (!(getDebugLevel() == LEVEL.NONE)) { log.info("pcfChannel: Object created"); }
		if (metricSummary != null) {
			if (getDebugLevel() == LEVEL.TRACE) { log.trace("MetricSummary exists object has been created ..."); }
	    	this.metricSummary = metricSummary;
		} else {
			if (getDebugLevel() == LEVEL.TRACE) { log.trace("MetricSummary does not exist ...."); }
		}

		
    }

    /*
     * When the class is fully created ...
     */
    @PostConstruct
    private void PostMethod() {
    	log.info("Excluding channels ;");
    	for (String s : this.excludeChannels) {
    		log.info(s);
    	}
    }
    
    /*
     * Load properties for metrics summary if needed
     */
    public void loadProperties(boolean summaryRequired) {
    	log.debug("Channel loadProperties ....");    	
		this.summaryRequired = summaryRequired;
		
    	if (this.summaryRequired) {			
			if (this.metricSummary != null) {
		    	this.metricSummary.LoadMetrics();	
		    	
			} else {
				if (getDebugLevel() == LEVEL.TRACE
						|| (getDebugLevel() == LEVEL.DEBUG) ) { log.trace("this.metricsSummary object has not been created !!"); }
				
			}
    	}
    	
    }
    
    /*
     * Get the channel metrics
     */
	public void updateChannelMetrics() throws MQException, IOException, PCFException, MQDataException, ParseException {
		
		if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: inquire on channel request"); }
		
		/*
		 * Clear the metrics every 'x' iteration
		 */
		this.clearMetrics ++;
		if (this.clearMetrics % CONST_CLEARMETRICS == 0) {
			this.clearMetrics = 0;
			if (getDebugLevel() == LEVEL.TRACE) {
				log.debug("Clearing channel metrics");
			}
			resetMetrics();
		}

		// Enquire on all channels
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL);
		pcfRequest.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*");
		int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
		pcfRequest.addParameter(MQConstants.MQIACF_CHANNEL_ATTRS, pcfParmAttrs);
		
		PCFMessage[] pcfResponse = null;
		try {
			pcfResponse = this.messageAgent.send(pcfRequest);
		
		} catch (Exception e) {
			if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: no response returned - " + e.getMessage()); }
			
		}		
				
		if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: inquire on channel response"); }
		int[] pcfStatAttrs = { MQConstants.MQIACF_ALL };

		// for each return response, loop
		try {
			for (PCFMessage pcfMsg : pcfResponse) {
				String channelName = pcfMsg.getStringParameterValue(MQConstants.MQCACH_CHANNEL_NAME).trim(); 
				if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: " + channelName); }
				
				int chlType = pcfMsg.getIntParameterValue(MQConstants.MQIACH_CHANNEL_TYPE);	
				String channelType = getChannelType(chlType);
				
				String channelCluster = "";
				if ((chlType == MQConstants.MQCHT_CLUSRCVR) ||
						(chlType == MQConstants.MQCHT_CLUSSDR)) {
					channelCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();
				}
				
				// Correct channel ?
				if (checkChannelNames(channelName.trim())) {
					if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: inquire channel status " + channelName); }
					PCFMessage pcfReq = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS);
					pcfReq.addParameter(MQConstants.MQCACH_CHANNEL_NAME, channelName);
					pcfReq.addParameter(MQConstants.MQIACH_CHANNEL_INSTANCE_TYPE, MQConstants.MQOT_CURRENT_CHANNEL);				
					pcfReq.addParameter(MQConstants.MQIACH_CHANNEL_INSTANCE_ATTRS, pcfStatAttrs);
						
					// loop through each response
			        PCFMessage[] pcfResp = null;
					try {
						pcfResp = this.messageAgent.send(pcfReq);
						if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: inquire channel status response "); }
						PCFMessage pcfStatus = pcfResp[MQPCFConstants.BASE];
			
						/*
						 * Channel status
						 */
						int channelStatus = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_CHANNEL_STATUS);
						AtomicInteger channels = channelMap.get(lookupChannel + "_" + channelName );
						if (channels == null) {
							channelMap.put(lookupChannel + "_" + channelName, meterRegistry.gauge(lookupChannel, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicInteger(channelStatus))
									);
						} else {
							channels.set(channelStatus);
						}
						
					} catch (PCFException pcfe) {
						if (pcfe.reasonCode == MQConstants.MQRCCF_CHL_STATUS_NOT_FOUND) {
							if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: inquire channel status NOT FOUND"); }
							AtomicInteger channels = channelMap.get(lookupChannel + "_" + channelName);
							if (channels == null) {
								channelMap.put(lookupChannel + "_" + channelName, meterRegistry.gauge(lookupChannel, 
										Tags.of("queueManagerName", this.queueManager,
												"channelType", channelType,
												"channelName", channelName,
												"cluster", channelCluster
												),
										new AtomicInteger(MQConstants.MQCHS_INACTIVE))
										);
							} else {
								channels.set(MQConstants.MQCHS_INACTIVE);
							}
							
						}
						
					} catch (Exception e) {
						if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: inquire channel status exception: " + e.getMessage()); }
						AtomicInteger channels = channelMap.get(lookupChannel + "_" + channelName);
						if (channels == null) {
							channelMap.put(lookupChannel + "_" + channelName, meterRegistry.gauge(lookupChannel, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicInteger(MQConstants.MQCHS_INACTIVE))
									);
						} else {
							channels.set(MQConstants.MQCHS_INACTIVE);
						}
						
					}
	
					long msgsOverChannels = 0l;
					int bytesReceviedOverChannels = MQPCFConstants.BASE;
					int bytesSentOverChannels = MQPCFConstants.BASE;
					if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: inquire messages over channels"); }
						
					/*
					 * Messages received
					 */
					try {
						
						// Count the messages over the number of threads on each channel
						for (PCFMessage pcfM : pcfResp) {
							long msgs = pcfM.getIntParameterValue(MQConstants.MQIACH_MSGS);		
							msgsOverChannels += msgs;
						}
						if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: channel count: " + msgsOverChannels); }
						AtomicLong msgRec = msgRecMap.get(lookupMsgRec + "_" + channelName);
						if (msgRec == null) {
							msgRecMap.put(lookupMsgRec + "_" + channelName, meterRegistry.gauge(lookupMsgRec, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(msgsOverChannels))
									);
						} else {
							msgRec.set(msgsOverChannels);
						}
						
						if (this.metricSummary != null) {
							this.metricSummary.UpdateCounts(channelName
									, channelType
									, this.queueManager
									, channelCluster
									, msgsOverChannels
									, false);
						}
						
					} catch (Exception e) {
						if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: inquire channel status exception (1): " + e.getMessage()); }
						AtomicLong msgRec = msgRecMap.get(lookupMsgRec + "_" + channelName);
						if (msgRec == null) {
							msgRecMap.put(lookupMsgRec + "_" + channelName, meterRegistry.gauge(lookupMsgRec, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(msgsOverChannels))
									);
						} else {
							msgRec.set(msgsOverChannels);
						}

					}
					

					/*
					 * Bytes received over the channel
					 */
					try {
						// Count the messages over the number of threads on each channel
						for (PCFMessage pcfM : pcfResp) {
							int bytes = pcfM.getIntParameterValue(MQConstants.MQIACH_BYTES_RECEIVED);										
							//if (bytes < 0) {
							//	bytes = bytes * -1;
							//}
							//bytesReceviedOverChannels += bytes;
							bytesReceviedOverChannels += bytes;
						}
						AtomicLong msgBytesRec = msgBytesRecMap.get(lookupBytesRec + "_" + channelName);
						if (msgBytesRec == null) {
							msgBytesRecMap.put(lookupBytesRec + "_" + channelName, meterRegistry.gauge(lookupBytesRec, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(bytesReceviedOverChannels))
									);
						} else {
							msgBytesRec.set(bytesReceviedOverChannels);
						}

						if (this.metricSummary != null) {
							this.metricSummary.UpdateCounts(channelName
									, channelType
									, this.queueManager
									, channelCluster
									, bytesReceviedOverChannels
									, false);
						}
						
					} catch (Exception e) {
						AtomicLong msgBytesRec = msgBytesRecMap.get(lookupBytesRec + "_" + channelName);
						if (msgBytesRec == null) {
							msgBytesRecMap.put(lookupBytesRec + "_" + channelName, meterRegistry.gauge(lookupBytesRec, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(bytesReceviedOverChannels))
									);
						} else {
							msgBytesRec.set(bytesReceviedOverChannels);
						}
						
						// If the metric summary is required, then updates the counts
						if (this.summaryRequired) {
							if (this.metricSummary != null) {
								this.metricSummary.UpdateCounts(channelName
										, channelType
										, this.queueManager
										, channelCluster
										, MQPCFConstants.PCF_INIT_VALUE, false);
							}
						}
						
					}

					/*
					 * Bytes sent over the channel
					 */
					try {
						// Count the messages over the number of threads on each channel
						for (PCFMessage pcfM : pcfResp) {
							int bytes = pcfM.getIntParameterValue(MQConstants.MQIACH_BYTES_SENT);
							//if (bytes < 0) {
							//	bytes = bytes * -1;
							//}
							//bytesSentOverChannels += bytes;
							bytesSentOverChannels += bytes;
						}
						AtomicLong msgBytesSent = msgBytesSentMap.get(lookupBytesSent + "_" + channelName);
						if (msgBytesSent == null) {
							msgBytesSentMap.put(lookupBytesSent + "_" + channelName, meterRegistry.gauge(lookupBytesSent, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(bytesSentOverChannels))
									);
						} else {
							msgBytesSent.set(bytesSentOverChannels);
						}
						
					} catch (Exception e) {
						AtomicLong msgBytesSent = msgBytesSentMap.get(lookupBytesSent + "_" + channelName);
						if (msgBytesSent == null) {
							msgBytesSentMap.put(lookupBytesSent + "_" + channelName, meterRegistry.gauge(lookupBytesSent, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(bytesSentOverChannels))
									);
						} else {
							msgBytesSent.set(bytesSentOverChannels);
						}
					}			
				}
			}
			
		} catch (Exception e) {
			if (getDebugLevel() == LEVEL.WARN
					|| getDebugLevel() == LEVEL.DEBUG
					|| getDebugLevel() == LEVEL.ERROR
					|| getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: unable to get channel metrics " + e.getMessage()); }
		
		}
		
		/*
		 * If the summary file is required, save the details
		 */
		if (this.summaryRequired) {
			this.metricSummaryCount++;
			if (getDebugLevel() == LEVEL.INFO) { log.info("SummaryCount = " + this.metricSummaryCount); }
			
			if ((this.metricSummaryCount % this.saveSummaryStats) == SAVEMETRICS) {
				this.metricSummaryCount = MQPCFConstants.BASE;
				this.metricSummary.SaveMetrics();
				this.metricSummary.DoWeNeedToRollOver();
			}
		}
		
	}

	/*
	 * Convert the channel name
	 */
	private String getChannelType(int chlType) {
		
		String channelType = "";
		switch (chlType) {
			case MQConstants.MQCHT_SVRCONN:
			{
				channelType = "ServerConn";
				break;
			}
			case MQConstants.MQCHT_SENDER:
			{
				channelType = "Sender";
				break;
			}
			case MQConstants.MQCHT_RECEIVER:
			{
				channelType = "Receiver";
				break;
			}
			case MQConstants.MQCHT_CLNTCONN:
			{
				channelType = "ClientConn";
				break;
			}
			case MQConstants.MQCHT_CLUSRCVR:
			{
				channelType = "ClusterReceiver";
				break;
			}
			case MQConstants.MQCHT_CLUSSDR:
			{
				channelType = "ClusterSender";
				break;
			}
			case MQConstants.MQCHT_REQUESTER:
			{
				channelType = "Requester";
				break;
			}
			case MQConstants.MQCHT_AMQP:
			{
				channelType = "AMQP";
				break;
			}
			case MQConstants.MQCHT_MQTT:
			{
				channelType = "MQTT";
				break;
			}
			case MQConstants.MQCHT_SERVER:
			{
				channelType = "Server";
				break;
			}
			default:
			{
				channelType = "Unknown";
				break;
			}
		}
				
		return channelType;
		
	}

	/*
	 * Check for the correct channel name
	 */
	private boolean checkChannelNames(String name) {

		// Exclude ...
		for (String s : this.excludeChannels) {
			if (s.equals("*")) {
				break;
			} else {
				if (name.startsWith(s)) {
					return false;
				}
			}
		}
	
		// Check channels against the list 
		for (String s : this.includeChannels) {
			if (s.equals("*")) {
				return true;
			} else {
				if (name.startsWith(s)) {
					return true;
				}				
			}
		}		
		return false;
	}

	/*
	 * Allow access to delete the metrics
	 */
	public void resetMetrics() {
		if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfChannel: resetting metrics"); }
		deleteMetrics();
	}	
	
	/*
	 * Reset the metrics
	 */
	private void deleteMetrics() {
		deleteMetricEntry(lookupChannel);
		deleteMetricEntry(lookupMsgRec);
		deleteMetricEntry(lookupBytesRec);
		deleteMetricEntry(lookupBytesSent);
		
	    this.channelMap.clear();
	    this.msgRecMap.clear();
	    this.msgBytesRecMap.clear();
	    this.msgBytesSentMap.clear();

		
	}

    
}
