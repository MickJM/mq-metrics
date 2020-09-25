# MQ Metrics API

## MQ Exporter for Prometheus monitoring

This repository contains Java Spring Boot, microservice code for a monitoring solution that exports queue manager metrics to a Prometheus data collection system.  It also contains example configuration files on how to run the monitoring program.

The monitor collects metrics from an IBM MQ v9, v8 or v7 queue manager.  The monitor, polls metrics from the queue manager every 10 seconds, which can be changed in the configuration file.  Prometheus can be configured to call the exposed end-point at regular intervals to pull these metrics into its database, where they can be queried directly or used with dashboard applications such as Grafana.

The API can be run as a service or from a Docker container.

## Configure IBM MQ

The API can be run in three ways;

* Local binding connection
* Client connection
* Client Channel Defintion Table connection

### Local Binding connections

When running with a local binding connection, the API and the queue manager must be running on the same host.  The API connects directly to the queue manager.  No security or authentication is required, as the API is deemed to be authenticated due to it running on the same host.

```
ibm.mq.queueManager: QMGR
ibm.mq.local: true
```

No additional queue manager properties are required, but the APIs common properties can still be used.

### Client Connections

When running as a client connection, the API and the queue manager run on seperate servers, the API connects to the queue manager over a network.  The queue manager must be configured to expose a running MQ listener, have a configured server-connection channel and the appropriate authorities set against the MQ objects (queue manager, queues, channels etc) to issue PCF commands.

Minimum yaml requirements in the application-XXXX.yaml file

```
ibm.mq.queueManager: QMGR
ibm.mq.channel: SVRCONN.CHANNEL.NAME
ibm.mq.connName: HOSTNAME(PORT)
ibm.mq.user: MQUser
ibm.mq.password: secret
ibm.mq.authenticateUsingCSP: true
ibm.mq.local: false
```

`ibm.mq.local` can be true of false, depending if the API connects to queue manager in local binding or client mode.


Connections to the queue manager should be encrpyted where possible.  For this, the queue manager needs to be configured with a key-store / trust-store - which can be the same file - and the server-connection channel needs to be configured with a cipher.

```
ibm.mq.useSSL: true
ibm.mq.sslCipherSpec: TLS_RSA_WITH_AES_128_CBC_SHA256
ibm.mq.ibmCipherMapping: false 
ibm.mq.security.truststore: {fully qualified file path}/truststore 
ibm.mq.security.truststore-password: secret
ibm.mq.security.keystore: {fully qualified file path}/keystore 
ibm.mq.security.keystore-password: secret
```

`ibm.mq.useSSL` can be true of false, depending if the MQ server connection channel is configured to be SSL/TLS enabled.

`ibm.mq.ibmCipherMapping` can be true of false, depending on the JVM being used.

### Client Channel Defintion Table (CCDT) connections

When running with a CCDT connection, this is similar to a client connection, with the client connection details stored in a secure, binary file.

```
ibm.mq.queueManager: QMGR
ibm.mq.ccdtFile: {fully qualified file path}/AMQCLCHL.TAB 
```

All configurations are stored in the Spring Boot yaml or properties file, which it typically located in a `./config` folder under where the API jar file is run from.

## API Endpoints

By default the output of the API is in Prometheus format, but can also be returned in JSON format.

Currently, the API endpoints do not use HTTPS for clients - this will be added in future releases.

### Promtheus Output 

Invoking the API using the below URL, will generate a response in Prometheus format.

`http://{hostname}:{port}/actuator/prometheus`

### JSON Output 

Invoking the API using the below URL, will generate a response in JSON format.

`http://{hostname}:{port}/json/allmetrics` - Returns ALL metrics, including MQ metrics and System metrics generated by the API

`http://{hostname}:{port}/json/mqmetrics` - Returns only MQ metrics - these are all metrics prefixed `mq:`

JSON output can be sorted into Ascending or Descending order using the following properties.

`ibm.mq.json.sort:true|false` - The sort value can be true or false.  True sorts the JSON response into the required order.
`ibm.mq.json.order:ascending|descending` - The order in which to sort the JSON response. By default, the order is in ascending order.

## Filter MQ objects

MQ objects (queues, channels and listeners) can be filtered in or out if required.

### Queues

`ibm.mq.objects.queues.include: Q1, Q2` - List of queues separated by a comma.  For all queues, use an *

`ibm.mq.objects.queues.exclude: SYSTEM.,AMQ.` - List of queues to exclude, separated by a comma

### Channels

`ibm.mq.objects.channels.include: CHANNEL1, CHANNEL2` - List of channel names separated by a comma.  For all channels, use an "*"

`ibm.mq.objects.channels.exclude: SYSTEM.,AMQ.` - List of channel names to exclude, separated by a comma

### Listeners

`ibm.mq.objects.listeners.include: "*"` - List of listener names separated by a comma.  For all listeners, use an "*"

`ibm.mq.objects.listeners.exclude:SYSTEM.` - List of listener names to exclude, separated by a comma

The type of listeners can also be included or excluded.  The most common type of listener is TCP, but others are available.

`ibm.mq.objects.listeners.types.include: TCP` - List of listener types separated by a comma.  

`ibm.mq.objects.listeners.types.exclude:"*"` - List of listener types to exclude, separated by a comma

## Common API properites

Additional properties can be used in the yaml file;

`logging.level.org.springfromwork: OFF` - Spring Framework logging

`logging.level.maersk.com: debug-level` - Maersk objects to debug

