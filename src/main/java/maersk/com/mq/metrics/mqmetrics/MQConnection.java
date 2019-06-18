package maersk.com.mq.metrics.mqmetrics;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
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
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.CollectorRegistry;
import maersk.com.mq.pcf.queuemanager.pcfQueueManager;
import maersk.com.mq.pcf.listener.pcfListener;
import maersk.com.mq.pcf.queue.pcfQueue;
import maersk.com.mq.pcf.channel.pcfChannel;

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
	
	@Value("${ibm.mq.security.truststore}")
	private String truststore;
	@Value("${ibm.mq.security.truststore-password}")
	private String truststorepass;
	@Value("${ibm.mq.security.keystore}")
	private String keystore;
	@Value("${ibm.mq.security.keystore-password}")
	private String keystorepass;
	
    //MQ reset
    @Value("${ibm.mq.event.delayInMilliSeconds}")
    private long resetIterations;

	//@Autowired(required = false)
    private PCFMessageAgent messageAgent = null;

    private Map<String,Map<String,AtomicInteger>>collectionqueueHandleMaps 
			= new HashMap<String,Map<String,AtomicInteger>>();
    
    //
    static Logger log = Logger.getLogger(MQConnection.class);

    //
    @Autowired
    public pcfQueueManager pcfQueueManager;
    @Autowired
    public pcfListener pcfListener;
    @Autowired
    public pcfQueue pcfQueue;
    @Autowired
    public pcfChannel pcfChannel;
    
    @Autowired
    public CollectorRegistry registry;
    
    @Bean
    public pcfQueueManager QueueManager() {
    	return new pcfQueueManager();
    }
    
    @Bean
    public pcfListener Listener() {
    	return new pcfListener();
    }
    
    @Bean
    public pcfQueue Queue() {
    	return new pcfQueue();
    }

    @Bean
    public pcfChannel Channel() {
    	return new pcfChannel();
    }

    
	// Constructor
	private MQConnection() {
	}
	

	/*
	 * Every 'x' seconds, start the processing to get the MQ metrics
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
				SetPCFParameters();

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
    
	// Set the MQ Objects parameters
	private void SetPCFParameters() {
		this.pcfQueueManager.setMessageAgent(this.messageAgent);
		this.pcfListener.setMessageAgent(this.messageAgent);
		this.pcfQueue.setMessageAgent(this.messageAgent);
		this.pcfChannel.setMessageAgent(this.messageAgent);
		
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

	/*
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
				log.error("While attempting to connect to a queue manager, the connName is invalid ");
				System.exit(1);				
			}
		} else {
			log.error("While attempting to connect to a queue manager, the connName is missing  ");
			System.exit(1);
			
		}

		// dont allow to run as MQM
		if (this.userId == null) {
			return;
		}
		
		if (!this.userId.equals("")) {
			if ((this.userId.equals("mqm") || (this.userId.equals("MQM")))) {
				log.error("The MQ channel USERID must not be running as 'mqm' ");
				System.exit(1);
			}
		} else {
			this.userId = null;
			this.password = null;
		}
	
	}
	
	/*
	 * When the queue manager isn't running, send back a status of inactive 
	 */
	private void QueueManagerIsNotRunning() {

		this.pcfQueueManager.NotRunning();
		this.pcfListener.NotRunning();
		this.pcfChannel.NotRunning();
		
	}

	/*
	 * Reset iterations value between capturing performance metrics
	 */
	private void ResetIterations() {
		
		this.pcfQueueManager.ResetIteration();
			
	}
	
	/*
	 * 
	 * Check if the queue manager belongs to a cluster ...
	 * 
	 */
	private void CheckQueueManagerCluster() {

		this.pcfQueueManager.CheckQueueManagerCluster();
				
	}
	
	/*
	 * Update the queue manager metrics
	 * 
	 */
	private void UpdateQMMetrics() throws PCFException, 
											MQException, 
											IOException, 
											MQDataException {

		this.pcfQueueManager.UpdateQMMetrics();
		this.pcfQueue.setQueueMonitoringFromQmgr(this.pcfQueueManager.getQueueMonitoringFromQmgr());		
		
	}

	/*
	 * Update the queue manager listener metrics
	 * 
	 */
	private void UpdateListenerMetrics() throws MQException, IOException, MQDataException {

		this.pcfListener.UpdateListenerMetrics();
		
		
	}
		
	/*
	 * Update the Channel Metrics
	 * 
	 */
	private void UpdateChannelMetrics() throws MQException, IOException, PCFException, MQDataException {
		
		this.pcfChannel.UpdateChannelMetrics();
		
	}

	/*
	 * Get queue metrics
	 * 
	 */
	private void UpdateQueueMetrics() throws MQException, IOException, MQDataException {

		this.pcfQueue.UpdateQueueMetrics();
				
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
	
	
	// Not running ...
	public void ClearPrometheusEntries() {
		
		//CollectorRegistry reg = new CollectorRegistry();
		
		Enumeration<MetricFamilySamples> coll = registry.metricFamilySamples();
		while (coll.hasMoreElements()) {
			MetricFamilySamples metric = coll.nextElement();
			//Collector col = (Collector) coll;
			log.info("metric: " + metric.name);
			if (metric.name.startsWith("mq:")) {
		//		registry.unregister(coll);
	
			}
		};
		
		
		/*
		Iterator<MetricFamilySamples> coll = registry.metricFamilySamples().asIterator();
		while (coll.hasNext()) {
			MetricFamilySamples metric = coll.next();
			//Collector col = (Collector) coll;
			log.info("metric: " + metric.name);
			if (metric.name.startsWith("mq:")) {
				coll.remove();
	
			}
		};
		*/
		
	}
	
	
	/*
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


