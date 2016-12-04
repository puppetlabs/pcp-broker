# Logging in pcp-broker

We use the [puppetlabs/structured-logging](https://github.com/puppetlabs/structured-logging)
to log certain events as they are handled by the broker.

## Configuration

pcp-broker relies on [logback](http://logback.qos.ch/). The logging
configuration file should be specified by setting the `global.logging-config`
entry in the *global.conf* file in the [configuration](./configuration.md)
directory. Example:

```
    global: {
      logging-config = ./eng-resources/logback-dev.xml
    }
```

An example of logging configuration file is `test-resources/logback-dev.xml`.

## Levels and their uses

The default logging level (INFO) for the broker should be fairly quiet, this is the
intended scheme for logging levels:

* ERROR - only really bad things
* WARN - mostly bad things
* INFO - service started/stopped
* DEBUG - client connecting, client session establishment, client disconnected, message acceptance, message delivery
* TRACE - messages moving through delivery, message information during association.

## HTTP access log

This logger is implemented by Jetty and the
[logback-access](http://logback.qos.ch/access.html) module. It logs information
about the HTTP request sent by a PCP client when the WebSocket handshake with
the pcp-broker starts.

You can enable it by simply placing the *request-logging.xml* file (contained
in the *test-resources/log* directory) in the same directory where your logback
configuration file is stored (see above). Note that *request-logging.xml* is the
default file that logback looks for when configuring logback-access; for further
details, please refer to the [logback-access](http://logback.qos.ch/access.html)
documentation.

The pattern used in the provided *request-logging.xml* follows the
[Common Log Format](https://en.wikipedia.org/wiki/Common_Log_Format). It will
log entries like:

```
0:0:0:0:0:0:0:1 - - [02/Aug/2016:20:44:55 +0100] "GET /pcp/ HTTP/1.1" 101 0 "-" "WebSocket++/0.7.0" 2
0:0:0:0:0:0:0:1 - - [02/Aug/2016:20:45:07 +0100] "GET /pcp/vNext HTTP/1.1" 101 0 "-" "WebSocket++/0.7.0" 2
```

## PCP access log

The `pcp_access` logger provides information about incoming PCP messages.

To enable it you must set the level of `puppetlabs.pcp.broker.pcp_access` to
*INFO* in your logback configuration file (see above). The
*test-resources/logback-dev.xml* and *logback-test.xml* files provide examples
of logback configuration where the `pcp_access` logger is configured to *ERROR*;
such log level will prevent any pcp_access entry.

Each pcp_access entry is composed of 8 fields:

```
[<date time>] <access outcome> <sender: IP address and port> <sender: SSL common name> <sender: PCP URI> <PCP messagetype> <PCP message id> [<list of PCP targets>]
```

Examples of log entries are:

```
[2016-07-28 14:25:56,364] AUTHORIZATION_SUCCESS 0:0:0:0:0:0:0:1:61955 0002agent.example.com pcp://0002agent.example.com/test_agent pcp-test-response 8abdfc04-3b74-4a39-97b5-ff237c9acdb0 ["pcp://0001controller.example.com/test_controller"]
[2016-07-29 18:53:43,606] AUTHORIZATION_SUCCESS 0:0:0:0:0:0:0:1:62360 pxp-agent.example.com pcp://pxp-agent.example.com/agent http://puppetlabs.com/associate_request 311fd3ef-02fa-469d-b88b-d7f81b5b3acc ["pcp:///server"]
```

The second entry gives the outcome of the message validation; possible values
are:

| validation outcome | log level | description
|--------------------|-----------|------------
| DESERIALIZATION_ERROR | WARN | invalid PCP message that can't be deserialized
| AUTHENTICATION_FAILURE | WARN | authentication failure (refer to the [authentication](./authentication.md) section)
| AUTHORIZATION_FAILURE | WARN | authorization failure (refer to the [authorization](./authorization.md) section)
| EXPIRED | WARN | the message's TTL expired
| AUTHORIZATION_SUCCESS | INFO | the message will be processed by pcp-broker

For each incoming message, the `pcp_access` logger will produce only one entry.
All entries will be logged at *WARN* level, except `AUTHORIZATION_SUCCESS` that
is logged as *INFO*.

You can find a trivial python script for parsing `pcp_access` log files
[here](../resources/messages_log_parser.py).

## Types of messages

In order to allow you to more readily search through your structured
logs we add a :type field to each log entry that we generate.  These
types are as follows:


### `broker-init`

The broker is being initalised

Level: INFO

### `broker-start`

The broker is being started

Level: INFO

### `broker-started`

The broker has been started

Level: DEBUG

### `broker-unhandled-message`

The broker recieved a message targetted at it that it cannot handle.

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* `messagetype` String - The message_type of the message

Level: DEBUG

### `broker-state-transition-unknown`

The broker couldn't find a handler function for the named state.

* `state` String - the state

Level: ERROR

### `broker-stop`

The broker is stopping

Level: INFO

### `broker-stopped`

The broker has been stopped

Level: DEBUG

### `connection-open`

A client has connected to the broker.

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* [`uri`](#uri)

Level: DEBUG

### `connection-message`

Received a message from a client

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* [`messageid`](#messageid)
* [`messagetype`](#messagetype)
* [`sender`](#sender)
* [`destination`](#destination)

Level: TRACE

### `connection-already-associated`

A client that is associated attempted to associate again.

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* `uri` String - PCP URI of the new association
* `existinguri` String - PCP URI of the existing association

Level: WARN

### `connection-association-failed`

A client failed to become associated with the broker

* [`uri`](#uri)
* `reason` String - the reason given for association failure

Level: DEBUG

### `connection-no-peer-certificate`

A client didn't provide a x509 peer certifcate when connecting.

* [`remoteaddress`](#remoteaddress)

Level: DEBUG

### `connection-message-before-association`

A client sent a message before it was associated with the broker

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* [`messageid`](#messageid)
* [`messagetype`](#messagetype)
* [`sender`](#sender)
* [`destination`](#destination)

Level: WARN

### `connection-error`

A websocket session error on a connection.

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)

Level: ERROR

### `connection-close`

A client has disconnected from the broker.

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* [`uri`](#uri)
* `statuscode` Numeric - the websockets status code
* `reason`  String - the reason given for disconnection

Level: DEBUG

### `send-message`

Sending a PCP message.

* [`uri`](#uri)
* `rawmsg` String - the decoded PCP message

Level: TRACE

### `message-authorization`

A message has been checked if it can be relayed by the broker.

* [`messageid`](#messageid)
* [`messagetype`](#messagetype)
* [`sender`](#sender)
* [`destination`](#destination)
* `allowed` Boolean - was the message allowed
* `message` String - why the message was allowed/denied

Level: TRACE

Note: a level WARN is also used for messages that failed validation prior to
authorization.

### `incoming-message-trace`

A received message.

* [`uri`](#uri)
* `rawmsg` String - the decoded PCP message

Level: TRACE

### `processing-error`

An error occured while processing a message.

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* [`messageid`](#messageid)
* [`messagetype`](#messagetype)
* [`sender`](#sender)
* [`destination`](#destination)

Level: DEBUG

### `message-delivery-failure`

A message delivery failed. This may not be fatal, the message may be retried later.

* [`messageid`](#messageid)
* [`messagetype`](#messagetype)
* [`sender`](#sender)
* [`destination`](#destination)
* `reason` String - description of why the delivery failed

Level: TRACE

### `outgoing-message-trace`

A message being sent.

* [`uri`](#uri)
* [`rawmsg`](#rawmsg)

Level: TRACE

### `message-delivery`

A message is being delivered to a client.

* [`messageid`](#messageid)
* [`messagetype`](#messagetype)
* [`sender`](#sender)
* [`destination`](#destination)
* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)

Level: DEBUG

### `message-delivery-error`

An exception was raised during message delivery.

Level: ERROR

# Common data

The pcp-broker manages connections and messages, and so log data will
often include there common properties about these primitives.

## General

### `uri`

PCP URI of the associated connection

## Connections

When reporting about connections we provide the following pieces of
information:

### `commonname`

The Common Name from the x509 Client certificate

### `remoteaddress`

The host:port of the remote end of the connection

## Messages

When reporting about messages we provide the following pieces of
information:

### `messageid`

The string uuid of a message

### `messagetype`

The string identifying the type of message

### `source`

The sender of the message as a PCP URI

### `destination`

An array or single PCP URI
