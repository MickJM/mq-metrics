package monitor.mq.metrics.mqmetrics;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import io.micrometer.core.instrument.Tags;

@Component
public class MQMetricsQueueManager {

	private final static Logger log = LoggerFactory.getLogger(MQMetricsQueueManager.class);
				
	private boolean onceonly = true;
	public void OnceOnly(boolean v) {
		this.onceonly = v;
	}
	public boolean OnceOnly() {
		return this.onceonly;
	}
	
	// taken from connName
	private String hostname;
	public void HostName(String v) {
		this.hostname = v;
	}
	public String HostName() { return this.hostname; }
	
	@Value("${ibm.mq.queueManager}")
	private String queuemanagername;
	public void QueueManagerName(String v) {
		this.queuemanagername = v;
	}
	public String QueueManagerName() { return this.queuemanagername; }
	
	// hostname(port)
	@Value("${ibm.mq.connName}")
	private String[] connname;
	public void ConnName(String[] v) {
		this.connname = v;
	}
	public String[] ConnName() { return this.connname; }
	
	@Value("${ibm.mq.channel}")
	private String channel;
	public void ChannelName(String v) {
		this.channel = v;
	}
	public String ChannelName() { return this.channel; }

	// taken from connName
	private int port;
	public void Port(int v) {
		this.port = v;
	}
	public int Port() { return this.port; }

	@Value("${ibm.mq.user:#{null}}")
	private String userid;
	public void UserId(String v) {
		this.userid = v;
	}
	public String UserId() { return this.userid; }

	@Value("${ibm.mq.password:#{null}}")
	private String password;
	public void Password(String v) {
		this.password = v;
	}
	public String Password() { return this.password; }

	// MQ Connection Security Parameter
	@Value("${ibm.mq.authenticateUsingCSP:true}")
	private boolean authCSP;
	public boolean MQCSP() {
		return this.authCSP;
	}
	
	@Value("${ibm.mq.sslCipherSpec:#{null}}")
	private String cipher;
	private String CipherSpec() {
		return this.cipher;
	}

	@Value("${ibm.mq.ibmCipherMappings:false}")
	private String ibmCipherMappings;
	private String IBMCipherMappings() {
		return this.ibmCipherMappings;
	}
	
	@Value("${ibm.mq.useSSL:false}")
	private boolean usessl;
	public boolean UsingSSL() {
		return this.usessl;
	}
	public void UsingSSL(boolean v) {
		this.usessl = v;
	}
	
	@Value("${ibm.mq.security.truststore:#{null}}")
	private String truststore;
	public String TrustStore() {
		return this.truststore;
	}
	@Value("${ibm.mq.security.truststore-password:#{null}}")
	private String truststorepass;
	public String TrustStorePass() {
		return this.truststorepass;
	}

	@Value("${ibm.mq.security.keystore:#{null}}")
	private String keystore;
	public String KeyStore() {
		return this.keystore;
	}

	@Value("${ibm.mq.security.keystore-password:#{null}}")
	private String keystorepass;
	public String KeyStorePass() {
		return this.keystorepass;
	}
	
	@Value("${ibm.mq.multiInstance:false}")
	private boolean multiInstance;
	public boolean isMultiInstance() {
		return this.multiInstance;
	}
	
	@Value("${ibm.mq.ccdtFile:#{null}}")
	private String ccdtfile;
	public String CCDTFile() {
		return this.ccdtfile;
	}	
	
	@Value("${ibm.mq.local:false}")
	private boolean local;
	public boolean RunningLocal() {
		return this.local;
	}
			
	@Value("${ibm.mq.pcf.browse:false}")
	private boolean pcfBrowse;	
	public boolean Browse() {
		return this.pcfBrowse;
	}
	public void Browse(boolean v) {
		this.pcfBrowse = v;
	}
	
	@Value("${info.app.version:}")
	private String appversion;	
	public String VersionNumeric() {
		return this.appversion;
	}
	
	@Value("${info.app.name:MQMonitor}")
	private String appName;	
	public String AppName() {
		return this.appName;
	}
	
	private int statType;
	private void StatType(int v) {
		this.statType = v;
	}
	private int StatType() {
		return this.statType;
	}
	
	/*
     *  MAP details for the metrics
     */
    private Map<String,AtomicInteger>runModeMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>versionMap = new HashMap<String,AtomicInteger>();

    private String runMode = "mq:runMode";
    private String version = "mq:monitoringVersion";
	