`debug-level` can be `OFF`, `INFO`, `DEBUG`, `WARN` or `TRACE`

`spring.security.user.name: username` - The username used to authenticate the API when being invoked
`spring.security.user.password: secret` - The password used to authenticate the API when being invoked

`ibm.mq.clearMetrics: 5` - Reset and clear the metrics every 5 iterations

`ibm.mq.keepMetricsWhenQueueManagerIsDown` - Can be true or false, when true, Metrics are not reset and cleared when the API disconnects from the queue manager.

`ibm.mq.event.delayInMilliSeconds: 10000` -This is the time, in milliseconds, between each iteration of when the metrics are collected.

## Running the API

Running the API microservice is easy;

`java -jar mq-metric-1.0.0.17.jar --spring.active.profiles=xxxxxx`

Where xxxxxx is the sufix name of the yaml file.

## Technical details

The following PCF properties are used to get the MQ metrics

Property | Type | Description
---------| -----| -----------
MQCA_CLUSTER_Q_MGR_NAME | Queue Manager | Name of the queue manager
MQCA_CLUSTER_NAME | Queue Manager | Cluster name
MQIACF_Q_MGR_STATUS | Queue Manager | Queue manager status 
MQIACF_CMD_SERVER_STATUS | Queue Manager | Command server status
MQIACF_CMD_SERVER_STATUS | Queue Manager | Command server status
MQCA_Q_NAME | Queue | Queue name
MQIA_Q_TYPE | Queue | Queue type
MQIA_USAGE | Queue | Queue usage
MQIA_CURRENT_Q_DEPTH | Queue | Queue depth
MQCA_CLUSTER_NAME | Queue | The MQ cluster name
MQIA_OPEN_INPUT_COUNT | Queue | Number of threads that have the queue open for input
MQIA_OPEN_OUTPUT_COUNT | Queue | Number of threads that have the queue open for output
MQIA_MAX_Q_DEPTH | Queue | Maximum number of messages allowed on the queue
MQCACF_LAST_GET_DATE | Queue | Last GET date in String format 
MQCACF_LAST_GET_TIME | Queue | Last GET date in String format (Date and Time converted EPOCH date)
MQIACF_OLDEST_MSG_AGE | Queue | Oldest message age in seconds
MQIA_MSG_DEQ_COUNT | Queue | Number of messages de-queued (read from queues)
MQIA_MSG_ENQ_COUNT | Queue | Number of messages en-queued ( written to queues)
MQIACF_OPEN_INQUIRE | Queue | Open for Inquire
MQIACF_OPEN_OUTPUT | Queue | Open for Output
MQCACH_CHANNEL_NAME | Channel | Channel name
MQIACH_CHANNEL_TYPE | Channel | Channel type
MQCA_CLUSTER_NAME | Channel | Cluster name on the Channel
MQIACH_INDOUBT_STATUS | Channel | Channel inboubt status
MQCACH_CURRENT_LUWID | Channel | Current Logical Unit of Work ID
MQCACH_LAST_LUWID | Channel | Last Logical Unit of Work ID
MQIACH_DISC_INTERVAL | Channel | Channel disconnect internal
MQIACH_HB_INTERVAL | Channel | Channel Heart Beat value
MQIACH_KEEP_ALIVE_INTERVAL | Channel | Channel keep alive value
MQCACH_CONNECTION_NAME | Channel | Application connect name
MQIACH_MSGS | Channel | Sum of all messages over the channel
MQIACH_BYTES_RECEIVED | Channel | Sum of all bytes received over the channel
MQIACH_BYTES_SENT | Channel | Sum of all bytes sent across the channel
MQIACH_MAX_MSG_LENGTH | Channel | Maximum message size of the channel
MQIA_APPL_TYPE | Connections | Application type
MQCACF_APPL_TAG | Connections | Application tag (name)
MQCACF_USER_IDENTIFIER | Connections | Application userid
MQIACF_PROCESS_ID | Connections | Process id on the queue manager
MQCACH_CHANNEL_NAME | Connections | Channel name used by the connection
MQCACH_CONNECTION_NAME | Connections | Name of the connection
MQCACH_LISTENER_NAME | Listener | Name of the listener
MQIACH_XMIT_PROTOCOL_TYPE | Listener | Listener type
MQIACF_LISTENER_STATUS_ATTRS | Listener | Listener status
MQIACH_PORT | Listener | Listener port number


## Example output

Prometheus output

### Queue Depth

The number of messages currently on queues.

```
mq:queueDepth{cluster="DEMOA",queueManagerName="QMAP01",queueName="SERVICEA.REQ",queueType="Local",usage="Normal",} 0.0
mq:queueDepth{cluster="",queueManagerName="QMAP01",queueName="SYSTEM.ADMIN.ACCOUNTING.QUEUE",queueType="Local",usage="Normal",} 24.0
mq:queueDepth{cluster="",queueManagerName="QMAP01",queueName="NEWALIAS",queueType="Alias",usage="Alias",} 0.0
mq:queueDepth{cluster="DEMOA",queueManagerName="QMAP01",queueName="CARTA",queueType="Local",usage="Normal",} 0.0
```

### Oldest message age

The number of seconds that the message has been on the queue

```
mq:oldestMsgAge{cluster="",queueManagerName="QMAP01",queueName="SYSTEM.ADMIN.ACCOUNTING.QUEUE",queueType="Local",usage="Normal",} 342.0
mq:oldestMsgAge{cluster="",queueManagerName="QMAP01",queueName="CPU_QMGR",queueType="Local",usage="Normal",} 243.0
```

