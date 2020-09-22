# MQ Metrics API

## MQ Exporter for Prometheus monitoring

This repository contains Java microservice code for a monitoring solution that exports queue manager metrics to a Prometheus data collection system.  It also contains example configuration files on how to run the monitoring program.

The monitor collects metrics from an IBM MQ v9, v8 or v7 queue manager.  The monitor, polls metrics from the queue manager every 10 seconds, which can be changed in the configuration file.  Prometheus can be configured to call the exposed end-point at regular intervals to pull these metrics into its database, where they can be queried directly or used with dashboard applications such as Grafana.

The API can be run as a service or from a Docker container.

## Configure IBM MQ

The API can be run in 3 ways;

* Local binding connection
* Client connection
* Client Channel Defintion Table connection

