package maersk.com.mq.metrics.mqmetrics;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.MQCFGR;
import com.ibm.mq.headers.pcf.MQCFH;
import com.ibm.mq.headers.pcf.MQCFIL;
import com.ibm.mq.headers.pcf.MQCFST;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import com.ibm.mq.headers.pcf.PCFParameter;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import maersk.com.mq.metrics.accounting.AccountingEntity;

@Component
public class MQMetricsQueueManager {

	private final static Logger log = LoggerFactory.getLogger(MQMetricsQueueManager.class);
				
	private boolean onceOnly = true;
	public void setOnceOnly(boolean v) {
		this.onceOnly = v;
	}
	public boolean getOnceOnly() {
		return this.onceOnly;
	}
	
	// taken from connName
	private String hostName;
	public void setHostName(String v) {
		this.hostName = v;
	}
	public String getHostName() { return this.hostName; }
	
	@Value("${ibm.mq.queueManager}")
	private String queueManager;
	public void setQueueManager(String v) {
		this.queueManager = v;
	}
	public String getQueueManagerName() { return this.queueManager; }
	
	// hostname(port)
	@Value("${ibm.mq.connName}")
	private String connName;
	public void setConnName(String v) {
		this.connName = v;
	}
	public String getConnName() { return this.connName; }
	
	@Value("${ibm.mq.channel}")
	private String channel;
	public void setChannelName(String v) {
		this.channel = v;
	}
	public String getChannelName() { return this.channel; }

	// taken from connName
	private int port;
	public void setPort(int v) {
		this.port = v;
	}
	public int getPort() { return this.port; }

	@Value("${ibm.mq.user:#{null}}")
	private String userId;
	public void setUserId(String v) {
		this.userId = v;
	}
	public String getUserId() { return this.userId; }

	@Value("${ibm.mq.password:#{null}}")
	private String password;
	public void setPassword(String v) {
		this.password = v;
	}
	public String getPassword() { return this.password; }

	// MQ Connection Security Parameter
	@Value("${ibm.mq.authenricateUsingCSP:true}")
	private boolean authCSP;
	public boolean getMQCSP() {
		return this.authCSP;
	}
	
	@Value("${ibm.mq.sslCipherSpec}")
	private String cipher;
	
	@Value("${ibm.mq.useSSL:false}")
	private boolean bUseSSL;
	public boolean usingSSL() {
		return this.bUseSSL;
	}
	
	@Value("${ibm.mq.security.truststore:}")
	private String truststore;
	@Value("${ibm.mq.security.truststore-password:}")
	private String truststorepass;
	@Value("${ibm.mq.security.keystore:}")
	private String keystore;
	@Value("${ibm.mq.security.keystore-password:}")
	private String keystorepass;
	
	@Value("${ibm.mq.multiInstance:false}")
	private boolean multiInstance;
	public boolean isMultiInstance() {
		return this.multiInstance;
	}
	
	@Value("${ibm.mq.ccdtFile:#{null}}")
	private String ccdtFile;
	public String getCCDTFile() {
		return this.ccdtFile;
	}	
	
	@Value("${ibm.mq.local:false}")
	private boolean local;
	public boolean isRunningLocal() {
		return this.local;
	}
			
	@Value("${ibm.mq.pcf.browse:false}")
	private boolean pcfBrowse;	
	public boolean getBrowse() {
		return this.pcfBrowse;
	}
	public void setBrowse(boolean v) {
		this.pcfBrowse = v;
	}
	
	@Value("${info.app.version:}")
	private String appversion;	
	public String getVersionNumeric() {
		return this.appversion;
	}
	
	@Value("${info.app.name:MQMonitor}")
	private String appName;	
	public String getAppName() {
		return this.appName;
	}
	
	private int statType;
	private void setStatType(int v) {
		this.statType = v;
	}
	private int getStatType() {
		return this.statType;
	}
	
	/*
     *  MAP details for the metrics
     */
    private Map<String,AtomicInteger>runModeMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>versionMap = new HashMap<String,AtomicInteger>();

    protected static final String runMode = "mq:runMode";
    protected static final String version = "mq:monitoringVersion";
	
	/*
	 * Validate connection name and userID
	 */
	private boolean validConnectionName() {
		return (getConnName().equals(""));
	}
	private boolean validateUserId() {
		return (getUserId().equals(""));		
	}
	private boolean validateUserId(String v) {
		boolean ret = false;
		if (getUserId().equals(v)) {
			ret = true;
		}
		return ret;
	}
	
