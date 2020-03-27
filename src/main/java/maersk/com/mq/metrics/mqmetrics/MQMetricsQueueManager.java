package maersk.com.mq.metrics.mqmetrics;

import java.io.IOException;
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

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
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
//import maersk.com.mq.metrics.mqmetrics.MQBase.MQPCFConstants;
import maersk.com.mq.metrics.accounting.AccountingEntity;

@Component
public class MQMetricsQueueManager extends MQBase {

	static Logger log = Logger.getLogger(MQMetricsQueueManager.class);
				
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
	public String getQueueManager() { return this.queueManager; }
	
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

	@Value("${ibm.mq.user}")
	private String userId;
	public void setUserId(String v) {
		this.userId = v;
	}
	public String getUserId() { return this.userId; }

	@Value("${ibm.mq.password}")
	private String password;
	public void setPassword(String v) {
		this.password = v;
	}
	public String getPassword() { return this.password; }
	
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
	
	@Value("${ibm.mq.local:false}")
	private boolean local;
	public boolean isRunningLocal() {
		return this.local;
	}
	
	@Value("${ibm.mq.pcf.values:}")
	private String[] pcfValues;
	
	private int[] searchPCF;
	
    /*
     *  MAP details for the metrics
     */
    private Map<String,AtomicInteger>runModeMap = new HashMap<String,AtomicInteger>();
	protected static final String runMode = MQPREFIX + "runMode";
	
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
	
    private MQQueueManager queManager = null;
    private PCFMessageAgent messageAgent = null;
    
    private MQQueue queue = null;
    private MQGetMessageOptions gmo = null;
    
    /*
     * Constructor
     */
	public MQMetricsQueueManager() {
		log.info("MQMeticQueueManager ...");
	}
	
	/*
	 * Create an MQQueueManager object
	 */
	public MQQueueManager createQueueManager() throws MQException, MQDataException {

		this.searchPCF = new int[this.pcfValues.length];		
		int array = 0;
		for (String w: this.pcfValues) {
			final int x = MQConstants.getIntValue(w);
			this.searchPCF[array] = x;
			array++;		
		}
		Arrays.sort(this.searchPCF);		
		
		Hashtable<String, Comparable> env = null;
		
		if (!isRunningLocal()) { 
			
			getEnvironmentVariables();
			if (getDebugLevel() == LEVEL.INFO) { log.info("Attempting to connect using a client connection"); }
			
			env = new Hashtable<String, Comparable>();
			env.put(MQConstants.HOST_NAME_PROPERTY, getHostName());
			env.put(MQConstants.CHANNEL_PROPERTY, getChannelName());
			env.put(MQConstants.PORT_PROPERTY, getPort());
			
			/*
			 * 
			 * If a username and password is provided, then use it
			 * ... if CHCKCLNT is set to OPTIONAL or RECDADM
			 * ... RECDADM will use the username and password if provided ... if a password is not provided
			 * ...... then the connection is used like OPTIONAL
			 */		
		
			if (!StringUtils.isEmpty(getUserId())) {
				env.put(MQConstants.USER_ID_PROPERTY, getUserId()); 
			}
			if (!StringUtils.isEmpty(this.password)) {
				env.put(MQConstants.PASSWORD_PROPERTY, getPassword());
			}
			env.put(MQConstants.TRANSPORT_PROPERTY,MQConstants.TRANSPORT_MQSERIES);
	
			if (isMultiInstance()) {
				if (getOnceOnly()) {
					if (getDebugLevel() == LEVEL.INFO) { 
						log.info("MQ Metrics is running in multiInstance mode");
					}
				}
			}
			
			if (getDebugLevel() == LEVEL.DEBUG) {
				log.debug("Host		: " + getHostName());
				log.debug("Channel	: " + getChannelName());
				log.debug("Port		: " + getPort());
				log.debug("Queue Man	: " + getQueueManager());
				log.debug("User		: " + getUserId());
				log.debug("Password	: **********");
				if (usingSSL()) {
					log.debug("SSL is enabled ....");
				}
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
				if (getDebugLevel() == LEVEL.DEBUG) {
					log.debug("SSL is NOT enabled ....");
				}
			}
			
	        //System.setProperty("javax.net.debug","all");
			if (getDebugLevel() == LEVEL.DEBUG) {
				if (!StringUtils.isEmpty(this.truststore)) {
					log.debug("TrustStore       : " + this.truststore);
					log.debug("TrustStore Pass  : ********");
				}
				if (!StringUtils.isEmpty(this.keystore)) {
					log.debug("KeyStore         : " + this.keystore);
					log.debug("KeyStore Pass    : ********");
					log.debug("Cipher Suite     : " + this.cipher);
				}
			}
		} else {
			if (getDebugLevel() == LEVEL.DEBUG) {
				log.debug("Attemping to connect using local bindings");
				log.debug("Queue Man	: " + this.queueManager);
			}
			
		}
		
		if (getOnceOnly()) {
			log.info("Attempting to connect to queue manager " + this.queueManager);
			setOnceOnly(false);
		}
		
		/*
		 * Connect to the queue manager 
		 * ... local connection : application connection in local bindings
		 * ... client connection: application connection in client mode 
		 */
		MQQueueManager qmgr = null;
		if (isRunningLocal()) {
			qmgr = new MQQueueManager(this.queueManager);
			log.info("Local connection established ");
		} else {
			qmgr = new MQQueueManager(this.queueManager, env);
		}
		log.info("Connection to queue manager established ");
		
		this.queManager = qmgr;
		
		return qmgr;
	}
	
