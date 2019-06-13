package maersk.com.mq.metrics.mqmetrics;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.PreDestroy;
import javax.validation.constraints.Null;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQEnvironment;
import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import com.ibm.mq.headers.pcf.PCFException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.prometheus.client.CollectorRegistry;
import maersk.com.mq.pcf.queuemanager.pcfQueueManager;

/***
 * 
 * @author Maersk
 *
 * Main class to connect to a queue manager
 * 
 */
@Component
public class MQConnection {

	//
	private static final String MQPREFIX = "mq:";

	// properties or envirnment variables ...
	// taken from connName
	private String hostName;
	
	@Value("${ibm.mq.queuemanager}")
	private String queueManager;
	
	// hostname(port)
	@Value("${ibm.mq.connName}")
	private String connName;	
	@Value("${ibm.mq.channel}")
	private String channel;

	// taken from connName
	private int port;
	
	@Value("${ibm.mq.user}")
	private String userId;
	@Value("${ibm.mq.password}")
	private String password;
	@Value("${ibm.mq.sslCipherSpec}")
	private String cipher;

	//
	@Value("${ibm.mq.useSSL}")
	private boolean bUseSSL;
	
	@Value("${application.debug:false}")
    private boolean _debug;

	@Value("${ibm.mq.objects.queues.exclude}")
    private String[] excludeQueues;
	@Value("${ibm.mq.objects.queues.include}")
    private String[] includeQueues;

	@Value("${ibm.mq.objects.channels.exclude}")
    private String[] excludeChannels;
	@Value("${ibm.mq.objects.channels.include}")
    private String[] includeChannels;
	
	@Value("${ibm.mq.objects.listeners.exclude}")
    private String[] excludeListeners;
	@Value("${ibm.mq.objects.listeners.include}")
    private String[] includeListeners;
	
	@Value("${ibm.mq.objects.listeners.types.exclude}")
    private String[] excludeTypes;
	@Value("${ibm.mq.objects.listeners.types.include}")
    private String[] includeTypes;
	
	@Value("${ibm.mq.security.truststore}")
	private String truststore;
	@Value("${ibm.mq.security.truststore-password}")
	private String truststorepass;
	@Value("${ibm.mq.security.keystore}")
	private String keystore;
	@Value("${ibm.mq.security.keystore-password}")
	private String keystorepass;
	
    //@Autowired(required = false)
    private PCFMessageAgent messageAgent = null;

    @Autowired
    private Map<Integer,String>typeList = null;    
    
    @Autowired
    private MeterRegistry registry = null;
    
    //Queue maps
    private Map<String,AtomicInteger>queueDepMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicInteger>queueDeqMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicInteger>queueEnqMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicLong>queueLGDMap = new HashMap<String, AtomicLong>();
    private Map<String,AtomicLong>queueLPDMap = new HashMap<String, AtomicLong>();
    private Map<String,AtomicLong>queueAgeMap = new HashMap<String, AtomicLong>();
    private Map<String,AtomicInteger>queueOpenInMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicInteger>queueOpenOutMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicInteger>maxQueueDepthMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicInteger>queueHandleMap = new HashMap<String, AtomicInteger>();

    private Map<String,Map<String,AtomicInteger>>collectionqueueHandleMaps 
			= new HashMap<String,Map<String,AtomicInteger>>();

    //Channel maps
    private Map<String,AtomicInteger>channelStatusMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicLong>msgsReceived = new HashMap<String, AtomicLong>();
    private Map<String,AtomicInteger>bytesReceived = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicInteger>bytesSent = new HashMap<String, AtomicInteger>();
    
    //Queue Manager / IIB maps
    private Map<String,AtomicInteger>qmStatusMap = new HashMap<String, AtomicInteger>();

    //MQ reset
    private Map<String,AtomicLong>mqReset = new HashMap<String, AtomicLong>();
    @Value("${ibm.mq.event.delayInMilliSeconds}")
    private long resetIterations;
    
    //Command Server maps
    private Map<String,AtomicInteger>cmdStatusMap = new HashMap<String, AtomicInteger>();

    //Listener status maps
    private Map<String,AtomicInteger>listenerStatusMap = new HashMap<String, AtomicInteger>();

    //
    static Logger log = Logger.getLogger(MQConnection.class);

    //
    //@Autowired
    private pcfQueueManager pcfmessageAgent;
    
    //
    private int queueMonitoringFromQmgr;
    public int getQueueMonitoringFromQmgr() {
		return queueMonitoringFromQmgr;
    }
	public void setQueueMonitoringFromQmgr(int value) {
		this.queueMonitoringFromQmgr = value;
	}
		
	private String queueManagerClusterName;
	public String getQueueManagerClusterName() {
		//return this.queueManagerClusterName;
		return this.pcfmessageAgent.getQueueManagerClusterName();
	}
	public void setQueueManagerClusterName(String value) {
		this.queueManagerClusterName = value;
	}
	
	private MQConnection(MeterRegistry registry) {
	}
	