    private MQQueueManager queManager;
    public void setQmgr(MQQueueManager qm) {
    	this.queManager = qm;
    }
    public MQQueueManager getQmgr() {
    	return this.queManager;
    }

    private MQQueue queue = null;
    public void setQueue(MQQueue q) {
    	this.queue = q;
    }
    public MQQueue getQueue() {
    	return this.queue;
    }
    
    private MQGetMessageOptions gmo = null;
    public void setGMO(MQGetMessageOptions gmo) {
    	this.gmo = gmo;
    }
    public MQGetMessageOptions getGMO() {
    	return this.gmo;
    }
    
	private int qmgrAccounting;
	public synchronized void setAccounting(int v) {		
		this.qmgrAccounting = v;
	}
	public synchronized int getAccounting() {		
		return this.qmgrAccounting;
	}
	private int stats;
	public synchronized void setQueueManagerStatistics(int v) {
		this.stats = v;
	}
	public synchronized int getQueueManagerStatistics() {
		return this.stats;
	}

    @Autowired
    private MQMonitorBase base;
    
    @Autowired
    Environment env;

    /*
     * Constructor
     */
	public MQMetricsQueueManager() {
	}
	
	@PostConstruct
	public void init() {
		setRunMode();
		setVersion();
		
	}
		
	/*
	 * Create an MQQueueManager object
	 */
	@SuppressWarnings("rawtypes")
	public MQQueueManager createQueueManager() throws MQException, MQDataException, MalformedURLException {
		
		Hashtable<String, Comparable> env = new Hashtable<String, Comparable>();
		
		if (!isRunningLocal()) { 
			
			getEnvironmentVariables();
			log.info("Attempting to connect using a client connection");

			if ((getCCDTFile() == null) || (getCCDTFile().isEmpty())) {
				env.put(MQConstants.HOST_NAME_PROPERTY, getHostName());
				env.put(MQConstants.CHANNEL_PROPERTY, getChannelName());
				env.put(MQConstants.PORT_PROPERTY, getPort());
			}
			
			/*
			 * 
			 * If a username and password is provided, then use it
			 * ... if CHCKCLNT is set to OPTIONAL or RECDADM
			 * ... RECDADM will use the username and password if provided ... if a password is not provided
			 * ...... then the connection is used like OPTIONAL
			 */		
		
			if (!StringUtils.isEmpty(getUserId())) {
				env.put(MQConstants.USER_ID_PROPERTY, getUserId()); 
				if (!StringUtils.isEmpty(getPassword())) {
					env.put(MQConstants.PASSWORD_PROPERTY, getPassword());
				}
			}
			env.put(MQConstants.USE_MQCSP_AUTHENTICATION_PROPERTY, getMQCSP());
			env.put(MQConstants.TRANSPORT_PROPERTY,MQConstants.TRANSPORT_MQSERIES);
			env.put(MQConstants.APPNAME_PROPERTY,getAppName());
			
			if (isMultiInstance()) {
				if (getOnceOnly()) {
					log.info("MQ Metrics is running in multiInstance mode");
				}
			}
			
			log.debug("Host		: " + getHostName());
			log.debug("Channel	: " + getChannelName());
			log.debug("Port		: " + getPort());
			log.debug("Queue Man	: " + getQueueManagerName());
			log.debug("User		: " + getUserId());
			log.debug("Password	: **********");
			if (usingSSL()) {
				log.debug("SSL is enabled ....");
			}
			
			// If SSL is enabled (default)
			if (usingSSL()) {
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
				log.debug("SSL is NOT enabled ....");
			}
			
	        //System.setProperty("javax.net.debug","all");
			if (!StringUtils.isEmpty(this.truststore)) {
				log.debug("TrustStore       : " + this.truststore);
				log.debug("TrustStore Pass  : ********");
			}
			if (!StringUtils.isEmpty(this.keystore)) {
				log.debug("KeyStore         : " + this.keystore);
				log.debug("KeyStore Pass    : ********");
				log.debug("Cipher Suite     : " + this.cipher);
			}
		} else {
			log.debug("Attemping to connect using local bindings");
		}
				
		/*
		 * Connect to the queue manager 
		 * ... local connection : application connection in local bindings
		 * ... client connection: application connection in client mode 
		 */
		MQQueueManager qmgr = null;
		if (isRunningLocal()) {
			log.info("Attemping to connect to queue manager " + getQueueManagerName() + " using local bindings");
			qmgr = new MQQueueManager(getQueueManagerName());
			
		} else {
			if ((getCCDTFile() == null) || (getCCDTFile().isEmpty())) {
				log.info("Attempting to connect to queue manager " + getQueueManagerName());
				qmgr = new MQQueueManager(getQueueManagerName(), env);
				
			} else {
				URL ccdtFileName = new URL("file:///" + getCCDTFile());
				log.info("Attempting to connect to queue manager " + getQueueManagerName() + " using CCDT file");
				qmgr = new MQQueueManager(this.queueManager, env, ccdtFileName);
				
			}
		}
		log.info("Connection to queue manager established ");
		setQmgr(qmgr);
		
		return qmgr;
	}
	
