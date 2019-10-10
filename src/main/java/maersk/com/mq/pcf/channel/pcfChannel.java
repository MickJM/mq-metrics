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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import maersk.com.mq.metrics.mqmetrics.MQBase;
import maersk.com.mq.metricsummary.MQMetricSummary;

@Component
public class pcfChannel extends MQBase {

    private Logger log = Logger.getLogger(this.getClass());

	private static final int SAVEMETRICS = 0;
	protected static final String lookupChannel = MQPREFIX + "channelStatus";
	protected static final String lookupMsgRec = MQPREFIX + "messagesReceived";
	protected static final String lookupBytesRec = MQPREFIX + "bytesReceived";
	protected static final String lookupBytesSent = MQPREFIX + "bytesSent";
	protected static final String lookupClientConnection = MQPREFIX + "clientConnections";

	private String queueManager;

	@Value("${application.save.summary.stats:3}")
    private int saveSummaryStats;
	@Value("${application.save.summary.required:false}")
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
    
    public pcfChannel(MQMetricSummary metricSummary) {

    	if (this.summaryRequired) {
			if (metricSummary != null) {
				if (this._debug) { log.info("MetricSummary exists object has been created ..."); }
		    	this.metricSummary = metricSummary;
		    	
			} else {
				if (this._debug) { log.info("MetricSummary does not exist ...."); }
				
			}
			
			if (this.metricSummary != null) {
		    	this.metricSummary.LoadMetrics();	
		    	
			} else {
				if (this._debug) { log.info("this.metricsSummary object has not been created !!"); }
				
			}
    	}
    	
    }
    
    /*
     * Get the channel metrics
     */
	public void UpdateChannelMetrics() throws MQException, IOException, PCFException, MQDataException, ParseException {

		resetMetric();
		
		if (this._debug) { log.info("pcfChannel: inquire on channel request"); }

		// Enquire on all channels
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL);
		pcfRequest.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*");
		int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
		pcfRequest.addParameter(MQConstants.MQIACF_CHANNEL_ATTRS, pcfParmAttrs);
		
		PCFMessage[] pcfResponse = null;
		try {
			pcfResponse = this.messageAgent.send(pcfRequest);
		
		} catch (Exception e) {
			if (this._debug) { log.warn("pcfChannel: no response returned - " + e.getMessage()); }
			
		}		
		if (this._debug) { log.info("pcfChannel: inquire on channel response"); }

		int[] pcfStatAttrs = { MQConstants.MQIACF_ALL };
		int iChannelCounter = MQPCFConstants.BASE;
		int iChannelSeq = MQPCFConstants.BASE;
		
		String debugName = "";
		
		// for each return response, loop
		try {
			for (PCFMessage pcfMsg : pcfResponse) {
				String channelName = pcfMsg.getStringParameterValue(MQConstants.MQCACH_CHANNEL_NAME).trim(); 
				if (this._debug) { log.info("pcfChannel: " + channelName); }
				
				int chlType = pcfMsg.getIntParameterValue(MQConstants.MQIACH_CHANNEL_TYPE);	
				String channelType = GetChannelType(chlType);
				
				String channelCluster = "";
				if ((chlType == MQConstants.MQCHT_CLUSRCVR) ||
						(chlType == MQConstants.MQCHT_CLUSSDR)) {
					channelCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();
				}
				
				// Correct channel ?
				if (checkChannelNames(channelName.trim())) {
					if (this._debug) { log.info("pcfChannel: inquire channel status " + channelName); }
					PCFMessage pcfReq = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS);
					pcfReq.addParameter(MQConstants.MQCACH_CHANNEL_NAME, channelName);
					pcfReq.addParameter(MQConstants.MQIACH_CHANNEL_INSTANCE_TYPE, MQConstants.MQOT_CURRENT_CHANNEL);				
					pcfReq.addParameter(MQConstants.MQIACH_CHANNEL_INSTANCE_ATTRS, pcfStatAttrs);
	
					
					// loop through each response
					// ... for now, only show that the channel is running and not ALL instances that is using the channel
					// ... this is becuase of the way the prometheus metrics are registered
			        PCFMessage[] pcfResp = null;
					try {
						pcfResp = this.messageAgent.send(pcfReq);
						if (this._debug) { log.info("pcfChannel: inquire channel status response "); }
						PCFMessage pcfStatus = pcfResp[MQPCFConstants.BASE];
						//for (PCFMessage pcfMessage : pcfResp) {
							
						int channelStatus = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_CHANNEL_STATUS);
						meterRegistry.gauge(lookupChannel, 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								,channelStatus);

					} catch (PCFException pcfe) {
						if (pcfe.reasonCode == MQConstants.MQRCCF_CHL_STATUS_NOT_FOUND) {
							if (this._debug) { log.info("pcfChannel: inquire channel status NOT FOUND"); }
							meterRegistry.gauge(lookupChannel, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											)
									,MQConstants.MQCHS_INACTIVE);
						}
						
					} catch (Exception e) {
						if (this._debug) { log.info("pcfChannel: inquire channel status exception: " + e.getMessage()); }
						meterRegistry.gauge(lookupChannel, 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								,MQConstants.MQCHS_INACTIVE);

					}
	
