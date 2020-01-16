package maersk.com.mq.metrics.mqmetrics;

/*
 * Copyright 2019
 * Mick Moriarty - Maersk
 *
 * Connect to a queue manager
 * 
 * 22/10/2019 - Capture the return code when the queue manager throws an error so multi-instance queue
 *              managers can be checked
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.validation.constraints.Null;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.ibm.mq.MQEnvironment;
import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import com.ibm.mq.headers.pcf.PCFAgent;
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
//import maersk.com.mq.metricsummary.Channels;
//import maersk.com.mq.metricsummary.Channel;
import maersk.com.mq.metricsummary.MQMetricSummary;
import maersk.com.mq.pcf.channel.pcfChannel;

@Component
public class MQConnection extends MQBase {

    static Logger log = Logger.getLogger(MQConnection.class);

	//
	private boolean onceOnly = true;
	
	// taken from connName
	private String hostName;

	@Value("${ibm.mq.multiInstance:false}")
	private Boolean multiInstance;

	@Value("${ibm.mq.queueManager}")
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
	
	@Value("${ibm.mq.local:false}")
	private boolean local;

	@Value("${ibm.mq.keepMetricsWhenQueueManagerIsDown:false}")
	private boolean keepMetricsWhenQueueManagerIsDown;
	
	//
	@Value("${ibm.mq.useSSL:false}")
	private boolean bUseSSL;
	
	@Value("${ibm.mq.security.truststore:}")
	private String truststore;
	@Value("${ibm.mq.security.truststore-password:}")
	private String truststorepass;
	@Value("${ibm.mq.security.keystore:}")
	private String keystore;
	@Value("${ibm.mq.security.keystore-password:}")
	private String keystorepass;
	
    //MQ reset
    @Value("${ibm.mq.event.delayInMilliSeconds:10000}")
    private long resetIterations;

    private MQQueueManager queManager = null;
    private PCFMessageAgent messageAgent = null;
    private PCFAgent agent = null;
    
    //
    private Map<String,AtomicInteger>runModeMap = new HashMap<String,AtomicInteger>();
	protected static final String runMode = MQPREFIX + "runMode";

    //
    @Autowired
    public pcfQueueManager pcfQueueManager;
    @Autowired
    public pcfListener pcfListener;
    @Autowired
    public pcfQueue pcfQueue;
    @Autowired
    public pcfChannel pcfChannel;
    
    public MQMetricSummary metricSummary;    

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
    
    @Bean("MetricsSummary")
    public MQMetricSummary metricSummary() {
    	this.metricSummary = new MQMetricSummary();
    	return this.metricSummary;
    }

    //@PostConstruct

    @Bean
    @DependsOn("MetricsSummary")
    public pcfChannel Channel() {
    	return new pcfChannel(this.metricSummary);
    }

	// Constructor
	private MQConnection() {
	}
	

	/*
	 * Every 'x' seconds, start the processing to get the MQ metrics
	 */
	@Scheduled(fixedDelayString="${ibm.mq.event.delayInMilliSeconds}")
    public void scheduler() {
	
		resetIterations();

		try {
			if (this.messageAgent != null) {
				checkQueueManagerCluster();
				updateQMMetrics();
				updateListenerMetrics();
				updateQueueMetrics();
				updateChannelMetrics();
				
			} else {
				if (this._debug) { log.error("No MQ queue manager object"); }
				createQueueManagerConnection();
				setPCFParameters();

			}
			
		} catch (PCFException p) {
			if (!this.multiInstance) {
				log.error("PCFException " + p.getMessage());
			}
			log.debug("PCFException: ReasonCode " + p.getReason());
			closeQMConnection();
			queueManagerIsNotRunning(p.getReason());
			
		} catch (MQException m) {
			if (!this.multiInstance) {
				log.error("MQException " + m.getMessage());
			}
			closeQMConnection();
			queueManagerIsNotRunning(m.getReason());
			this.messageAgent = null;
			
		} catch (IOException i) {
			if (!this.multiInstance) {
				log.error("IOException " + i.getMessage());
			}
			closeQMConnection();
			queueManagerIsNotRunning(0);
			
		} catch (Exception e) {
			if (!this.multiInstance) {
				log.error("Exception " + e.getMessage());
			}
			closeQMConnection();
			queueManagerIsNotRunning(0);
		}
    }
    
	// Set the MQ Objects parameters
	private void setPCFParameters() {
		this.pcfQueueManager.setMessageAgent(this.messageAgent, this.multiInstance);
		this.pcfListener.setMessageAgent(this.messageAgent);
		this.pcfQueue.setMessageAgent(this.messageAgent);
		this.pcfChannel.setMessageAgent(this.messageAgent);
		
	}

	/*
	 * Create an MQ connection to the queue manager
	 * ... once connected, create a messageAgent for PCF commands
	 * 
	 * @throws MQException
	 * @throws MQDataException 
	 */
	private void createQueueManagerConnection() throws MQException, MQDataException {
		
		setRunMode();
		
		Hashtable<String, Comparable> env = null;
		
		if (!this.local) { 
			getEnvironmentVariables();
			log.info("Attempting to connect using a client connection");

			env = new Hashtable<String, Comparable>();
			env.put(MQConstants.HOST_NAME_PROPERTY, this.hostName);
			env.put(MQConstants.CHANNEL_PROPERTY, this.channel);
			env.put(MQConstants.PORT_PROPERTY, this.port);
			
			/*
			 * 
			 * If a username and password is provided, then use it
			 * ... if CHCKCLNT is set to OPTIONAL or RECDADM
			 * ... RECDADM will use the username and password if provided ... if a password is not provided
			 * ...... then the connection is used like OPTIONAL
			 */
			
			if (!StringUtils.isEmpty(this.userId)) {
				env.put(MQConstants.USER_ID_PROPERTY, this.userId); 
				log.info("USER_ID_PROPERTY: " + this.userId);
			}
			if (!StringUtils.isEmpty(this.password)) {
				env.put(MQConstants.PASSWORD_PROPERTY, this.password);
				log.info("PASSWORD_PROPERTY: " + this.password);
			}
			env.put(MQConstants.TRANSPORT_PROPERTY,MQConstants.TRANSPORT_MQSERIES);
	
			if (this.multiInstance) {
				if (this.onceOnly) {
					log.info("MQ Metrics is running in multiInstance mode");
				}
			}
			
			if (this._debug) {
				log.info("Host		: " + this.hostName);
				log.info("Channel	: " + this.channel);
				log.info("Port		: " + this.port);
				log.info("Queue Man	: " + this.queueManager);
				log.info("User		: " + this.userId);
				log.info("Password	: **********");
				if (this.bUseSSL) {
					log.info("SSL is enabled ....");
				}
			}
			
			// If SSL is enabled (default)
			if (this.bUseSSL) {
				if (!StringUtils.isEmpty(this.truststore)) {
					System.setProperty("javax.net.ssl.trustStore", this.truststore);
			        System.setProperty("javax.net.ssl.trustStorePassword", this.truststorepass);
			        System.setProperty("javax.net.ssl.trustStoreType","JKS");
			        System.setProperty("com.ibm.mq.cfg.useIBMCipherMappings","false");
				}
				if (!StringUtils.isEmpty(this.keystore)) {
			        System.setProperty("javax.net.ssl.keyStore", this.keystore);
			        System.setProperty("javax.net.ssl.keyStorePassword", this.keystorepass);
			        System.setProperty("javax.net.ssl.keyStoreType","JKS");
				}
				if (!StringUtils.isEmpty(this.cipher)) {
					env.put(MQConstants.SSL_CIPHER_SUITE_PROPERTY, this.cipher);
				}
			
			} else {
				if (this._debug) {
					log.info("SSL is NOT enabled ....");
				}
			}
			
	        //System.setProperty("javax.net.debug","all");
			if (this._debug) {
				if (!StringUtils.isEmpty(this.truststore)) {
					log.info("TrustStore       : " + this.truststore);
					log.info("TrustStore Pass  : ********");
				}
				if (!StringUtils.isEmpty(this.keystore)) {
					log.info("KeyStore         : " + this.keystore);
					log.info("KeyStore Pass    : ********");
					log.info("Cipher Suite     : " + this.cipher);
				}
			}
		} else {
			if (this._debug) {
				log.info("Attemping to connect using local bindings");
				log.info("Queue Man	: " + this.queueManager);
			}
			
		}
		
		if (this.onceOnly) {
			log.info("Attempting to connect to queue manager " + this.queueManager);
			this.onceOnly = false;
		}
		
		/*
		 * Connect to the queue manager 
		 * ... local connection : application connection in local bindings
		 * ... client connection: application connection in client mode 
		 */
		if (this.queManager == null) {
			if (this.local) {
				this.queManager = new MQQueueManager(this.queueManager);
				log.info("Local connection established ");
			} else {
				this.queManager = new MQQueueManager(this.queueManager, env);
			}
			log.info("Connection to queue manager established ");
			
		} else {
			log.info("Connection to queue manager is already established ");
		}

		/*
		 * Establish a PCF agent
		 */
		log.info("Creating PCFAgent ");
		if (this.messageAgent == null) {
			this.messageAgent = new PCFMessageAgent(queManager);
			log.info("PCF agent to  " + this.queueManager + " established.");
		} else {
			log.info("PCFAgent is already established ");
			
		}
	}

	// Set Run mode
	private void setRunMode() {

		int mode = 0;
		if (!this.local) {
			mode = 1;
		}
		
		AtomicInteger rMode = runModeMap.get(runMode);
		if (rMode == null) {
			runModeMap.put(runMode, meterRegistry.gauge(runMode, 
					Tags.of("queueManagerName", this.queueManager),
					new AtomicInteger(mode))
					);
		} else {
			rMode.set(mode);
		}
		
		
	}
	/*
	 * Get MQ details from environment variables
	 */
	private void getEnvironmentVariables() {
		
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

		/*
		 * If we dont have a user or a certs are not being used, then we cant connect ... unless we are in local bindings
		 */
		if (this.userId.equals("")) {
			if (this.bUseSSL == false) {
				log.error("Unable to connect to queue manager, credentials are missing and certificates are not being used");
				System.exit(1);
			}
		}

		// if no use, for get it ...
		if (this.userId == null) {
			return;
		}

		/*
		 * dont allow mqm user
		 */
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
	private void queueManagerIsNotRunning(int status) {

		if (this.pcfQueueManager != null) {
			this.pcfQueueManager.NotRunning(this.queueManager, this.multiInstance, status);
		}

		/*
		 * Clear the metrics, but ...
		 * ... dont clear them if the queue manager is down 
		 */
		if (!keepMetricsWhenQueueManagerIsDown) {
			if (this.pcfListener != null) {
				this.pcfListener.resetMetrics();
			}
			if (this.pcfChannel != null) {
				this.pcfChannel.resetMetrics();
			}
			if (this.pcfChannel != null) {
				this.pcfChannel.resetMetrics();			
			}
			if (this.pcfQueue != null) {
				this.pcfQueue.resetMetrics();			
			}
		}
	}

	/*
	 * Reset iterations value between capturing performance metrics
	 */
	private void resetIterations() {
		
		this.pcfQueueManager.ResetIteration(this.queueManager);
			
	}
	
	/*
	 * 
	 * Check if the queue manager belongs to a cluster ...
	 * 
	 */
	private void checkQueueManagerCluster() {

		this.pcfQueueManager.CheckQueueManagerCluster();
				
	}
	
	/*
	 * Update the queue manager metrics
	 * 
	 */
	private void updateQMMetrics() throws PCFException, 
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
	private void updateListenerMetrics() throws MQException, 
		IOException, 
		MQDataException {

		this.pcfListener.UpdateListenerMetrics();
		
		
	}
		
	/*
	 * Update the Channel Metrics
	 * 
	 */
	private void updateChannelMetrics() throws MQException, IOException, 
		PCFException, 
		MQDataException, 
		ParseException {
		
		this.pcfChannel.updateChannelMetrics();
		
	}

	/*
	 * Update queue metrics
	 * 
	 */
	private void updateQueueMetrics() throws MQException, 
		IOException, 
		MQDataException {

		this.pcfQueue.updateQueueMetrics();
				
	}
	
	/*
	 * Disconnect cleanly from the queue manager
	 */
    @PreDestroy
    public void closeQMConnection() {

    	try {
    		if (this.queManager.isConnected()) {
	    		if (this._debug) { log.info("Closing MQ Connection "); }
    			this.queManager.disconnect();
    		}
    	} catch (Exception e) {
    		// do nothing
    	}
    	
    	try {
	    	if (this.messageAgent != null) {
	    		if (this._debug) { log.info("Closing PCF agent "); }
	        	this.messageAgent.disconnect();
	    	}
    	} catch (Exception e) {
    		// do nothing
    	}
    	
    	this.queManager = null;
		this.messageAgent = null;
		
    }
	
        
}


