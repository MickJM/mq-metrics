package monitor.mq.pcf.channel;

/*
 * Copyright 2019
 * Get channel details
 * 
 */

import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

import io.micrometer.core.instrument.Tags;
import monitor.mq.metrics.mqmetrics.MQMonitorBase;
import monitor.mq.metrics.mqmetrics.MQPCFConstants;

@Component
public class pcfChannel {

    private final static Logger log = LoggerFactory.getLogger(pcfChannel.class);

	private String lookupChannel = "mq:channelStatus";
	private String lookupChannelConns = "mq:channelConnections";
	private String lookupMsgRec = "mq:messagesReceived";
	private String lookupBytesRec = "mq:bytesReceived";
	private String lookupBytesSent = "mq:bytesSent";
	private String lookupMaxMsgSize = "mq:channelMaxMsgSize";
	private String lookupInDoubt = "mq:channelsInDoubt";
	private String lookupDisc = "mq:channelDisconnectInt";
	private String lookupHB = "mq:channelHeartBeatInt";
	private String lookupKeepAlive = "mq:channelKeepAliveInt";
	
	/*
	 * 22/10/2020 - MJM - Amended channelMap to AtomicLong
	 */
    private Map<String,AtomicLong>channelMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>msgRecMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>msgBytesRecMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>msgBytesSentMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>msgMaxMsgSizeMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>channelsInDoubtMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>channelGeneralMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>channelCountsMap = new HashMap<String,AtomicLong>();

	private String queueManager;

	@Value("${ibm.mq.objects.channels.exclude}")
    private String[] excludeChannels;
	@Value("${ibm.mq.objects.channels.include}")
    private String[] includeChannels;
	
    private PCFMessageAgent messageAgent;
    public void setMessageAgent(PCFMessageAgent agent) {
    	this.messageAgent = agent;
    	this.queueManager = this.messageAgent.getQManagerName().trim();    	
    }
    private PCFMessageAgent getMessageAgent() {
    	return this.messageAgent;
    }

    @Autowired
    private MQMonitorBase base;
    
    /*
     * Constructor ...
     */
    public pcfChannel() {		
    }

    /*
     * When the class is fully created ...
     */
    @PostConstruct
    private void initSetup() {
		log.info("pcfChannel: Object created");

    	log.debug("Excluding channels ;");
    	for (String s : this.excludeChannels) {
    		log.debug(s);
    	}
    }
        
    /*
     * Get the channel metrics
     */
	public void UpdateChannelMetrics() throws MQException, IOException, PCFException, MQDataException, ParseException {
		
		log.debug("pcfChannel: inquire on channel request"); 
		
		/*
		 * Clear the metrics every 'x' iteration
		 */
		//base.setCounter();
		if (base.getCounter() % base.ClearMetrics() == 0) {
			//base.setCounter(0);
			log.debug("Clearing channel metrics");
			ResetMetrics();
		}
		
		/*
		 *  Enquire on all channels
		 */
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL);
		pcfRequest.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*");
		int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
		pcfRequest.addParameter(MQConstants.MQIACF_CHANNEL_ATTRS, pcfParmAttrs);
		
		PCFMessage[] pcfResponse = null;
		try {
			pcfResponse = getMessageAgent().send(pcfRequest);
		
		} catch (Exception e) {
			log.debug("pcfChannel: no response returned - " + e.getMessage());
			
		}		
				
		log.debug("pcfChannel: inquire on channel response");
		int[] pcfStatAttrs = { MQConstants.MQIACF_ALL };