	/*
	 * Validate connection name and userID
	 */
	private boolean validConnectionName() {
		return (ConnName().equals(""));
	}
	private boolean validateUserId() {
		return (UserId().equals(""));		
	}
	private boolean validateUserId(String v) {
		boolean ret = false;
		if (UserId().equals(v)) {
			ret = true;
		}
		return ret;
	}
	
    private MQQueueManager queManager;
    public void QueueManagerObject(MQQueueManager qm) {
    	this.queManager = qm;
    }
    public MQQueueManager QueueManagerObject() {
    	return this.queManager;
    }

    private MQQueue queue = null;
    public void Queue(MQQueue q) {
    	this.queue = q;
    }
    public MQQueue Queue() {
    	return this.queue;
    }
    
    private MQGetMessageOptions gmo = null;
    public void GetMessageOptions(MQGetMessageOptions gmo) {
    	this.gmo = gmo;
    }
    public MQGetMessageOptions GetMessageOptions() {
    	return this.gmo;
    }
    
	private int qmgrAccounting;
	public synchronized void Accounting(int v) {		
		this.qmgrAccounting = v;
	}
	public synchronized int Accounting() {		
		return this.qmgrAccounting;
	}
	private int stats;
	public synchronized void QueueManagerStatistics(int v) {
		this.stats = v;
	}
	public synchronized int QueueManagerStatistics() {
		return this.stats;
	}

    @Autowired
    private MQMonitorBase base;
    
    //@Autowired
    //Environment env;

    /*
     * Constructor
     */
	public MQMetricsQueueManager() {
	}
	
	@PostConstruct
	public void Init() {
		RunMode();
		Version();
		
	}
	
	/*
	 * Select the correct queue manager
	 */
	public MQQueueManager MultipleQueueManagers() throws MalformedURLException, MQException, MQDataException {
	
		int serverId = 0;
		boolean connected = false;
		MQQueueManager qm = null;
		String server = null;
		
		while (!connected) {

			/*
			 * If not running locally, select the server "host and port" from the list
			 * ... if not, the server will be null
			 */
			if (!RunningLocal()) {
				server = connname[serverId];
				log.info("Attepting to connect to server {} {}", server, connname.length);
			}

			try {
				qm = CreateQueueManager(server);
				QueueManagerObject(qm);
				connected = true;
				
			} catch (MQException e) {
				log.warn("Unable to connect to server {}", server);
			
				if (serverId < (connname.length - 1)) {
					if (serverId == 0) {
						serverId = 1;
					}
				} else {
					throw e;
				}
			}
			
		}
		
		return qm;
		
	}
	