	/*
	 * Create a PCF agent
	 */	
	public PCFMessageAgent createMessageAgent(MQQueueManager queManager) throws MQDataException {
		
		log.info("Attempting to create a PCFAgent ");
		PCFMessageAgent pcfmsgagent = new PCFMessageAgent(queManager);
		log.info("PCFAgent created successfully");
	
		return pcfmsgagent;	
		
	}
	
	/*
	 * Get MQ details from environment variables
	 */
	private void getEnvironmentVariables() {
		
		/*
		 * ALL parameter are passed in the application.yaml file ...
		 *    These values can be overrided using an application-???.yaml file per environment
		 *    ... or passed in on the command line
		 */
		
		// Split the host and port number from the connName ... host(port)
		if (!validConnectionName()) {
			Pattern pattern = Pattern.compile("^([^()]*)\\(([^()]*)\\)(.*)$");
			Matcher matcher = pattern.matcher(this.connName);	
			if (matcher.matches()) {
				this.hostName = matcher.group(1).trim();
				this.port = Integer.parseInt(matcher.group(2).trim());
			
			} else {
				log.error("While attempting to connect to a queue manager, the connName is invalid ");
				System.exit(MQPCFConstants.EXIT_ERROR);				
			
			}
			
		} else {
			log.error("While attempting to connect to a queue manager, the connName is missing  ");
			System.exit(MQPCFConstants.EXIT_ERROR);
			
		}

		/*
		 * If we dont have a user or a certs are not being used, then we cant connect ... unless we are in local bindings
		 */
		if (validateUserId()) {
			if (!usingSSL()) {
				log.error("Unable to connect to queue manager, credentials are missing and certificates are not being used");
				System.exit(MQPCFConstants.EXIT_ERROR);
			}
		}

		// if no user, forget it ...
		if (this.userId == null) {
			return;
		}

		/*
		 * dont allow mqm user
		 */
		if (!validateUserId()) {
			if ((validateUserId("mqm") || (validateUserId("MQM")))) {
				log.error("The MQ channel USERID must not be running as 'mqm' ");
				System.exit(MQPCFConstants.EXIT_ERROR);
			}
		} else {
			this.userId = null;
			this.password = null;
		}
	}
	
	/*
	 * Set 'runmode'
	 *    LOCAL or CLIENT
	 */
	private void setRunMode() {

		int mode = MQPCFConstants.MODE_LOCAL;
		if (!isRunningLocal()) {
			mode = MQPCFConstants.MODE_CLIENT;
		}
		
		runModeMap.put(runMode, base.meterRegistry.gauge(runMode, 
				Tags.of("queueManagerName", getQueueManagerName()),
				new AtomicInteger(mode)));
	}

	/*
	 * Parse the version
	 */
	private void setVersion() {

		String s = getVersionNumeric().replaceAll("[\\s.]", "");
		int v = Integer.parseInt(s);		
		versionMap.put(version, base.meterRegistry.gauge(version, 
				new AtomicInteger(v)));		
	}
	
	
	/*
	 * Close the connection to the queue manager
	 */
	public void CloseConnection(MQQueueManager qm, PCFMessageAgent ma) {
		
    	try {
    		if (qm.isConnected()) {
	    		log.debug("Closing MQ Connection ");
    			qm.disconnect();
    		}
    	} catch (Exception e) {
    		// do nothing
    	}
    	
    	try {
	    	if (ma != null) {
	    		log.debug("Closing PCF agent ");
	        	ma.disconnect();
	    	}
    	} catch (Exception e) {
    		// do nothing
    	}
	}

	
}