		// for each return response, loop
		try {
			for (PCFMessage pcfMsg : pcfResponse) {
				String channelName = pcfMsg.getStringParameterValue(MQConstants.MQCACH_CHANNEL_NAME).trim(); 
				log.debug("pcfChannel: " + channelName);
				
				int chlType = pcfMsg.getIntParameterValue(MQConstants.MQIACH_CHANNEL_TYPE);	
				String channelType = getChannelType(chlType);
				
				String channelCluster = "";
				if ((chlType == MQConstants.MQCHT_CLUSRCVR) ||
						(chlType == MQConstants.MQCHT_CLUSSDR)) {
					channelCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();
				}
				
				// Correct channel ?
				if (checkChannelNames(channelName)) {
					log.debug("pcfChannel: inquire channel status " + channelName);
					PCFMessage pcfReq = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS);
					pcfReq.addParameter(MQConstants.MQCACH_CHANNEL_NAME, channelName);
					pcfReq.addParameter(MQConstants.MQIACH_CHANNEL_INSTANCE_TYPE, MQConstants.MQOT_CURRENT_CHANNEL);				
					pcfReq.addParameter(MQConstants.MQIACH_CHANNEL_INSTANCE_ATTRS, pcfStatAttrs);
						
					// loop through each response
			        PCFMessage[] pcfResp = null;
					try {
						pcfResp = getMessageAgent().send(pcfReq);
						log.debug("pcfChannel: inquire channel status response ");
						PCFMessage pcfStatus = pcfResp[MQPCFConstants.BASE];
			
						/*
						 * Channel status
						 */
						int channelStatus = pcfStatus.getIntParameterValue(MQConstants.MQIACH_CHANNEL_STATUS);
						AtomicLong channels = channelMap.get(lookupChannel + "_" + channelName );
						if (channels == null) {
							channelMap.put(lookupChannel + "_" + channelName, base.meterRegistry.gauge(lookupChannel, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(channelStatus))
									);
						} else {
							channels.set(channelStatus);
						}
 
					} catch (PCFException pcfe) {
						if (pcfe.reasonCode == MQConstants.MQRCCF_CHL_STATUS_NOT_FOUND) {
							log.debug("pcfChannel: inquire channel status NOT FOUND"); 
							AtomicLong channels = channelMap.get(lookupChannel + "_" + channelName);
							if (channels == null) {
								channelMap.put(lookupChannel + "_" + channelName, base.meterRegistry.gauge(lookupChannel, 
										Tags.of("queueManagerName", this.queueManager,
												"channelType", channelType,
												"channelName", channelName,
												"cluster", channelCluster
												),
										new AtomicLong(MQConstants.MQCHS_INACTIVE))
										);
							} else {
								channels.set(MQConstants.MQCHS_INACTIVE);
							}
							
						}
						
					} catch (Exception e) {
						log.debug("pcfChannel: inquire channel status exception: " + e.getMessage());
						AtomicLong channels = channelMap.get(lookupChannel + "_" + channelName);
						if (channels == null) {
							channelMap.put(lookupChannel + "_" + channelName, 
									base.meterRegistry.gauge(lookupChannel, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(MQConstants.MQCHS_INACTIVE))
									);
						} else {
							channels.set(MQConstants.MQCHS_INACTIVE);
						}
						
					}
	
					
					/*
					 * Channel in doubt
					 * 
					 * https://www.ibm.com/support/knowledgecenter/SSFKSJ_9.1.0/com.ibm.mq.con.doc/q015690_.htm
					 */
					try {
						log.debug("pcfChannel: Channel in-doubt ");

						int channelInDoubt = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_INDOUBT_STATUS);
						String currentLUWID = pcfResp[0].getStringParameterValue(MQConstants.MQCACH_CURRENT_LUWID);
						String lastLUWID = pcfResp[0].getStringParameterValue(MQConstants.MQCACH_LAST_LUWID);
						
						AtomicLong inDoubt = channelsInDoubtMap.get(lookupInDoubt + "_" + channelName );
						if (inDoubt == null) {
							channelsInDoubtMap.put(lookupInDoubt + "_" + channelName, 
									base.meterRegistry.gauge(lookupInDoubt, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster,
											"currentLUWID",currentLUWID,
											"lastLUWID",lastLUWID
											),
									new AtomicLong(channelInDoubt))
									);
						} else {
							inDoubt.set(channelInDoubt);
						}
						
					} catch (Exception e) {
						log.debug("pcfChannel: in-doubt status: " + e.getMessage());
						
					}
					
					/*
					 * Channel Disconnect Interval
					 */
					try {
						log.debug("pcfChannel: disconnect interval ");

						int discConnect = pcfMsg.getIntParameterValue(MQConstants.MQIACH_DISC_INTERVAL);
						
						AtomicLong disConn = channelGeneralMap.get(lookupDisc + "_" + channelType + "_" + channelName );
						if (disConn == null) {
							channelGeneralMap.put(lookupDisc + "_" + channelType + "_" + channelName, 
									base.meterRegistry.gauge(lookupDisc, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(discConnect))
									);
						} else {
							disConn.set(discConnect);
						}
						
					} catch (Exception e) {
						log.debug("pcfChannel: disconect: " + e.getMessage());
						
					}
					
					/*
					 * Channel HeartBeat Interval
					 */
					try {
						log.debug("pcfChannel: heartbeat interval ");

						int hbInt = pcfMsg.getIntParameterValue(MQConstants.MQIACH_HB_INTERVAL);
						
						AtomicLong hbConn = channelGeneralMap.get(lookupHB + "_" + channelType + "_" + channelName );
						if (hbConn == null) {
							channelGeneralMap.put(lookupHB + "_" + channelType + "_" + channelName, 
									base.meterRegistry.gauge(lookupHB, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(hbInt))
									);
						} else {
							hbConn.set(hbInt);
						}
						
					} catch (Exception e) {
						log.debug("pcfChannel: hearbeat: " + e.getMessage());
						
					}

