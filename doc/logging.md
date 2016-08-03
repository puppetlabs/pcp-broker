# Logging in pcp-broker

We use the [puppetlabs/structured-logging][https://github.com/puppetlabs/structured-logging]
to log certain events as they are handled by the broker.

## Configuration

pcp-broker uses [logback][http://logback.qos.ch/]. The logging configuration
file should be specified by setting the `global.logging-config` entry in the
*global.conf* file in the [configuration][./configuration.md] directory.
Example:

'''
    global: {
      logging-config = ./eng-resources/logback-dev.xml
    }
'''


## Levels and their uses

The default logging level (INFO) for the broker should be fairly quiet, this is the
intended scheme for logging levels:

* ERROR - only really bad things
* WARN - mostly bad things
* INFO - service started/stopped
* DEBUG - client connecting, client session establishment, client disconnected, message acceptance, message delivery
* TRACE - messages moving through queues, message information during association.

## HTTP access log

This logger is implemented by Jetty and the
[logback-access][http://logback.qos.ch/access.html] module. It logs information
about the HTTP request sent by a PCP client when the WebSocket handshake with
the pcp-broker starts.

You can enable it by simply placing the *request-logging.xml* file (contained
in the *test-resources/log* directory) in the same directory where your logback
configuration file is stored (see above). Note that *request-logging.xml* is the
default file that logback looks for when configuring logback-access; for further
details, please refer to the [logback-access][http://logback.qos.ch/access.html]
documentation.

The pattern used in the provided *request-logging.xml* follows the
[Common Log Format][https://en.wikipedia.org/wiki/Common_Log_Format]. It will
log entries like:

'''
0:0:0:0:0:0:0:1 - - [02/Aug/2016:20:44:55 +0100] "GET /pcp/ HTTP/1.1" 101 0 "-" "WebSocket++/0.7.0" 2
0:0:0:0:0:0:0:1 - - [02/Aug/2016:20:45:07 +0100] "GET /pcp/vNext HTTP/1.1" 101 0 "-" "WebSocket++/0.7.0" 2
'''

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

Level: DEBUG

### `connection-message`

Received a message from a client

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)

Level: TRACE

### `connection-already-associated`

A client that is associated attempted to associate again.

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* `uri` String - PCP uri of the new association
* `existinguri` String - PCP uri of the existing association

Level: WARN

### `connection-association-failed`

A client failed to become associated with the broker

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* `uri` String - PCP uri of the association

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
* `statuscode` Numeric - the websockets status code
* `reason`  String - the reason given for disconnection

Level: DEBUG

### `message-authorization`

A message has been checked if it can be relayed by the broker.

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)
* `allowed` Boolean - was the message allowed
* `message` String - why the message was allowed/denied

Level: TRACE

### `message-expired`

A message has hit its expiry (discovered when sending).

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)

Level: TRACE

### `message-expired-from-server`

A message that hit its expiry was sourced from the server.

Level: TRACE

### `message-delivery-failure`

A message delivery failed.   This may not be fatal, the message may be retried later.

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)
* `reason` String - description of why the delivery failed

Level: TRACE

### `message-redelivery`

A message has been scheduled for redelivery.

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)
* `delay` Number - how far in the future will we retry delivery

Level: TRACE

### `message-delivery`

A message is being delivered to a client.

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)
* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)

Level: DEBUG

### `message-deliver-error`

An exception was raised during message delivery.

Level: ERROR

### `queue-enqueue`

A message is being spooled to an internal queue

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)
* `queue`  String - name of the queue

Level: TRACE

### `queue-dequeue`

A message is being consumed from an internal queue

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)
* `queue`  String - name of the queue

Level: TRACE

### `queue-dequeue-error`

A failure happened in consuming/processing a message from an internal queue

* `queue`  String - name of the queue

Level: ERROR

# Common data

The pcp-broker manages connections and messages, and so log data will
often include there common properties about these primitives.

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

### `source`

The sender of the message as a PCP Uri

### `destination`

An array or single PCP Uri