					// Add IP address details here ...
					
					long msgsOverChannels = 0l;
					int bytesReceviedOverChannels = MQPCFConstants.BASE;
					int bytesSentOverChannels = MQPCFConstants.BASE;
					if (this._debug) { log.info("pcfChannel: inquire messages over channels"); }
					try {
						
						// Count the messages over the number of threads on each channel
						for (PCFMessage pcfM : pcfResp) {
							long msgs = pcfM.getIntParameterValue(MQConstants.MQIACH_MSGS);		
							msgsOverChannels += msgs;
						}
						if (this._debug) { log.info("pcfChannel: channel count: " + msgsOverChannels); }
						meterRegistry.gauge(lookupMsgRec, 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								,msgsOverChannels);
	
						if (this.metricSummary != null) {
							this.metricSummary.UpdateCounts(channelName
									, channelType
									, this.queueManager
									, channelCluster
									, msgsOverChannels);
						}
						
					} catch (Exception e) {
						if (this._debug) { log.info("pcfChannel: inquire channel status exception (1): " + e.getMessage()); }
						meterRegistry.gauge(lookupMsgRec, 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								,msgsOverChannels);
						
						// If the metric summary is required, then updates the counts
						if (this.summaryRequired) {
							if (this.metricSummary != null) {
								this.metricSummary.UpdateCounts(channelName
										, channelType
										, this.queueManager
										, channelCluster
										, MQPCFConstants.PCF_INIT_VALUE);
							}
						}
					}
					
					
					try {
						// Count the messages over the number of threads on each channel
						for (PCFMessage pcfM : pcfResp) {
							int bytes = pcfM.getIntParameterValue(MQConstants.MQIACH_BYTES_RECEIVED);										
							if (bytes < 0) {
								bytes = bytes * -1;
							}
							bytesReceviedOverChannels += bytes;
						}	
						meterRegistry.gauge(lookupBytesRec, 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								,bytesReceviedOverChannels);

					} catch (Exception e) {
						meterRegistry.gauge(lookupBytesRec, 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								,MQPCFConstants.PCF_INIT_VALUE);
						
					}
	
					try {
						// Count the messages over the number of threads on each channel
						for (PCFMessage pcfM : pcfResp) {
							int bytes = pcfM.getIntParameterValue(MQConstants.MQIACH_BYTES_SENT);
							if (bytes < 0) {
								bytes = bytes * -1;
							}
							bytesSentOverChannels += bytes;
						}
						meterRegistry.gauge(lookupBytesSent, 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								,bytesSentOverChannels);

					} catch (Exception e) {
						meterRegistry.gauge(lookupBytesSent, 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								,MQPCFConstants.PCF_INIT_VALUE);
						
					}			
				}
			}
			
		} catch (Exception e) {
			if (this._debug) { log.warn("pcfChannel: unable to get channel metrics " + e.getMessage()); }
		
		}
		
		if (this.summaryRequired) {
			this.metricSummaryCount++;
			log.info("SummaryCount = " + this.metricSummaryCount);
			
			if ((this.metricSummaryCount % this.saveSummaryStats) == SAVEMETRICS) {
				this.metricSummaryCount = MQPCFConstants.BASE;
				log.info("SummaryCount = " + this.metricSummaryCount);
				this.metricSummary.SaveMetrics();
				this.metricSummary.DoWeNeedToRollOver();
			}
		}
		
	}


	private String GetChannelType(int chlType) {
		
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
	 * Reset the metrics
	 */
	public void resetMetric() {
		DeleteMetricEntry(lookupChannel);
		DeleteMetricEntry(lookupMsgRec);
		DeleteMetricEntry(lookupBytesRec);
		DeleteMetricEntry(lookupBytesSent);
		
	}
    
}