	/**
     * Main processing ... schedule every 10 seconds to update Metrics
     * 
     * @throws PCFException
     * @throws MQException
     * @throws IOException
     * 
     * If a messageagent is available,
     *     update the queue manager metrics
     *     update the listener metrics
     *     update the queue metrics
     *     update the channel metrics
	 *
     * otherwise,
     * 		attempt to create an MQ connection
     * 
     */
	@Scheduled(fixedDelayString="${ibm.mq.event.delayInMilliSeconds}")
    public void Scheduler() {
	
		try {
			if (this.messageAgent != null) {
				ResetIterations();
				CheckQueueManagerCluster();
				UpdateQMMetrics();
				UpdateListenerMetrics();
				UpdateQueueMetrics();
				UpdateChannelMetrics();
				
			} else {
				log.info("No MQ queue manager object");
				CreateQueueManagerConnection();
				this.pcfmessageAgent = new pcfQueueManager(this.messageAgent);
				this.pcfmessageAgent.setResetIterations(resetIterations);
				
			}
		} catch (PCFException p) {
			log.info("PCFException " + p.getMessage());
			QueueManagerIsNotRunning();
			this.messageAgent = null;
			
		} catch (MQException m) {
			log.info("MQException " + m.getMessage());
			QueueManagerIsNotRunning();
			this.messageAgent = null;
			
		} catch (IOException i) {
			log.info("IOException " + i.getMessage());
			QueueManagerIsNotRunning();
			this.messageAgent = null;
			
		} catch (Exception e) {
			log.info("Exception " + e.getMessage());
			QueueManagerIsNotRunning();
			this.messageAgent = null;
		}
    }
    
	/**
	 * Create an MQ connection to the queue manager
	 * ... once connected, create a messageAgent for PCF commands
	 * 
	 * @throws MQException
	 * @throws MQDataException 
	 */
	private void CreateQueueManagerConnection() throws MQException, MQDataException {
		
		GetEnvironmentVariables();
		
		Hashtable<String, Comparable> env = new Hashtable<String, Comparable>();
		env.put(MQConstants.HOST_NAME_PROPERTY, this.hostName);
		env.put(MQConstants.CHANNEL_PROPERTY, this.channel);
		env.put(MQConstants.PORT_PROPERTY, this.port);
		
		if (this._debug) {
			log.info("Host 		: " + this.hostName);
			log.info("Channel 	: " + this.channel);
			log.info("Port 		: " + this.port);
			log.info("Queue Man : " + this.queueManager);
			log.info("User 		: " + this.userId);
			log.info("Password  : **********");

		}
		/*
		 * 
		 * If a username and password is provided, then use it
		 * ... if CHCKCLNT is set to OPTIONAL or RECDADM
		 * ... RECDADM will use the username and password if provided ... if a password is not provided
		 * ...... then the connection is used like OPTIONAL
		 */
		
		if (this.userId != null) {
			env.put(MQConstants.USER_ID_PROPERTY, this.userId); 
		}
		if (this.password != null) {
			env.put(MQConstants.PASSWORD_PROPERTY, this.password);
		}
		env.put(MQConstants.TRANSPORT_PROPERTY,MQConstants.TRANSPORT_MQSERIES);

		// If SSL is enabled (default)
		if (this.bUseSSL) {
			if (this._debug) {
				log.info("SSL is enabled ....");
			}
			System.setProperty("javax.net.ssl.trustStore", this.truststore);
	        System.setProperty("javax.net.ssl.trustStorePassword", this.truststorepass);
	        System.setProperty("javax.net.ssl.trustStoreType","JKS");
	        System.setProperty("javax.net.ssl.keyStore", this.keystore);
	        System.setProperty("javax.net.ssl.keyStorePassword", this.keystorepass);
	        System.setProperty("javax.net.ssl.keyStoreType","JKS");
	        System.setProperty("com.ibm.mq.cfg.useIBMCipherMappings","false");
	        env.put(MQConstants.SSL_CIPHER_SUITE_PROPERTY, this.cipher); 
		} else {
			if (this._debug) {
				log.info("SSL is NOT enabled ....");
			}
		}
		
        //System.setProperty("javax.net.debug","all");
		if (this._debug) {
			log.info("TrustStore       : " + this.truststore);
			log.info("TrustStore Pass  : ********");
			log.info("KeyStore         : " + this.keystore);
			log.info("KeyStore Pass    : ********");
			log.info("Cipher Suite     : " + this.cipher);
		}
		
		log.info("Attempting to connect to queue manager " + this.queueManager);
		MQQueueManager queManager = new MQQueueManager(this.queueManager, env);
		
		log.info("Connection to queue manager established ");
		log.info("Creating PCFAgent ");
		this.messageAgent = new PCFMessageAgent(queManager);
		
		
		log.info("PCF agent to  " + this.queueManager + " established.");

	}

	/**
	 * Get MQ details from environment variables
	 */
	private void GetEnvironmentVariables() {
		
		/*
		 * ALL parameter are passed in the application.yaml file ...
		 *    These values can be overrrided using an application-???.yaml file per environment
		 *    ... or passed in on the command line
		 */
		
		// Split the host and port number from the connName ... host(port)
		if (!this.connName.equals("")) {
			Pattern pattern = Pattern.compile("^([^()]*)\\(([^()]*)\\)(.*)$");
			Matcher matcher = pattern.matcher(this.connName);	
			if (matcher.matches()) {
				this.hostName = matcher.group(1).trim();
				this.port = Integer.parseInt(matcher.group(2).trim());
			} else {
				log.error("connName mismatch ");
				System.exit(1);				
			}
		} else {
			log.error("connName mismatch ");
			System.exit(1);
			
		}

		// dont allow to run as MQM
		if (this.userId == null) {
			return;
		}
		
		if (!this.userId.equals("")) {
			if ((this.userId.equals("mqm") || (this.userId.equals("MQM")))) {
				log.error("Channel USERID must not be running as 'mqm'");
				System.exit(1);
			}
		} else {
			this.userId = null;
			this.password = null;
		}
	
	}
	