	/*
	 * Establish a PCF agent
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
		 *    These values can be overrrided using an application-???.yaml file per environment
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
	 */
	private void setRunMode() {

		int mode = MQPCFConstants.MODE_LOCAL;
		if (!isRunningLocal()) {
			mode = MQPCFConstants.MODE_CLIENT;
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
	 * Look for the account information for MAX message size on a queue ...
	 * This is complex ...
	 * 
	 * Open the 'SYSTEM.ADMIN.ACCOUNTING.QUEUE'
	 *    if 'browse', set to browse mode, if 'read' set to read messages
	 *    For each messages on the queue;
	 *        Read the message of the queue (PCF format)
	 *        Loop through each PCF parameter on the message
	 *            Found a PCF Group record (MQCFT_GROUP)
	 *                Loop through each PCF parameter within the group
	 *                    When parameter is MQCA_Q_NAME
	 *                        if SYSTEM or AMQ; break
	 *                    When parameter is MQCA_Q_NAME
	 *                        save the queue name
	 *                    When parameter is MQIAMO_PUT_MAX_BYTES
	 *                        save the values
	 *                        break
	 *                :
	 *            :
	 *        :
	 *        
	 */
	public List<AccountingEntity> readAccountData(String queueName) throws MQDataException, IOException {
		
		// https://github.com/icpchave/MQToolsBox/blob/master/src/cl/continuum/mq/pfc/samples/ReadPCFMessages.java
		log.info("Looking for queue = " + queueName);
		
		if (getDebugLevel() == LEVEL.TRACE) {
			DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
			Date date = new Date();
			log.info("Start accounting processing : " + dateFormat.format(date));
		}
		final List<AccountingEntity> stats = new ArrayList<AccountingEntity>();
		stats.clear();
		
		try {
			if (this.queue == null) {
				this.queue = this.queManager.accessQueue("SYSTEM.ADMIN.ACCOUNTING.QUEUE", 
						MQConstants.MQOO_INPUT_AS_Q_DEF |
						MQConstants.MQOO_BROWSE | 
						MQConstants.MQOO_FAIL_IF_QUIESCING);
				this.gmo = new MQGetMessageOptions();

			}
			
			this.gmo.options = MQConstants.MQGMO_BROWSE_FIRST | 
					MQConstants.MQGMO_NO_WAIT | 
					MQConstants.MQGMO_CONVERT;
			this.gmo.matchOptions = MQConstants.MQMO_MATCH_MSG_ID  | MQConstants.MQMO_MATCH_CORREL_ID;
							
			String pcfQueueName = "";
			int[] pcfArrayValue = {0};
			int pcfType = 0;
			
			MQMessage message = new MQMessage ();

			while (true) {
			
				message.messageId = MQConstants.MQMI_NONE;
				message.correlationId = MQConstants.MQMI_NONE;
				this.queue.get (message, this.gmo);
			
				PCFMessage pcf = new PCFMessage (message);
				Enumeration<PCFParameter> parms = pcf.getParameters();
				boolean foundPCFEntry = false;
				
				msgPCFRecords: while (parms.hasMoreElements()) {
					PCFParameter pcfParams = parms.nextElement();
					
					switch (pcfParams.getParameter()) {

						default:
							switch (pcfParams.getType()) {
								case(MQConstants.MQCFT_GROUP):
									MQCFGR grp = (MQCFGR)pcfParams; // PCF Group record
									Enumeration<PCFParameter> gparms = grp.getParameters();
									
									grpRecords: while (gparms.hasMoreElements()) {
										PCFParameter grpPCFParams = gparms.nextElement();
										
										switch (grpPCFParams.getParameter()) {
											case (MQConstants.MQCA_Q_NAME):
												pcfQueueName = grpPCFParams.getStringValue().trim();
										        if ((pcfQueueName.startsWith("SYSTEM")) 
										        		|| (pcfQueueName.startsWith("AMQ"))) {
										        	break grpRecords; // get next PCF record
										        }
												if (!pcfQueueName.equals(queueName)) {
													pcfQueueName = "";
													break grpRecords; // get next PCF record
												}
												break; // get next group record

											case (MQConstants.MQIAMO_PUT_MAX_BYTES):
												if (Arrays.binarySearch(this.searchPCF, MQConstants.MQIAMO_PUT_MAX_BYTES) >= 0) {

													MQCFIL max = (MQCFIL) grpPCFParams;
													pcfArrayValue = max.getValues();
													if (pcfQueueName != "") {
														pcfType = MQConstants.MQIAMO_PUT_MAX_BYTES;
														
														AccountingEntity ae = new AccountingEntity();
														ae.setType(pcfType);
														ae.setQueueName(queueName);
														ae.setValues(pcfArrayValue);
														if (pcfArrayValue[0] > 0) {
															stats.add(ae);
															log.info("PUT MAX: " + pcfQueueName + " { " + pcfArrayValue[0] + ", " + pcfArrayValue[1] + " } ");

														}
														
													//	foundPCFEntry = true;
													}
													//break msgPCFRecords;
												}
											
												break;

											case (MQConstants.MQIAMO_GET_MAX_BYTES):
												if (Arrays.binarySearch(this.searchPCF, MQConstants.MQIAMO_GET_MAX_BYTES) >= 0) {
													
													MQCFIL max = (MQCFIL) grpPCFParams;
													pcfArrayValue = max.getValues();
													if (pcfQueueName != "") {
														pcfType = MQConstants.MQIAMO_GET_MAX_BYTES;

														AccountingEntity ae = new AccountingEntity();
														ae.setType(pcfType);
														ae.setQueueName(queueName);
														ae.setValues(pcfArrayValue);
														if (pcfArrayValue[0] > 0) {
															stats.add(ae);
															log.info("GET MAX: " + pcfQueueName + " { " + pcfArrayValue[0] + ", " + pcfArrayValue[1] + " } ");
						
														}
										
												//		foundPCFEntry = true;
													}
													break msgPCFRecords;
												}
											
												break;
												
											default:
												break;
										} // end of switch
									} // end of loop 'a'
									
							        break;
							}
						//	log.info("Type: " + p.getType());
							break;
					}
				} // end of inner loop		        
		        
				this.gmo.options = MQConstants.MQGMO_BROWSE_NEXT | 
						MQConstants.MQGMO_NO_WAIT | 
						MQConstants.MQGMO_CONVERT;
				
				/*
				if (foundPCFEntry) {
					AccountingEntity ae = new AccountingEntity();
					ae.setType(pcfType);
					ae.setQueueName(queueName);
					ae.setValues(pcfArrayValue);
					if (pcfArrayValue[0] > 0) {
						stats.add(ae);
						if (pcfType == MQConstants.MQIAMO_PUT_MAX_BYTES) {
							log.info("PUT MAX: " + pcfQueueName + " { " + pcfArrayValue[0] + ", " + pcfArrayValue[1] + " } ");
						}
						if (pcfType == MQConstants.MQIAMO_GET_MAX_BYTES) {
							log.info("GET MAX: " + pcfQueueName + " { " + pcfArrayValue[0] + ", " + pcfArrayValue[1] + " } ");
						}

					}
					
				}
				*/
				
			} // end of loop
			
			
		} catch (MQException e) {
			// not bothered if we get an error ...
			
		} 

		if (getDebugLevel() == LEVEL.TRACE) {
			DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
			Date date = new Date();
			log.info("End accounting time   : " + dateFormat.format(date));
		}
		
		return stats;
	}
	
	/*
	 * Close the connection to the queue manager
	 */
	public void CloseConnection(MQQueueManager qm, PCFMessageAgent ma) {
		
    	try {
    		if (qm.isConnected()) {
	    		if (getDebugLevel() == LEVEL.DEBUG) { log.debug("Closing MQ Connection "); }
    			qm.disconnect();
    		}
    	} catch (Exception e) {
    		// do nothing
    	}
    	
    	try {
	    	if (ma != null) {
	    		if (getDebugLevel() == LEVEL.DEBUG) { log.debug("Closing PCF agent "); }
	        	ma.disconnect();
	    	}
    	} catch (Exception e) {
    		// do nothing
    	}
	}
	
}