					/*
					 * Channel Hibernate
					 */
					try {
						log.debug("pcfChannel: hibernate interval ");

						int keepAliveInt = pcfMsg.getIntParameterValue(MQConstants.MQIACH_KEEP_ALIVE_INTERVAL);
						
						AtomicLong kaConn = channelGeneralMap.get(lookupKeepAlive + "_" + channelType + "_" + channelName );
						if (kaConn == null) {
							channelGeneralMap.put(lookupKeepAlive + "_" + channelType + "_" + channelName, 
									base.meterRegistry.gauge(lookupKeepAlive, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(keepAliveInt))
									);
						} else {
							kaConn.set(keepAliveInt);
						}
						
					} catch (Exception e) {
						log.debug("pcfChannel: KeepAlive: " + e.getMessage());
						
					}
					
					long msgsOverChannels = 0l;
					int bytesReceviedOverChannels = MQPCFConstants.BASE;
					int bytesSentOverChannels = MQPCFConstants.BASE;
					log.debug("pcfChannel: inquire messages over channels");
					
					/*
					 * Connections per IP address on each channel
					 */
					final Map<String,Integer>conns = new HashMap<String,Integer>();
					conns.clear();
					
					try {		
						int size = 0;						
						log.debug("pcfChannel: channel count: " + size);
						String savedIPaddress = 
								pcfResp[0].
								getStringParameterValue(MQConstants.MQCACH_CONNECTION_NAME).
								trim();
						
						boolean saveMap = false;
						for (PCFMessage pcfM : pcfResp) {
							String ipAddress = pcfM.
									getStringParameterValue(MQConstants.MQCACH_CONNECTION_NAME).
									trim();
							if (!savedIPaddress.contentEquals(ipAddress)) {								
								conns.put(savedIPaddress, size);
								savedIPaddress = ipAddress;

								channelConnections(conns, savedIPaddress, channelName, channelType, channelCluster);
								
							}
							size++;
							saveMap = true;
						}
						if (saveMap) {
							conns.put(savedIPaddress, size);
							channelConnections(conns, savedIPaddress, channelName, channelType, channelCluster);

						}
						
					} catch (Exception e) {
						
					}
					