	/**
	 * When the queue manager isn't running, send back a status of inactive 
	 */
	private void QueueManagerIsNotRunning() {

		// Set the queue manager status to indicate that its not running
		AtomicInteger q = qmStatusMap.get(this.queueManager);
		if (q == null) {
			qmStatusMap.put(this.queueManager, 
					Metrics.gauge("mq:queueManagerStatus", 
					Tags.of("queueManagerName", this.queueManager,
							"cluster","unknown"), 
					new AtomicInteger(0)));
		} else {
			q.set(0);
		}        

		// For each listener, set the status to indicate its not running, as the ...
		// ... queue manager is not running
		Iterator<Entry<String, AtomicInteger>> listListener = this.listenerStatusMap.entrySet().iterator();
		while (listListener.hasNext()) {
	        Map.Entry pair = (Map.Entry)listListener.next();
	        String key = (String) pair.getKey();
	        try {
				AtomicInteger i = (AtomicInteger) pair.getValue();
				if (i != null) {
					i.set(0);
				}
	        } catch (Exception e) {
	        	System.out.println("Ignore the error");
	        }
		}
	

		Iterator<Entry<String, AtomicInteger>> listChannels = this.channelStatusMap.entrySet().iterator();
		while (listChannels.hasNext()) {
	        Map.Entry pair = (Map.Entry)listChannels.next();
	        String key = (String) pair.getKey();
	        try {
				AtomicInteger i = (AtomicInteger) pair.getValue();
				if (i != null) {
					i.set(0);
				}
	        } catch (Exception e) {
	        	System.out.println("Ignore channel error ");
	        }
		}

		
		/*
		AtomicInteger l = listenerStatusMap.get(listenerName);
		if (l == null) {
			listenerStatusMap.put(listenerName, Metrics.gauge(new StringBuilder()
					.append(MQPREFIX)
					.append("listenerStatus")
					.toString(),
					Tags.of("queueManagerName", this.queueManager,
							"listenerName", listenerName,
							"type", Integer.toString(type),
							"port", Integer.toString(portNumber))
					, new AtomicInteger(0)));
		} else {
			l.set(0);
		}
		*/
		
		/*
		Iterator qDep = this.queueDepMap.entrySet().iterator();
		while (qDep.hasNext()) {
	        Map.Entry pair = (Map.Entry)qDep.next();
	        String key = (String) pair.getKey();	        
			AtomicInteger i = (AtomicInteger) pair.setValue(key);
			if (i != null) {
				i.set(0);
			}
		}
		*/
		
	}

	/***
	 * Reset iterations value between capturing performance metrics
	 */
	private void ResetIterations() {
		
		this.pcfmessageAgent.ResetIteration();
		/*
		Long l = resetIterations;		
        AtomicLong q = mqReset.get(this.queueManager);
		if (q == null) {
			mqReset.put(this.queueManager, 
					Metrics.gauge(new StringBuilder()
							.append(MQPREFIX)
							.append("ResetIterations").toString(),  
					Tags.of("queueManagerName", this.queueManager),
					new AtomicLong(l)));
		} else {
			q.set(0);
		}        
		*/
		
	}
	/***
	 * 
	 * Check if the queue manager belongs to a cluster ...
	 * 
	 * @throws PCFException
	 * @throws MQException
	 * @throws IOException
	 * @throws MQDataException
	 */
	private void CheckQueueManagerCluster() {

		this.pcfmessageAgent.CheckQueueManagerCluster();
		
		/*
        //int[] pcfParmAttrs = { MQConstants.MQIACF_Q_MGR_CLUSTER };
        int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
        
        PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_CLUSTER_Q_MGR);
        //pcfRequest1.addParameter(MQConstants.MQIACF_Q_MGR_ATTRS, pcfParmAttrs);
        //pcfRequest.addParameter(MQConstants.MQIACF_Q_MGR_ATTRS, pcfParmAttrs);
        pcfRequest.addParameter(MQConstants.MQCA_CLUSTER_Q_MGR_NAME, this.queueManager); 
        pcfRequest.addParameter(MQConstants.MQIACF_CLUSTER_Q_MGR_ATTRS, pcfParmAttrs);
       
        // if an error occurs, ignore it, as the queue manager may not belong to a cluster
        try {
	        PCFMessage[] pcfResponse = this.messageAgent.send(pcfRequest);
	        PCFMessage response = pcfResponse[0];
	        String clusterNames = response.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME);
	        setQueueManagerClusterName(clusterNames.trim());
        } catch (Exception e) {
        	if (this._debug) { log.info("Queue manager " + this.queueManager.trim() + " does not belong to a cluster"); }
        	setQueueManagerClusterName("");
        	
        }
    	*/
		
	}
	