	/*
	 * Create an MQQueueManager object
	 */
	@SuppressWarnings("rawtypes")
	private MQQueueManager CreateQueueManager(String server) throws MQException, MQDataException, MalformedURLException {
	
		Hashtable<String, Comparable> env = new Hashtable<String, Comparable>();
		
		/*
		 * If not running locally, then 'server' will contain the host/port
		 */
		if (!RunningLocal()) { 
			
			EnvironmentVariables(server);
			log.info("Attempting to connect using a client connection");

			if ((CCDTFile() == null) || (CCDTFile().isEmpty())) {
				env.put(MQConstants.HOST_NAME_PROPERTY, HostName());
				env.put(MQConstants.CHANNEL_PROPERTY, ChannelName());
				env.put(MQConstants.PORT_PROPERTY, Port());
			}
			
			/*
			 * 
			 * If a username and password is provided, then use it
			 * ... if CHCKCLNT is set to OPTIONAL or RECDADM
			 * ... RECDADM will use the username and password if provided ... if a password is not provided
			 * ...... then the connection is used like OPTIONAL
			 */		
		
			if (!StringUtils.isEmpty(UserId())) {
				env.put(MQConstants.USER_ID_PROPERTY, UserId()); 
				if (!StringUtils.isEmpty(Password())) {
					env.put(MQConstants.PASSWORD_PROPERTY, Password());
				}
				env.put(MQConstants.USE_MQCSP_AUTHENTICATION_PROPERTY, MQCSP());
			} 
			env.put(MQConstants.TRANSPORT_PROPERTY,MQConstants.TRANSPORT_MQSERIES);
			env.put(MQConstants.APPNAME_PROPERTY,AppName());
			if (isMultiInstance()) {
				if (OnceOnly()) {
					log.info("MQ Metrics is running in multiInstance mode");
				}
			}
			
			log.debug("Host       : {}", HostName());
			log.debug("Channel    : {}", ChannelName());
			log.debug("Port       : {}", Port());
			log.debug("Queue Man  : {}", QueueManagerName());
			log.debug("User       : {}", UserId());
			log.debug("Password   : {}", "**********");

			if (TrustStore() != null) {
				UsingSSL(true);
			} 

			if (UsingSSL()) {
				log.debug("SSL is enabled ....");
			}
			
			// If SSL is enabled (default)
			if (UsingSSL()) {
				if (!StringUtils.isEmpty(TrustStore())) {
					System.setProperty("javax.net.ssl.trustStore", TrustStore());
			        System.setProperty("javax.net.ssl.trustStorePassword", TrustStorePass());
			        System.setProperty("javax.net.ssl.trustStoreType","JKS");
			        System.setProperty("com.ibm.mq.cfg.useIBMCipherMappings",IBMCipherMappings());
				}
				if (!StringUtils.isEmpty(KeyStore())) {
			        System.setProperty("javax.net.ssl.keyStore", KeyStore());
			        System.setProperty("javax.net.ssl.keyStorePassword", KeyStorePass());
			        System.setProperty("javax.net.ssl.keyStoreType","JKS");
				}
				if (CipherSpec() != null) {
					if (!StringUtils.isEmpty(CipherSpec())) {
						env.put(MQConstants.SSL_CIPHER_SUITE_PROPERTY, CipherSpec());						
					}
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
		if (RunningLocal()) {
			log.info("Attemping to connect to queue manager " + QueueManagerName() + " using local bindings");
			qmgr = new MQQueueManager(QueueManagerName());
			
		} else {
			/*
			 * If not using a CCDT file
			 */
			if ((CCDTFile() == null) || (CCDTFile().isEmpty())) {
				log.info("Attempting to connect to queue manager " + QueueManagerName());
				qmgr = new MQQueueManager(QueueManagerName(), env);
				
			} else {
				/*
				 * Load the CCDT file and pass it into the MQ API
				 */
				URL ccdtFileName = new URL("file:///" + CCDTFile());
				log.info("Attempting to connect to queue manager " + QueueManagerName() + " using CCDT file");
				qmgr = new MQQueueManager(QueueManagerName(), env, ccdtFileName);
				
			}
		}
		log.info("Connection to queue manager established ");
		
		return qmgr;
	}
	
	/*
	 * Create a PCF agent
	 */	
	public PCFMessageAgent CreateMessageAgent(MQQueueManager queManager) throws MQDataException {
		
		log.info("Attempting to create a PCFAgent ");
		PCFMessageAgent pcfmsgagent = new PCFMessageAgent(queManager);
		log.info("PCFAgent created successfully");
	
		return pcfmsgagent;	
		
	}
	
	/*
	 * Get MQ details from environment variables
	 */
	private void EnvironmentVariables(String server) {
		
		/*
		 * ALL parameter are passed in the application.yaml file ...
		 *    These values can be overrided using an application-???.yaml file per environment
		 *    ... or passed in on the command line
		 */
		
		// Split the host and port number from the connName ... host(port)
		if (!validConnectionName()) {
			Pattern pattern = Pattern.compile("^([^()]*)\\(([^()]*)\\)(.*)$");
			Matcher matcher = pattern.matcher(server);	
			if (matcher.matches()) {
				HostName(matcher.group(1).trim());
				Port(Integer.parseInt(matcher.group(2).trim()));
			
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
			if (!UsingSSL()) {
				log.error("Unable to connect to queue manager, credentials are missing and certificates are not being used");
				System.exit(MQPCFConstants.EXIT_ERROR);
			}
		}

		// if no user, forget it ...
		if (this.userid == null) {
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
			this.userid = null;
			this.password = null;
		}
	}
	
	/*
	 * Set 'runmode'
	 *    LOCAL or CLIENT
	 */
	private void RunMode() {

		int mode = MQPCFConstants.MODE_LOCAL;
		if (!RunningLocal()) {
			mode = MQPCFConstants.MODE_CLIENT;
		}
		
		runModeMap.put(runMode, base.meterRegistry.gauge(runMode, 
				Tags.of("queueManagerName", QueueManagerName()),
				new AtomicInteger(mode)));
	}

	/*
	 * Parse the version
	 */
	private void Version() {

		String s = VersionNumeric().replaceAll("[\\s.]", "");
		int v = Integer.parseInt(s);		
		versionMap.put(version, base.meterRegistry.gauge(version, 
				new AtomicInteger(v)));		
	}
	
	public void CloseConnection(MQQueueManager qm) {

    	try {
    		if (qm.isConnected()) {
	    		log.debug("Closing MQ Connection ");
    			qm.disconnect();
    		}
    	} catch (Exception e) {
    		// do nothing
    	}
		
	}
	/*
	 * Close the connection and PCF agent to the queue manager
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