					/*
					 * Messages received
					 */
					try {
						
						/*
						 * Messages, bytes received and byte sent
						 */
						for (PCFMessage pcfM : pcfResp) {
							long msgs = pcfM.getIntParameterValue(MQConstants.MQIACH_MSGS);		
							msgsOverChannels += msgs;
							
							int bytesRec = pcfM.getIntParameterValue(MQConstants.MQIACH_BYTES_RECEIVED);										
							bytesReceviedOverChannels += bytesRec;

							int bytesSent = pcfM.getIntParameterValue(MQConstants.MQIACH_BYTES_SENT);
							bytesSentOverChannels += bytesSent;
							
						}
						if (msgsOverChannels > 0) {
							log.debug("pcfChannel: channel count: " + msgsOverChannels);
							AtomicLong msgRec = msgRecMap.get(lookupMsgRec + "_" + channelName);
							if (msgRec == null) {
								msgRecMap.put(lookupMsgRec + "_" + channelName, base.meterRegistry.gauge(lookupMsgRec, 
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
						
					} catch (Exception e) {
						if (msgsOverChannels > 0) {
							log.debug("pcfChannel: inquire channel status exception (1): " + e.getMessage()); 
							AtomicLong msgRec = msgRecMap.get(lookupMsgRec + "_" + channelName);
							if (msgRec == null) {
								msgRecMap.put(lookupMsgRec + "_" + channelName, base.meterRegistry.gauge(lookupMsgRec, 
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
					}
					

					/*
					 * Bytes received over the channel
					 */
					try {
						if (bytesReceviedOverChannels > 0) {
							AtomicLong msgBytesRec = msgBytesRecMap.get(lookupBytesRec + "_" + channelName);
							if (msgBytesRec == null) {
								msgBytesRecMap.put(lookupBytesRec + "_" + channelName, base.meterRegistry.gauge(lookupBytesRec, 
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
						}
												
					} catch (Exception e) {
						if (bytesReceviedOverChannels > 0) {
							AtomicLong msgBytesRec = msgBytesRecMap.get(lookupBytesRec + "_" + channelName);
							if (msgBytesRec == null) {
								msgBytesRecMap.put(lookupBytesRec + "_" + channelName, base.meterRegistry.gauge(lookupBytesRec, 
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
						}						
					}

					/*
					 * Bytes sent over the channel
					 */
					try {
						// Count the messages over the number of threads on each channel
						for (PCFMessage pcfM : pcfResp) {
							int bytes = pcfM.getIntParameterValue(MQConstants.MQIACH_BYTES_SENT);
							bytesSentOverChannels += bytes;
						}
						if (bytesSentOverChannels > 0) {
							AtomicLong msgBytesSent = msgBytesSentMap.get(lookupBytesSent + "_" + channelName);
							if (msgBytesSent == null) {
								msgBytesSentMap.put(lookupBytesSent + "_" + channelName, base.meterRegistry.gauge(lookupBytesSent, 
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
					} catch (Exception e) {
						if (bytesSentOverChannels > 0) {
							AtomicLong msgBytesSent = msgBytesSentMap.get(lookupBytesSent + "_" + channelName);
							if (msgBytesSent == null) {
								msgBytesSentMap.put(lookupBytesSent + "_" + channelName, base.meterRegistry.gauge(lookupBytesSent, 
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
				
				} // end of checks				
				
				
				/*
				 * Max msg size
				 */
				if (checkChannelNames(channelName.trim())) {

					try {
						int maxMsgLen = pcfMsg.getIntParameterValue(MQConstants.MQIACH_MAX_MSG_LENGTH);
						AtomicLong maxLen = msgMaxMsgSizeMap.get(lookupMaxMsgSize + "_" + channelName);
						if (maxLen == null) {
							msgMaxMsgSizeMap.put(lookupMaxMsgSize + "_" + channelName, 
									base.meterRegistry.gauge(lookupMaxMsgSize, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(maxMsgLen))
									);
						} else {
							maxLen.set(maxMsgLen);
						}
					} catch (Exception e ) {
						AtomicLong maxLen = msgMaxMsgSizeMap.get(lookupMaxMsgSize + "_" + channelName);
						if (maxLen == null) {
							msgMaxMsgSizeMap.put(lookupMaxMsgSize + "_" + channelName, 
									base.meterRegistry.gauge(lookupMaxMsgSize, 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster", channelCluster
											),
									new AtomicLong(MQConstants.MQCHS_INACTIVE))
									);
						} else {
							maxLen.set(MQConstants.MQCHS_INACTIVE);
						}					
					}
				}
			}
			
		} catch (Exception e) {
			log.debug("pcfChannel: unable to get channel metrics " + e.getMessage());
		
		}
				
	}

	private void channelConnections(Map<String,Integer>conns, String ip, String channelName, String channelType, 
							String channelCluster) {
		
		AtomicLong count = channelCountsMap.get(lookupChannelConns + 
				"_" + ip + "_" + channelName);
		if (count == null) {
			channelCountsMap.put(lookupChannelConns + "_" + ip + "_" + channelName, 
					base.meterRegistry.gauge(lookupChannelConns, 
					Tags.of("queueManagerName", this.queueManager,
							"channelType", channelType,
							"channelName", channelName,
							"cluster", channelCluster,
							"connName",ip
							),
					new AtomicLong(conns.get(ip)))
					);
		} else {
			count.set(conns.get(ip));
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

		if (name.equals(null)) {
			return false;
		}

		int inc = includeChannel(name);
		if (inc == 1) {
			return true;
		}
		
		int exc = excludeChannel(name);
		if (exc == 1) {
			return false;
		}
		
		return true;

	}
	
	private int includeChannel(String name) {
		
		int ret = 0;
		
		// Check queues against the list 
		for (String s : this.includeChannels) {
			if (s.equals("*")) {
				ret = 2;
			} else {
				if (name.startsWith(s)) {
					ret = 1;
				}				
			}
		}
		return ret;
	}
	
	private int excludeChannel(String name) {

		int ret = 0;
		
		// Exclude ...
		for (String s : this.excludeChannels) {
			if (s.equals("*")) {
				ret = 2;
				break;
			} else {
				if (name.startsWith(s)) {
					ret = 1;
				}
			}
		}
		return ret;
	}

	/*
	 * Allow access to delete the metrics
	 */
	public void ResetMetrics() {
		log.debug("pcfChannel: resetting metrics");
		DeleteMetrics();
	}	
	
	/*
	 * Reset the metrics
	 */
	private void DeleteMetrics() {
		base.DeleteMetricEntry(lookupChannel);
		base.DeleteMetricEntry(lookupMsgRec);
		base.DeleteMetricEntry(lookupBytesRec);
		base.DeleteMetricEntry(lookupBytesSent);
		base.DeleteMetricEntry(lookupMaxMsgSize);
		base.DeleteMetricEntry(lookupInDoubt);
		base.DeleteMetricEntry(lookupDisc);
		base.DeleteMetricEntry(lookupHB);
		base.DeleteMetricEntry(lookupKeepAlive);
		base.DeleteMetricEntry(lookupChannelConns);
		
	    this.channelMap.clear();
	    this.msgRecMap.clear();
	    this.msgBytesRecMap.clear();
	    this.msgBytesSentMap.clear();
	    this.msgMaxMsgSizeMap.clear();
	    this.channelsInDoubtMap.clear();
	    this.channelGeneralMap.clear();
	    this.channelCountsMap.clear();
	    
	}

    
}