	/**
	 * Update the queue manager metrics
	 * 
	 * @throws PCFException
	 * @throws MQException
	 * @throws IOException
	 * @throws PCFException 
	 * @throws MQDataException 
	 */
	private void UpdateQMMetrics() throws PCFException, 
											MQException, 
											IOException, 
											MQDataException {

		
		// Enquire on the queue manager ...
        int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
        PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR);
        pcfRequest.addParameter(MQConstants.MQIACF_Q_MGR_ATTRS, pcfParmAttrs);
        //pcfRequest.addParameter(MQConstants.MQIACF_Q_MGR_CLUSTER, pcfParmAttrs);
        PCFMessage[] pcfResponse = this.messageAgent.send(pcfRequest);		
    	PCFMessage response = pcfResponse[0];

    	// Save the queue monitoring attribute to be used later
		int queueMon = response.getIntParameterValue(MQConstants.MQIA_MONITORING_Q);
		setQueueMonitoringFromQmgr(queueMon);

		// Send a queue manager status request
        pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR_STATUS);
        pcfRequest.addParameter(MQConstants.MQIACF_Q_MGR_STATUS_ATTRS, pcfParmAttrs);
        pcfResponse = this.messageAgent.send(pcfRequest);		
    	response = pcfResponse[0];       	
    	
		// queue manager status
        int qmStatus = response.getIntParameterValue(MQConstants.MQIACF_Q_MGR_STATUS);
        
        AtomicInteger q = qmStatusMap.get(this.queueManager);
		if (q == null) {
			qmStatusMap.put(this.queueManager, 
					Metrics.gauge(new StringBuilder()
							.append(MQPREFIX)
							.append("queueManagerStatus")
							.toString(),  
					Tags.of("queueManagerName", this.queueManager,
							"cluster",getQueueManagerClusterName()),
					new AtomicInteger(qmStatus)));
		} else {
			q.set(qmStatus);
		}        
		
		// command server
		int cmdStatus = response.getIntParameterValue(MQConstants.MQIACF_CMD_SERVER_STATUS);
		AtomicInteger cmd = cmdStatusMap.get(this.queueManager);
		if (cmd == null) {
			cmdStatusMap.put(this.queueManager, 
					Metrics.gauge(new StringBuilder()
							.append(MQPREFIX)
							.append("commandServerStatus")
							.toString(), 
					Tags.of("queueManagerName", this.queueManager), 
					new AtomicInteger(cmdStatus)));
		} else {
			cmd.set(cmdStatus);
		}        

	}

	/**
	 * Update the queue manager listener metrics
	 * 
	 * @throws MQException
	 * @throws IOException
	 * @throws MQDataException 
	 */
	private void UpdateListenerMetrics() throws MQException, IOException, MQDataException {

		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER);
		pcfRequest.addParameter(MQConstants.MQCACH_LISTENER_NAME, "*");
		int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
		pcfRequest.addParameter(MQConstants.MQIACF_LISTENER_ATTRS, pcfParmAttrs);
        PCFMessage[] pcfResponse = this.messageAgent.send(pcfRequest);
        
        int[] pcfStatAttrs = { 	MQConstants.MQIACF_ALL };
		// For each response back, loop to process 
		for (PCFMessage pcfMsg : pcfResponse) {	
			int portNumber = 0;
			int type = -1;
			String listenerName = 
					pcfMsg.getStringParameterValue(MQConstants.MQCACH_LISTENER_NAME).trim(); 
			int listType = 
					pcfMsg.getIntParameterValue(MQConstants.MQIACH_XMIT_PROTOCOL_TYPE);
			String typeName = typeList.get(listType).trim();			
			if (checkListenNames(listenerName.trim())) {
				
				// Correct listener type ? Only interested in TCP
				if (checkType(typeName)) {
					PCFMessage pcfReq = new PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER_STATUS);
					pcfReq.addParameter(MQConstants.MQCACH_LISTENER_NAME, listenerName);
					pcfReq.addParameter(MQConstants.MQIACF_LISTENER_STATUS_ATTRS, pcfStatAttrs);
	
			        PCFMessage[] pcfResp = null;
					try {			
						pcfResp = this.messageAgent.send(pcfReq);
						int listenerStatus = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_LISTENER_STATUS);					
						portNumber = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_PORT);
						type = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_XMIT_PROTOCOL_TYPE);
						
						AtomicInteger l = listenerStatusMap.get(listenerName);
						if (l == null) {
							listenerStatusMap.put(listenerName, Metrics.gauge(new StringBuilder()
									.append(MQPREFIX)
									.append("listenerStatus").toString(), 
									Tags.of("queueManagerName", this.queueManager,
											"listenerName", listenerName,
											"type", Integer.toString(type),
											"port", Integer.toString(portNumber))
									, new AtomicInteger(listenerStatus)));
						} else {
							l.set(listenerStatus);
						}
					} catch (PCFException pcfe) {
						if (pcfe.reasonCode == MQConstants.MQRCCF_LSTR_STATUS_NOT_FOUND) {
							
							AtomicInteger l = listenerStatusMap.get(listenerName);
							if (l == null) {
								listenerStatusMap.put(listenerName, Metrics.gauge(new StringBuilder()
										.append(MQPREFIX)
										.append("listenerStatus").toString(), 
										Tags.of("queueManagerName", this.queueManager,
												"listenerName", listenerName,
												"type",Integer.toString(listType),
												"port", Integer.toString(portNumber))
										, new AtomicInteger(0)));
							} else {
								l.set(0);
							}
						}
						if (pcfe.reasonCode == MQConstants.MQRC_UNKNOWN_OBJECT_NAME) {
							
							AtomicInteger l = listenerStatusMap.get(listenerName);
							if (l == null) {
								listenerStatusMap.put(listenerName, Metrics.gauge(new StringBuilder()
										.append(MQPREFIX)
										.append("listenerStatus").toString(), 
										Tags.of("queueManagerName", this.queueManager,
												"listenerName", listenerName,
												"type",Integer.toString(listType),
												"port", Integer.toString(portNumber))
										, new AtomicInteger(0)));
							} else {
								l.set(0);
							}
						}

						
					} catch (Exception e) {
						AtomicInteger l = listenerStatusMap.get(listenerName);
						if (l == null) {
							listenerStatusMap.put(listenerName, Metrics.gauge(new StringBuilder()
									.append(MQPREFIX)
									.append("listenerStatus").toString(),
									Tags.of("queueManagerName", this.queueManager,
											"listenerName", listenerName,
											"type", Integer.toString(type),
											"port", Integer.toString(portNumber))
									, new AtomicInteger(0)));
						} else {
							l.set(0);
						}
					}				
				}
			}
		}		
	}
		
	/**
	 * Update the Channel Metrics
	 * 
	 * @throws MQException
	 * @throws IOException
	 * @throws MQDataException 
	 * @throws PCFException 
	 */
	private void UpdateChannelMetrics() throws MQException, IOException, PCFException, MQDataException {
		
		// Enquire on all channels
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL);
		pcfRequest.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*");
		int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
		pcfRequest.addParameter(MQConstants.MQIACF_CHANNEL_ATTRS, pcfParmAttrs);
        PCFMessage[] pcfResponse = this.messageAgent.send(pcfRequest);

		int[] pcfStatAttrs = { MQConstants.MQIACF_ALL };
		
		// for each return response, loop
		for (PCFMessage pcfMsg : pcfResponse) {	
			String channelName = pcfMsg.getStringParameterValue(MQConstants.MQCACH_CHANNEL_NAME).trim(); 
			int chlType = pcfMsg.getIntParameterValue(MQConstants.MQIACH_CHANNEL_TYPE);	
			String channelType = GetChannelType(chlType);
			
			String channelCluster = "";
			if ((chlType == MQConstants.MQCHT_CLUSRCVR) ||
					(chlType == MQConstants.MQCHT_CLUSSDR)) {
				channelCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();
			}
			
			// Correct channel ?
			if (checkChannelNames(channelName.trim())) {
				
				PCFMessage pcfReq = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS);
				pcfReq.addParameter(MQConstants.MQCACH_CHANNEL_NAME, channelName);
				pcfReq.addParameter(MQConstants.MQIACH_CHANNEL_INSTANCE_TYPE, MQConstants.MQOT_CURRENT_CHANNEL);				
				pcfReq.addParameter(MQConstants.MQIACH_CHANNEL_INSTANCE_ATTRS, pcfStatAttrs);

				//***** multiple connections ... add loop
				//MQCACH_CONNECTION_NAME, MQCACH_LOCAL_ADDRESS
		        PCFMessage[] pcfResp = null;
				try {
					pcfResp = this.messageAgent.send(pcfReq);
					
					for (PCFMessage pcfMessage : pcfResp) {

						int channelStatus = pcfMessage.getIntParameterValue(MQConstants.MQIACH_CHANNEL_STATUS);
						String conn = pcfMessage.getStringParameterValue(MQConstants.MQCACH_CONNECTION_NAME).trim();

						AtomicInteger c = channelStatusMap.get(channelName);
						if (c == null) {
							channelStatusMap.put(channelName, Metrics.gauge(new StringBuilder()
									.append(MQPREFIX)
									.append("channelStatus").toString(), 
									Tags.of("queueManagerName", this.queueManager,
											"channelType", channelType,
											"channelName", channelName,
											"cluster",channelCluster,
											"connection", conn
											)
									, new AtomicInteger(channelStatus)));
						} else {
							c.set(channelStatus);
						}
					}

				} catch (PCFException pcfe) {
					if (pcfe.reasonCode == MQConstants.MQRCCF_CHL_STATUS_NOT_FOUND) {
						
						AtomicInteger c = channelStatusMap.get(channelName);
						if (c == null) {
							channelStatusMap.put(channelName, Metrics.gauge(new StringBuilder()
									.append(MQPREFIX)
									.append("channelStatus").toString(), 
									Tags.of("queueManagerName", this.queueManager,
											"channelName", channelName,
											"channelType", channelType,				
											"cluster",channelCluster,
											"connection", ""
											)
									, new AtomicInteger(MQConstants.MQCHS_INACTIVE)));
						} else {
							c.set(MQConstants.MQCHS_INACTIVE);
						}
					}
					
				} catch (Exception e) {
					AtomicInteger c = channelStatusMap.get(channelName);
					if (c == null) {
						channelStatusMap.put(channelName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("channelStatus").toString(),
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster",channelCluster,
										"connection", ""
										)
								, new AtomicInteger(MQConstants.MQCHS_INACTIVE)));
					} else {
						c.set(MQConstants.MQCHS_INACTIVE);
					}
				}

				long msgsOverChannels = 0l;
				int bytesReceviedOverChannels = 0;
				int bytesSentOverChannels = 0;

				try {
					
					// Count the messages over the number of threads on each channel
					for (PCFMessage pcfM : pcfResp) {
						long msgs = pcfM.getIntParameterValue(MQConstants.MQIACH_MSGS);		
						msgsOverChannels += msgs;
					}
					
					//long msgs = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_MSGS);										
					AtomicLong r = msgsReceived.get(channelName);
					if (r == null) {
						msgsReceived.put(channelName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("messagesReceived").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								, new AtomicLong(msgsOverChannels)));
					} else {
						r.set(msgsOverChannels);
					}
				} catch (Exception e) {
					AtomicLong r = msgsReceived.get(channelName);
					if (r == null) {
						msgsReceived.put(channelName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("messagesReceived").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								, new AtomicLong(0)));
					} else {
						r.set(0);
					}
					
				}
				
				
				try {
					// Count the messages over the number of threads on each channel
					for (PCFMessage pcfM : pcfResp) {
						int bytes = pcfM.getIntParameterValue(MQConstants.MQIACH_BYTES_RECEIVED);										
						bytesReceviedOverChannels += bytes;
					}

					//long msgs = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_MSGS);										
					AtomicInteger r = bytesReceived.get(channelName);
					if (r == null) {
						bytesReceived.put(channelName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("bytesReceived").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								, new AtomicInteger(bytesReceviedOverChannels)));
					} else {
						r.set(bytesReceviedOverChannels);
					}
				} catch (Exception e) {
					AtomicInteger r = bytesReceived.get(channelName);
					if (r == null) {
						bytesReceived.put(channelName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("bytesReceived").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								, new AtomicInteger(0)));
					} else {
						r.set(0);
					}
					
				}

				try {
					// Count the messages over the number of threads on each channel
					for (PCFMessage pcfM : pcfResp) {
						int bytes = pcfM.getIntParameterValue(MQConstants.MQIACH_BYTES_SENT);
						bytesSentOverChannels += bytes;
					}

					//long msgs = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_MSGS);										
					AtomicInteger r = bytesSent.get(channelName);
					if (r == null) {
						bytesSent.put(channelName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("bytesSent").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								, new AtomicInteger(bytesSentOverChannels)));
					} else {
						r.set(bytesSentOverChannels);
					}
				} catch (Exception e) {
					AtomicInteger r = bytesSent.get(channelName);
					if (r == null) {
						bytesSent.put(channelName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("bytesSent").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"channelType", channelType,
										"channelName", channelName,
										"cluster", channelCluster
										)
								, new AtomicInteger(0)));
					} else {
						r.set(0);
					}
					
				}
				
			}
		}		
	}

	/**
	 * Get queue metrics
	 * 
	 * @throws MQException
	 * @throws IOException
	 * @throws MQDataException 
	 */
	private void UpdateQueueMetrics() throws MQException, IOException, MQDataException {

		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q);
		pcfRequest.addParameter(MQConstants.MQCA_Q_NAME, "*");
		pcfRequest.addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_ALL);		
        PCFMessage[] pcfResponse = this.messageAgent.send(pcfRequest);
			        
		for (PCFMessage pcfMsg : pcfResponse) {
			String queueName = null;
			try {
				queueName = pcfMsg.getStringParameterValue(MQConstants.MQCA_Q_NAME).trim();
				int qType = pcfMsg.getIntParameterValue(MQConstants.MQIA_Q_TYPE);
				String queueType = GetQueueType(qType);
				
				int qUsage = pcfMsg.getIntParameterValue(MQConstants.MQIA_USAGE);
				String queueUsage = "Normal";
				if (qUsage != MQConstants.MQUS_NORMAL) {
					queueUsage = "Transmission";
				}
				if (checkQueueNames(queueName)) {
					PCFMessage pcfInqStat = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS);	
					pcfInqStat.addParameter(MQConstants.MQCA_Q_NAME, queueName);
					//int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
					//pcfInqStat.addParameter(MQConstants.MQIACF_Q_STATUS_ATTRS, pcfParmAttrs);
					pcfInqStat.addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_STATUS);					
					PCFMessage[] pcfResStat = this.messageAgent.send(pcfInqStat);

					PCFMessage pcfReset = new PCFMessage(MQConstants.MQCMD_RESET_Q_STATS);
					pcfReset.addParameter(MQConstants.MQCA_Q_NAME, queueName);
					PCFMessage[] pcfResResp = this.messageAgent.send(pcfReset);
					
					int value = pcfMsg.getIntParameterValue(MQConstants.MQIA_CURRENT_Q_DEPTH);
					String queueCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();

					AtomicInteger i = queueDepMap.get(queueName);
					if (i == null) {
						queueDepMap.put(queueName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("queueDepth").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(value)));
					} else {
						i.set(value);
					}
					

					int openInvalue = pcfMsg.getIntParameterValue(MQConstants.MQIA_OPEN_INPUT_COUNT);
					AtomicInteger inC = queueOpenInMap.get(queueName);
					if (inC == null) {
						queueOpenInMap.put(queueName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("openInputCount").toString(),
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(openInvalue)));
					} else {
						inC.set(openInvalue);
					}
					
					int openOutvalue = pcfMsg.getIntParameterValue(MQConstants.MQIA_OPEN_OUTPUT_COUNT);
					AtomicInteger outC = queueOpenOutMap.get(queueName);
					if (outC == null) {
						queueOpenOutMap.put(queueName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("openOutputCount").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(openOutvalue)));
					} else {
						outC.set(openOutvalue);
					}

					if ((openInvalue > 0) || (openOutvalue > 0) ) {
					//	ProcessQueueHandlers(queueName);
						
					}
					
					value = pcfMsg.getIntParameterValue(MQConstants.MQIA_MAX_Q_DEPTH);
					AtomicInteger maxqd = maxQueueDepthMap.get(queueName);
					if (maxqd == null) {
						maxQueueDepthMap.put(queueName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("maxQueueDepth").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(value)));
					} else {
						maxqd.set(value);
					}
					
					
					// for dates / time - the queue manager or queue monitoring must be at least 'low'
					// MQMON_OFF 	- Monitoring data collection is turned off
					// MQMON_NONE	- Monitoring data collection is turned off for queues, regardless of their QueueMonitor attribute
					// MQMON_LOW	- Monitoring data collection is turned on, with low ratio of data collection
					// MQMON_MEDIUM	- Monitoring data collection is turned on, with moderate ratio of data collection
					// MQMON_HIGH	- Monitoring data collection is turned on, with high ratio of data collection
					if (!((getQueueMonitoringFromQmgr() == MQConstants.MQMON_OFF) 
							|| (getQueueMonitoringFromQmgr() == MQConstants.MQMON_NONE))) {
						String lastGetDate = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_GET_DATE);
						String lastGetTime = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_GET_TIME);
						if (!(lastGetDate.equals(" ") && lastGetTime.equals(" "))) {
							Date dt = formatter.parse(lastGetDate);
							long ld = dt.getTime() / (24*60*60*1000);	
							long hrs = Integer.parseInt(lastGetTime.substring(0, 2));
							long min = Integer.parseInt(lastGetTime.substring(3, 5));
							long sec = Integer.parseInt(lastGetTime.substring(6, 8));
							long seconds = sec + (60 * min) + (3600 * hrs);
							ld *= 86400;
							ld += seconds;
							AtomicLong lgd = queueLGDMap.get(queueName);
							if (lgd == null) {
								queueLGDMap.put(queueName, Metrics.gauge(new StringBuilder()
										.append(MQPREFIX)
										.append("lastGetDateTime").toString(), 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster,
												"type", "timestamp"
												),
										new AtomicLong(ld)));
							} else {
								lgd.set(ld);
							}							
						}
	
						String lastPutDate = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_PUT_DATE);
						String lastPutTime = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_PUT_TIME);
						if (!(lastPutDate.equals(" ") && lastPutTime.equals(" "))) {
							Date dt = formatter.parse(lastPutDate);
							long ld = dt.getTime() / (24*60*60*1000);	
							long hrs = Integer.parseInt(lastPutTime.substring(0, 2));
							long min = Integer.parseInt(lastPutTime.substring(3, 5));
							long sec = Integer.parseInt(lastPutTime.substring(6, 8));
							long seconds = sec + (60 * min) + (3600 * hrs);
							ld *= 86400;
							ld += seconds;
							AtomicLong lpd = queueLPDMap.get(queueName);
							if (lpd == null) {
								queueLPDMap.put(queueName, Metrics.gauge(new StringBuilder()
										.append(MQPREFIX)
										.append("lastPutDateTime").toString(), 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster,
												"type", "timestamp"
												),
										new AtomicLong(ld)));
							} else {
								lpd.set(ld);
							}							
						}										
						
						int old = pcfResStat[0].getIntParameterValue(MQConstants.MQIACF_OLDEST_MSG_AGE);
						AtomicLong age = queueAgeMap.get(queueName);
						if (age == null) {
							queueAgeMap.put(queueName, Metrics.gauge(new StringBuilder()
									.append(MQPREFIX)
									.append("oldestMsgAge").toString(), 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster,
											"type", "seconds"
											),
									new AtomicLong(old)));
						} else {
							age.set(old);
						}							


					}

					
					//
					int devalue = pcfResResp[0].getIntParameterValue(MQConstants.MQIA_MSG_DEQ_COUNT);
					AtomicInteger d = queueDeqMap.get(queueName);
					if (d == null) {
						queueDeqMap.put(queueName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("deQueued").toString(),
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(devalue)));
					} else {
						d.set(devalue);
					}

					int envalue = pcfResResp[0].getIntParameterValue(MQConstants.MQIA_MSG_ENQ_COUNT);
					AtomicInteger e = queueEnqMap.get(queueName);
					if (e == null) {
						queueEnqMap.put(queueName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("enQueued").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(envalue)));
					} else {
						e.set(envalue);
					}					
				}
				
			} catch (Exception e) {
				// do nothing
			}
		}
	}

	/**
	 * **** NOT YET USED ****
	 * When the queue has either openInputCounts or openOutputCounts, then get the
	 *    connection handles that are connected to the queue
	 * @param queueName
	 * @throws MQException
	 * @throws IOException
	 * @throws MQDataException 
	 */
	private void ProcessQueueHandlers(String queueName ) throws MQException, IOException, MQDataException {
		
		PCFMessage pcfInqHandle = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS);	
		pcfInqHandle.addParameter(MQConstants.MQCA_Q_NAME, queueName);
		pcfInqHandle.addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_HANDLE);					
		PCFMessage[] pcfResHandle = this.messageAgent.send(pcfInqHandle);

		int iCount = 0;
		for (PCFMessage pcfMsg : pcfResHandle) {
			int seqNo = pcfMsg.getMsgSeqNumber();
			StringBuilder sb = new StringBuilder()
					.append(queueName.trim())
					.append(seqNo);
						
			Map<String, AtomicInteger>flowMap 
				= collectionqueueHandleMaps.get(sb.toString());

			if (flowMap == null) {
				Map<String, AtomicInteger>stats 
						= new HashMap<String,AtomicInteger>();
				collectionqueueHandleMaps.put(sb.toString(), stats);
			}
			
			int value = pcfMsg.getIntParameterValue(MQConstants.MQIACF_HANDLE_STATE);
			String conn = pcfMsg.getStringParameterValue(MQConstants.MQCACH_CONNECTION_NAME).trim();
			String appName = 
					pcfMsg.getStringParameterValue(MQConstants.MQCACF_APPL_TAG).trim();
			

			Map<String,AtomicInteger>queHand = collectionqueueHandleMaps
						.get(sb.toString());
			AtomicInteger i = queHand.get(sb.toString());
			if (i == null) {
				queHand.put(sb.toString(), Metrics.gauge(new StringBuilder()
						.append(MQPREFIX)
						.append("queueHandles").toString(), 
						Tags.of("queueManagerName", this.queueManager,
								"queueName", queueName,
								"connection",conn,
								"appName",appName,
								"sequence", Integer.toString(seqNo)
								),
						new AtomicInteger(value)));
			} else {
				i.set(value);
			}
			iCount++;
			
		}
		
		//for (int iHCount = iCount; iHCount < 10; iHCount++) {
		iCount++;
		StringBuilder sb = new StringBuilder()
				.append(queueName.trim())
				.append(iCount);

		Map<String, AtomicInteger>flowMap = collectionqueueHandleMaps.get(sb.toString());

		if (flowMap != null) {
			Map<String,AtomicInteger>queHand = collectionqueueHandleMaps.get(sb.toString());
			queHand.remove(sb.toString());
			collectionqueueHandleMaps.remove(sb.toString());
		}
			
		// Get MQIACF_HANDLE_STATE
		// https://www.ibm.com/support/knowledgecenter/en/SSFKSJ_9.0.0/com.ibm.mq.ref.adm.doc/q129080_.html
		
	}
	/**
	 * Return the channel type
	 * 
	 * @param chlType
	 * @return
	 */
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
	/**
	 * Get the queue type
	 * 
	 * @param qType
	 * @return
	 */
	private String GetQueueType(int qType) {

		String queueType = "";
		switch (qType) {
			case MQConstants.MQQT_ALIAS:
			{
				queueType = "Alias";
				break;
			}
			case MQConstants.MQQT_LOCAL:
			{
				queueType = "Local";
				break;
			}
			case MQConstants.MQQT_REMOTE:
			{
				queueType = "Remote";
				break;
			}
			case MQConstants.MQQT_MODEL:
			{
				queueType = "Model";
				break;
			}
			case MQConstants.MQQT_CLUSTER:
			{
				queueType = "Cluster";
				break;
			}
			
			default:
			{
				queueType = "Local";
				break;
			}
		}

		return queueType;
	}

	/**
	 * Check queue names
	 * 
	 * @param name
	 * @return boolean
	 */	
	private boolean checkQueueNames(String name) {

		// Exclude ...
		for (String s : this.excludeQueues) {
			if (s.equals("*")) {
				break;
			} else {
				if (name.startsWith(s)) {
					return false;
				}
			}
		}
	
		// Check queues against the list 
		for (String s : this.includeQueues) {
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

	
	/**
	 * Return true if its the correct values again the Channel ...
	 * ... otherwise, return false
	 * 
	 * @param name
	 * @return boolean
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
	
	/**
	 * Return true if its the correct values again the Listener ...
	 * ... otherwise, return false
	 * 
	 * @param name
	 * @return
	 */
	private boolean checkListenNames(String name) {

		// Exclude ...
		for (String s : this.excludeListeners) {
			if (s.equals("*")) {
				break;
			} else {
				if (name.startsWith(s)) {
					return false;
				}
			}
		}
	
		// Check listeners against the list 
		for (String s : this.includeListeners) {
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

	/**
	 * Return true if its the correct ListenerType what we are looking for ...
	 * ... otherwise, return false
	 * 
	 * @param type
	 * @return
	 */
	private boolean checkType(String type) {

		// Exclude ...
		for (String s : this.excludeTypes) {
			if (s.equals("*")) {
				break;
			} else {
				if (type.startsWith(s)) {
					return false;
				}
			}
		}

		// Check listeners against the list 
		for (String s : this.includeTypes) {
			if (s.equals("*")) {
				return true;
			} else {
				if (type.startsWith(s)) {
					return true;
				}				
			}
		}		
		
		return false;
	}
	
	
	/**
	 * Disconnect cleanly from the queue manager
	 */
    @PreDestroy
    public void CloseQMConnection() {
    	
    	try {
	    	if (this.messageAgent != null) {
	    		log.info("Closing agent ");
	        	this.messageAgent.disconnect();
	    	}
    	} catch (Exception e) {
    		// do nothing
    	}
    }
	
        
}


