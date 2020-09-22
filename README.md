# MQ Metrics API

## MQ Exporter for Prometheus monitoring

This repository contains Java microservice code for a monitoring solution that exports queue manager metrics to a Prometheus data collection system.  It also contains example configuration files on how to run the monitoring program.

The monitor collects metrics from an IBM MQ v9, v8 or v7 queue manager.  The monitor, polls metrics from the queue manager every 10 seconds, which can be changed in the configuration file.  Prometheus can be configured to call the exposed end-point at regular intervals to pull these metrics into its database, where they can be queried directly or used with dashboard applications such as Grafana.

The API can be run as a service or from a Docker container.

## Configure IBM MQ

The API can be run in three ways;

* Local binding connection
* Client connection
* Client Channel Defintion Table connection

### Local Binding connections

When running with a local binding connection, the API and the queue manager must be running on the same host.  The API connects directly to the queue manager.  No security or authentication is required, as the API is deemed to be authenticated due to it running on the same host.

### Client Connections

When running as a client connection, the API and the queue manager run on seperate servers, the API connects to the queue manager over a network.  The queue manager must be configured to expose a running MQ listener, have a configured server-connection channel and the appropriate authorites set against the MQ objects (queue manager, queues, channels etc) to issue PCF commands.

### Client Channel Defintion Table (CCDT) connections

When running as a CCDT connection, this is similar to a client connection, with the client connection details stored in a secure, binary file.

